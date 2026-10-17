package com.kooo.evcam.share;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * Google 设备码登录和 Drive 上传用到的纯文本。不联网，方便单测。
 */
public final class DriveProtocol {

    public static final String SCOPE = "https://www.googleapis.com/auth/drive.file";
    public static final String FOLDER_NAME = "Zeekr Shortcut";
    public static final String FOLDER_MIME = "application/vnd.google-apps.folder";

    private DriveProtocol() {
    }

    public static final class DeviceCode {
        public final String deviceCode;
        public final String userCode;
        public final String verificationUrl;
        public final String qrUrl;
        public final int intervalSec;
        public final int expiresInSec;

        DeviceCode(String deviceCode, String userCode, String verificationUrl, String qrUrl,
                   int intervalSec, int expiresInSec) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.verificationUrl = verificationUrl;
            this.qrUrl = qrUrl;
            this.intervalSec = intervalSec;
            this.expiresInSec = expiresInSec;
        }
    }

    public static final class Tokens {
        public final String accessToken;
        public final String refreshToken;
        public final long expiresAtMs;

        public Tokens(String accessToken, String refreshToken, long expiresAtMs) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresAtMs = expiresAtMs;
        }
    }

    public enum Poll {
        PENDING, SLOW_DOWN, GRANTED, DENIED, FAILED
    }

    public static String deviceCodeBody(String clientId) {
        return "client_id=" + enc(clientId) + "&scope=" + enc(SCOPE);
    }

    public static String pollBody(String clientId, String clientSecret, String deviceCode) {
        return "client_id=" + enc(clientId)
                + "&client_secret=" + enc(clientSecret)
                + "&device_code=" + enc(deviceCode)
                + "&grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code");
    }

    public static String refreshBody(String clientId, String clientSecret, String refreshToken) {
        return "client_id=" + enc(clientId)
                + "&client_secret=" + enc(clientSecret)
                + "&refresh_token=" + enc(refreshToken)
                + "&grant_type=refresh_token";
    }

    /** 认不出设备码时返回 null。 */
    public static DeviceCode parseDeviceCode(String json) {
        String device = stringField(json, "device_code");
        String user = stringField(json, "user_code");
        if (device == null || device.isEmpty() || user == null || user.isEmpty()) {
            return null;
        }
        String page = stringField(json, "verification_url");
        if (page == null || page.isEmpty()) {
            page = stringField(json, "verification_uri");
        }
        if (page == null || page.isEmpty()) {
            page = "https://www.google.com/device";
        }
        String complete = stringField(json, "verification_uri_complete");
        int interval = (int) longField(json, "interval", 5);
        if (interval < 5) {
            interval = 5;
        }
        int expires = (int) longField(json, "expires_in", 1800);
        return new DeviceCode(device, user, page,
                complete != null && !complete.isEmpty() ? complete : page,
                interval, expires);
    }

    public static Poll classifyPoll(String json) {
        if (stringField(json, "access_token") != null) {
            return Poll.GRANTED;
        }
        String error = stringField(json, "error");
        if (error == null) {
            return Poll.FAILED;
        }
        if ("authorization_pending".equals(error)) {
            return Poll.PENDING;
        }
        if ("slow_down".equals(error)) {
            return Poll.SLOW_DOWN;
        }
        if ("access_denied".equals(error) || "expired_token".equals(error)) {
            return Poll.DENIED;
        }
        return Poll.FAILED;
    }

    /**
     * 登录或刷新成功时返回令牌。刷新响应里常常不带新的 refresh_token，这时沿用 {@code previousRefresh}。
     */
    public static Tokens parseTokens(String json, String previousRefresh, long nowMs) {
        String access = stringField(json, "access_token");
        if (access == null || access.isEmpty()) {
            return null;
        }
        String refresh = stringField(json, "refresh_token");
        if (refresh == null || refresh.isEmpty()) {
            refresh = previousRefresh;
        }
        long expiresIn = longField(json, "expires_in", 3600);
        long expiresAt = nowMs + expiresIn * 1000L - 60_000L;
        return new Tokens(access, refresh == null ? "" : refresh, expiresAt);
    }

    public static boolean accessFresh(String accessToken, long expiresAtMs, long nowMs) {
        return accessToken != null && !accessToken.isEmpty() && nowMs < expiresAtMs;
    }

    public static String fileMetadata(String name, String parentId) {
        return "{\"name\":" + quote(name) + ",\"parents\":[" + quote(parentId) + "]}";
    }

    public static String folderMetadata() {
        return "{\"name\":" + quote(FOLDER_NAME) + ",\"mimeType\":" + quote(FOLDER_MIME) + "}";
    }

    public static String folderQuery() {
        return "name = '" + FOLDER_NAME + "' and mimeType = '" + FOLDER_MIME
                + "' and trashed = false";
    }

    /** 列表响应里第一个文件 id。没有时返回 null。 */
    public static String firstFileId(String json) {
        return stringField(json, "id");
    }

    public static String mimeFor(String name) {
        String lower = name == null ? "" : name.toLowerCase(java.util.Locale.US);
        if (lower.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".txt") || lower.endsWith(".json")) {
            return "text/plain";
        }
        return "application/octet-stream";
    }

    static String quote(String value) {
        String s = value == null ? "" : value;
        StringBuilder out = new StringBuilder(s.length() + 2);
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '"') {
                out.append('\\');
            }
            if (c == '\n') {
                out.append("\\n");
                continue;
            }
            out.append(c);
        }
        out.append('"');
        return out.toString();
    }

    static String stringField(String json, String key) {
        if (json == null || key == null) {
            return null;
        }
        String needle = "\"" + key + "\"";
        int from = 0;
        while (from < json.length()) {
            int i = json.indexOf(needle, from);
            if (i < 0) {
                return null;
            }
            int colon = json.indexOf(':', i + needle.length());
            if (colon < 0) {
                return null;
            }
            int p = colon + 1;
            while (p < json.length() && Character.isWhitespace(json.charAt(p))) {
                p++;
            }
            if (p >= json.length() || json.charAt(p) != '"') {
                from = i + needle.length();
                continue;
            }
            StringBuilder out = new StringBuilder();
            for (int q = p + 1; q < json.length(); q++) {
                char c = json.charAt(q);
                if (c == '\\' && q + 1 < json.length()) {
                    out.append(json.charAt(++q));
                    continue;
                }
                if (c == '"') {
                    return out.toString();
                }
                out.append(c);
            }
            return null;
        }
        return null;
    }

    static long longField(String json, String key, long fallback) {
        if (json == null) {
            return fallback;
        }
        String needle = "\"" + key + "\"";
        int i = json.indexOf(needle);
        if (i < 0) {
            return fallback;
        }
        int colon = json.indexOf(':', i + needle.length());
        if (colon < 0) {
            return fallback;
        }
        int p = colon + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) {
            p++;
        }
        int end = p;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        if (end == p) {
            return fallback;
        }
        try {
            return Long.parseLong(json.substring(p, end));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }
}
