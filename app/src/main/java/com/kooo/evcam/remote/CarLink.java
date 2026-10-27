package com.kooo.evcam.remote;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * Car side of 7xDash remote watch.
 * Dials {@code /ws/car}, shows a QR from the code the hub sends, and pushes a JPEG
 * only while a phone has an active watch.
 */
public final class CarLink {

    public static final String HTTP = "https://7x-dash.vercel.app";
    public static final String WS = "wss://7xdash-production.up.railway.app";

    public interface Ui {
        void onLink(Snapshot snap);
    }

    /**
     * Outside speaker and megaphone volume, set from 7xDash web. {@link #status} is the car's
     * {@code {"t":"speaker",…}} message; {@link #command} runs on the main thread.
     */
    public interface Speaker {
        JSONObject status();

        void command(JSONObject msg);
    }

    /** Fired when the phone starts/stops watch or changes camera source. */
    public interface WatchListener {
        void onWatch(boolean watching, String source);

        /**
         * The phone cannot play the live video ({@code {"t":"novideo","reason":…}} from 7xDash web):
         * send stills for the rest of this watch. Main thread.
         */
        default void onNoVideo(String reason) {
        }
    }

    public static final class Snapshot {
        public final String state;
        public final String code;
        public final String url;
        public final String email;
        public final boolean watching;
        public final String source;

        Snapshot(String state, String code, String url, String email, boolean watching, String source) {
            this.state = state;
            this.code = code;
            this.url = url;
            this.email = email;
            this.watching = watching;
            this.source = source;
        }
    }

    private final Context context;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService jpegExec = Executors.newSingleThreadExecutor();
    private final AtomicBoolean watching = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean reconnectPosted = new AtomicBoolean(false);
    private final AtomicInteger generation = new AtomicInteger(0);

    private WebSocket socket;
    private long lastPushMs;
    private String email = "";
    private volatile String source = "drive";
    private String lastCode = "";
    private Ui ui;
    private WatchListener watchListener;
    private volatile Speaker speaker;

    /** A QR code lives 10 min on the hub; swap it before then while nobody is linked. */
    private static final long CODE_REFRESH_MS = 8 * 60 * 1000L;
    private final Runnable codeRefresh = new Runnable() {
        @Override
        public void run() {
            if (running.get() && email.isEmpty()) {
                refreshCode();
            }
            armCodeRefresh();
        }
    };

    private final Runnable reconnect = new Runnable() {
        @Override
        public void run() {
            reconnectPosted.set(false);
            if (running.get()) {
                dial();
            }
        }
    };

    public CarLink(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setUi(Ui ui) {
        this.ui = ui;
    }

    public void setWatchListener(WatchListener listener) {
        this.watchListener = listener;
    }

    public void setSpeaker(Speaker speaker) {
        this.speaker = speaker;
        sendSpeaker();
    }

    /** Tells the hub the speaker state now (on, volumes). Safe from any thread. */
    public void sendSpeaker() {
        sendSpeaker(socket);
    }

    private void sendSpeaker(WebSocket ws) {
        Speaker current = speaker;
        if (current == null || ws == null) {
            return;
        }
        try {
            JSONObject msg = current.status();
            if (msg != null) {
                msg.put("t", "speaker");
                ws.send(msg.toString());
            }
        } catch (Exception ignored) {
        }
    }

    public void start() {
        running.set(true);
        dial();
        armCodeRefresh();
    }

    /** Ask the hub for a new QR code. The old one stops working. */
    public void refreshCode() {
        WebSocket ws = socket;
        if (ws == null) {
            return;
        }
        try {
            JSONObject msg = new JSONObject();
            msg.put("t", "code");
            ws.send(msg.toString());
        } catch (Exception ignored) {
        }
    }

    private void armCodeRefresh() {
        handler.removeCallbacks(codeRefresh);
        if (running.get()) {
            handler.postDelayed(codeRefresh, CODE_REFRESH_MS);
        }
    }

    public void stop() {
        running.set(false);
        generation.incrementAndGet();
        handler.removeCallbacks(reconnect);
        handler.removeCallbacks(codeRefresh);
        reconnectPosted.set(false);
        watching.set(false);
        WebSocket ws = socket;
        socket = null;
        if (ws != null) {
            ws.close(1000, "stop");
        }
        jpegExec.shutdown();
    }

    public boolean isWatching() {
        return watching.get();
    }

    /** What the phone asked to see: drive, ch1–ch4, driver, backseat. */
    public String watchSource() {
        return source;
    }

    public void pushPreview(Bitmap frame) {
        if (!watching.get() || frame == null) {
            if (frame != null) {
                frame.recycle();
            }
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastPushMs < 350) {
            frame.recycle();
            return;
        }
        lastPushMs = now;
        if (jpegExec.isShutdown()) {
            frame.recycle();
            return;
        }
        jpegExec.execute(() -> {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
                if (!frame.compress(Bitmap.CompressFormat.JPEG, 55, out)) {
                    return;
                }
                WebSocket ws = socket;
                if (ws != null && watching.get()) {
                    ws.send(ByteString.of(out.toByteArray()));
                }
            } finally {
                frame.recycle();
            }
        });
    }

    /** Above this much unsent uplink, video deltas are dropped until the next keyframe. */
    private static final long VIDEO_BACKLOG_BYTES = 768 * 1024;
    private volatile boolean videoNeedKey;

    /**
     * One H.264 packet (see {@link RemoteStream#frame}). Order matters, so nothing is
     * coalesced; when the uplink backs up, deltas are dropped until a keyframe fits.
     *
     * @return false when the packet was dropped
     */
    public boolean pushVideo(byte[] packet, boolean key) {
        if (!watching.get() || packet == null || packet.length == 0) {
            return true;
        }
        WebSocket ws = socket;
        if (ws == null) {
            return true;
        }
        if (ws.queueSize() > VIDEO_BACKLOG_BYTES) {
            videoNeedKey = true;
            return false;
        }
        if (videoNeedKey && !key) {
            return false;
        }
        videoNeedKey = false;
        ws.send(ByteString.of(packet));
        return true;
    }

    /** Already a JPEG. Caller owns the array. */
    public void pushJpeg(byte[] jpeg) {
        if (!watching.get() || jpeg == null || jpeg.length == 0) {
            return;
        }
        WebSocket ws = socket;
        if (ws != null) {
            ws.send(ByteString.of(jpeg));
        }
    }

    /**
     * Where did I park ({@code {"t":"park",…}}). Underground there is often no signal, so the
     * newest one waits here and goes out on every hello until the hub says {@code parked}.
     */
    private volatile String pendingPark;

    public void sendPark(String json) {
        pendingPark = json;
        flushPark();
    }

    private void flushPark() {
        String json = pendingPark;
        WebSocket ws = socket;
        if (json != null && ws != null && !email.isEmpty()) {
            ws.send(json);
        }
    }

    private void dial() {
        int gen = generation.incrementAndGet();
        WebSocket old = socket;
        if (old != null) {
            old.cancel();
        }
        publish("CONNECTING", "", email);
        Request req = new Request.Builder().url(WS + "/ws/car").build();
        socket = client.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                if (gen != generation.get()) {
                    return;
                }
                try {
                    JSONObject hello = new JSONObject();
                    hello.put("t", "hello");
                    hello.put("device", deviceId());
                    webSocket.send(hello.toString());
                } catch (Exception ignored) {
                }
                publish("ONLINE", "", email);
                sendSpeaker(webSocket);
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                if (gen != generation.get()) {
                    return;
                }
                try {
                    JSONObject j = new JSONObject(text);
                    String type = j.optString("t");
                    if ("hello".equals(type)) {
                        email = j.optString("email");
                        boolean wasWatching = watching.get();
                        String prevSource = source;
                        watching.set(j.optBoolean("watch", false));
                        source = norm(j.optString("source", source));
                        if (watching.get()) {
                            lastPushMs = 0L;
                        }
                        publish("ONLINE", j.optString("code"), email);
                        notifyWatch(wasWatching, prevSource);
                        flushPark();
                    } else if ("parked".equals(type)) {
                        String sent = pendingPark;
                        if (sent != null && j.optLong("at") == new JSONObject(sent).optLong("at")) {
                            pendingPark = null;
                        }
                    } else if ("code".equals(type)) {
                        publish("ONLINE", j.optString("code"), email);
                        handler.post(CarLink.this::armCodeRefresh);
                    } else if ("linked".equals(type)) {
                        email = j.optString("email", email);
                        publish("LINKED", "", email);
                        flushPark();
                    } else if ("unlinked".equals(type)) {
                        email = "";
                        boolean wasWatching = watching.get();
                        String prevSource = source;
                        watching.set(false);
                        publish("ONLINE", j.optString("code"), "");
                        notifyWatch(wasWatching, prevSource);
                    } else if ("watch".equals(type)) {
                        boolean wasWatching = watching.get();
                        String prevSource = source;
                        source = norm(j.optString("source", "drive"));
                        watching.set(true);
                        lastPushMs = 0L;
                        publish("ONLINE", "", email);
                        notifyWatch(wasWatching, prevSource);
                    } else if ("speaker".equals(type)) {
                        Speaker current = speaker;
                        if (current != null) {
                            handler.post(() -> current.command(j));
                        }
                    } else if ("novideo".equals(type)) {
                        WatchListener listener = watchListener;
                        String reason = j.optString("reason");
                        if (listener != null) {
                            handler.post(() -> listener.onNoVideo(reason));
                        }
                    } else if ("volume".equals(type)) {
                        // The car's main volume, one step per tap on 7xDash web
                        int step = j.optInt("step");
                        if (step == 1 || step == -1) {
                            com.kooo.evcam.audio.CarVolume.step(context, step);
                            com.kooo.evcam.analytics.Analytics.event("remote",
                                    "command", step > 0 ? "volume_up" : "volume_down");
                            com.kooo.evcam.blackbox.BlackBox.note("car-link: volume " + (step > 0 ? "up" : "down"));
                        }
                    } else if ("idle".equals(type)) {
                        boolean wasWatching = watching.get();
                        String prevSource = source;
                        watching.set(false);
                        publish("ONLINE", "", email);
                        notifyWatch(wasWatching, prevSource);
                    }
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                if (gen != generation.get()) {
                    return;
                }
                dropWatch();
                publish("OFFLINE " + code, "", email);
                scheduleReconnect();
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                if (gen != generation.get()) {
                    return;
                }
                dropWatch();
                String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                publish("FAIL " + msg, "", email);
                scheduleReconnect();
            }
        });
    }

    private void publish(String state, String code, String mail) {
        if (code != null && !code.trim().isEmpty()) {
            lastCode = code.trim().toUpperCase();
        }
        String url = lastCode.isEmpty() ? "" : HTTP + "/link?c=" + lastCode;
        Snapshot snap = new Snapshot(state, lastCode, url, mail == null ? "" : mail, watching.get(), source);
        Ui target = ui;
        if (target == null) {
            return;
        }
        handler.post(() -> {
            if (ui != null) {
                ui.onLink(snap);
            }
        });
    }

    private void scheduleReconnect() {
        if (!running.get()) {
            return;
        }
        if (!reconnectPosted.compareAndSet(false, true)) {
            return;
        }
        handler.postDelayed(reconnect, 3000);
    }

    /** Link dropped: nobody is watching any more. Tell the listener so it lets the cameras go. */
    private void dropWatch() {
        boolean wasWatching = watching.getAndSet(false);
        notifyWatch(wasWatching, source);
    }

    private void notifyWatch(boolean wasWatching, String prevSource) {
        boolean now = watching.get();
        String src = source;
        if (now == wasWatching && src.equals(prevSource == null ? "" : prevSource)) {
            return;
        }
        WatchListener listener = watchListener;
        if (listener == null) {
            return;
        }
        handler.post(() -> listener.onWatch(now, src));
    }

    private String deviceId() {
        String aid = "";
        try {
            aid = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Exception ignored) {
        }
        String model = (Build.MANUFACTURER + "_" + Build.MODEL).replace(' ', '_');
        if (aid == null || aid.isEmpty()) {
            return model;
        }
        return model + "_" + aid;
    }

    /** Same whitelist as the hub / web client. */
    public static String normalizeSource(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase();
        if ("drive".equals(s) || "driver".equals(s) || "backseat".equals(s)
                || "ch1".equals(s) || "ch2".equals(s) || "ch3".equals(s) || "ch4".equals(s)) {
            return s;
        }
        return "drive";
    }

    private static String norm(String raw) {
        return normalizeSource(raw);
    }
}
