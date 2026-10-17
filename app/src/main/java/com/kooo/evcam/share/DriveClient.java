package com.kooo.evcam.share;

import com.kooo.evcam.AppLog;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 跟 Google 说话：设备码登录、刷新令牌、在「Zeekr Shortcut」文件夹里建文件。
 */
public final class DriveClient {

    private static final String TAG = "DriveClient";
    private static final String DEVICE_URL = "https://oauth2.googleapis.com/device/code";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String FILES_URL = "https://www.googleapis.com/drive/v3/files";
    private static final String UPLOAD_URL =
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id";
    private static final String USER_AGENT = "ZeekrShortcut-Drive";
    private static final int CONNECT_MS = 15000;
    private static final int READ_MS = 20000;

    /** 正在 PUT 的连接。取消时从界面线程掐掉它，上传线程才会从 write 里出来。 */
    private static volatile HttpURLConnection activePut;

    private DriveClient() {
    }

    public static DriveProtocol.DeviceCode requestDeviceCode(String clientId) throws IOException {
        Reply reply = postForm(DEVICE_URL, DriveProtocol.deviceCodeBody(clientId), null, READ_MS);
        DriveProtocol.DeviceCode code = DriveProtocol.parseDeviceCode(reply.body);
        if (reply.code != 200 || code == null) {
            throw new IOException(fail("device code", reply));
        }
        return code;
    }

    public static DriveProtocol.Poll poll(String json) {
        return DriveProtocol.classifyPoll(json);
    }

    public static Reply pollOnce(String clientId, String clientSecret, String deviceCode)
            throws IOException {
        return postForm(TOKEN_URL, DriveProtocol.pollBody(clientId, clientSecret, deviceCode),
                null, READ_MS);
    }

    public static DriveProtocol.Tokens refresh(String clientId, String clientSecret,
                                                String refreshToken, long nowMs) throws IOException {
        Reply reply = postForm(TOKEN_URL,
                DriveProtocol.refreshBody(clientId, clientSecret, refreshToken), null, READ_MS);
        DriveProtocol.Tokens tokens = DriveProtocol.parseTokens(reply.body, refreshToken, nowMs);
        if (reply.code != 200 || tokens == null) {
            throw new IOException(fail("refresh", reply));
        }
        return tokens;
    }

    /** 找已有文件夹，没有就建一个。返回文件夹 id。 */
    public static String ensureFolder(String accessToken) throws IOException {
        String query = java.net.URLEncoder.encode(DriveProtocol.folderQuery(), "UTF-8");
        String url = FILES_URL + "?spaces=drive&pageSize=1&fields=files(id)&q=" + query;
        Reply listed = get(url, accessToken);
        if (listed.code == 401) {
            throw new AuthExpired();
        }
        if (listed.code != 200) {
            throw new IOException(fail("list folder", listed));
        }
        String existing = DriveProtocol.firstFileId(listed.body);
        if (existing != null && !existing.isEmpty()) {
            return existing;
        }
        Reply created = postJson(FILES_URL, DriveProtocol.folderMetadata(), accessToken);
        if (created.code == 401) {
            throw new AuthExpired();
        }
        String id = DriveProtocol.firstFileId(created.body);
        if ((created.code != 200 && created.code != 201) || id == null || id.isEmpty()) {
            throw new IOException(fail("create folder", created));
        }
        return id;
    }

    /** 上传进度。{@code done} 和 {@code total} 都是这一个文件的字节数。 */
    public interface Progress {
        void onProgress(long done, long total);
    }

    /** 掐掉正在进行的文件 PUT。没有上传时什么都不做。 */
    public static void abortPut() {
        HttpURLConnection connection = activePut;
        if (connection != null) {
            connection.disconnect();
        }
    }

    /** 把 {@code payload} 上传成 {@code displayName}。成功时返回文件 id。 */
    public static String upload(String accessToken, String folderId, File payload, String displayName,
                                Progress progress, AtomicBoolean cancel) throws IOException {
        if (payload == null || !payload.isFile() || payload.length() <= 0) {
            throw new IOException("empty");
        }
        String mime = DriveProtocol.mimeFor(displayName);
        Reply started = postJson(UPLOAD_URL, DriveProtocol.fileMetadata(displayName, folderId),
                accessToken, mime, payload.length());
        if (started.code == 401) {
            throw new AuthExpired();
        }
        if (started.location == null || started.location.isEmpty()) {
            throw new IOException(fail("start upload", started));
        }
        Reply done = putFile(started.location, payload, mime, progress, cancel);
        if (done.code == 401) {
            throw new AuthExpired();
        }
        String id = DriveProtocol.firstFileId(done.body);
        if ((done.code != 200 && done.code != 201) || id == null) {
            throw new IOException(fail("upload", done));
        }
        AppLog.i(TAG, "已上传 " + displayName + "（" + payload.length() + " 字节）");
        return id;
    }

    /** 访问令牌失效。调用方刷新后再试一次。 */
    public static final class AuthExpired extends IOException {
        AuthExpired() {
            super("401");
        }
    }

    static final class Reply {
        final int code;
        final String body;
        final String location;

        Reply(int code, String body, String location) {
            this.code = code;
            this.body = body == null ? "" : body;
            this.location = location;
        }
    }

    private static Reply get(String url, String accessToken) throws IOException {
        HttpURLConnection connection = open(url, "GET", accessToken, READ_MS);
        try {
            return read(connection);
        } finally {
            connection.disconnect();
        }
    }

    private static Reply postForm(String url, String form, String accessToken, int readMs)
            throws IOException {
        byte[] bytes = form.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = open(url, "POST", accessToken, readMs);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        connection.setFixedLengthStreamingMode(bytes.length);
        try {
            OutputStream out = connection.getOutputStream();
            out.write(bytes);
            out.close();
            return read(connection);
        } finally {
            connection.disconnect();
        }
    }

    private static Reply postJson(String url, String json, String accessToken) throws IOException {
        return postJson(url, json, accessToken, null, -1);
    }

    private static Reply postJson(String url, String json, String accessToken,
                                  String uploadMime, long uploadLength) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = open(url, "POST", accessToken, READ_MS);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        if (uploadMime != null) {
            connection.setRequestProperty("X-Upload-Content-Type", uploadMime);
            connection.setRequestProperty("X-Upload-Content-Length", String.valueOf(uploadLength));
        }
        connection.setFixedLengthStreamingMode(bytes.length);
        try {
            OutputStream out = connection.getOutputStream();
            out.write(bytes);
            out.close();
            return read(connection);
        } finally {
            connection.disconnect();
        }
    }

    private static Reply putFile(String url, File payload, String mime, Progress progress,
                                 AtomicBoolean cancel) throws IOException {
        if (cancel != null && cancel.get()) {
            throw new IOException("cancelled");
        }
        HttpURLConnection connection = open(url, "PUT", null, 0);
        connection.setRequestProperty("Content-Type", mime);
        connection.setFixedLengthStreamingMode(payload.length());
        long total = payload.length();
        long done = 0;
        activePut = connection;
        try {
            OutputStream out = connection.getOutputStream();
            InputStream in = new BufferedInputStream(new FileInputStream(payload));
            try {
                byte[] buffer = new byte[256 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (cancel != null && cancel.get()) {
                        throw new IOException("cancelled");
                    }
                    out.write(buffer, 0, n);
                    done += n;
                    if (progress != null) {
                        progress.onProgress(done, total);
                    }
                }
            } finally {
                in.close();
                out.close();
            }
            if (cancel != null && cancel.get()) {
                throw new IOException("cancelled");
            }
            if (progress != null) {
                progress.onProgress(total, total);
            }
            return read(connection);
        } finally {
            if (activePut == connection) {
                activePut = null;
            }
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String url, String method, String accessToken, int readMs)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_MS);
        connection.setReadTimeout(readMs);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/json");
        if (accessToken != null && !accessToken.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        }
        if (!"GET".equals(method)) {
            connection.setDoOutput(true);
        }
        return connection;
    }

    private static Reply read(HttpURLConnection connection) throws IOException {
        int code = connection.getResponseCode();
        InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = stream == null ? "" : readLimited(stream);
        return new Reply(code, body, connection.getHeaderField("Location"));
    }

    private static String readLimited(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int total = 0;
        int n;
        while ((n = stream.read(chunk)) != -1 && total < 1024 * 1024) {
            buffer.write(chunk, 0, n);
            total += n;
        }
        stream.close();
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String fail(String step, Reply reply) {
        String detail = reply.body == null ? "" : reply.body.replace('\n', ' ').trim();
        if (detail.length() > 160) {
            detail = detail.substring(0, 160);
        }
        return step + " HTTP " + reply.code + (detail.isEmpty() ? "" : " " + detail);
    }
}
