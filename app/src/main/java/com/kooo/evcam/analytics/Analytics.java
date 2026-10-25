package com.kooo.evcam.analytics;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.BuildConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Anonymous usage stats to Google Analytics 4 over the Measurement Protocol (plain HTTPS, no
 * Google Play Services on the head unit).
 *
 * <p>No-op when the build has no measurement id / api secret (GA_MEASUREMENT_ID, GA_API_SECRET from
 * the environment or local.properties), and until the person said yes: in the first-open question
 * ({@link UsageConsent}) or with Settings → Interface → Anonymous usage stats. Sends a random
 * install id, never ANDROID_ID, email, plate or location.</p>
 *
 * <p>Events wait in memory and go out together, at most 25 per request, {@link #BATCH_DELAY_S}
 * after the first one: a day of normal use is a few requests, tens of KB. A failed send keeps the
 * events for the next one; events still waiting when the process dies are lost.</p>
 */
public final class Analytics {

    private static final String TAG = "Analytics";
    private static final String PREFS = "analytics";
    private static final String KEY_INSTALL_ID = "install_id";
    private static final String KEY_PENDING_CRASH = "pending_crash";
    private static final String ENDPOINT = "https://www.google-analytics.com/mp/collect";
    private static final MediaType JSON = MediaType.get("application/json");
    static final int BATCH_DELAY_S = 60;
    private static final int MAX_QUEUED = 200;

    @SuppressLint("StaticFieldLeak") // application context
    private static Context app;
    private static String clientId;
    private static long sessionId;
    private static Map<String, String> userProps;
    private static ScheduledExecutorService worker;
    private static OkHttpClient http;
    private static final List<AnalyticsPayload.Event> queue = new ArrayList<>();
    private static boolean flushScheduled;
    private static volatile boolean on;
    private static boolean openSent;

    private Analytics() {
    }

    public static boolean configured() {
        return !BuildConfig.GA_MEASUREMENT_ID.isEmpty() && !BuildConfig.GA_API_SECRET.isEmpty();
    }

    /**
     * Main process only, from Application.onCreate. With a remembered yes, sends app_open (and
     * app_crash if the last run crashed) now; otherwise once the person says yes this run.
     */
    public static synchronized void start(Context context) {
        if (app != null) {
            return;
        }
        app = context.getApplicationContext();
        AppConfig config = new AppConfig(app);
        on = UsageConsent.sendsAtStart(config.isUsageStatsEnabled(), config.isUsageStatsRemembered());
        if (!configured()) {
            AppLog.i(TAG, "off: no measurement id / api secret in this build");
            return;
        }
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        clientId = prefs.getString(KEY_INSTALL_ID, null);
        if (clientId == null) {
            clientId = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_INSTALL_ID, clientId).apply();
        }
        sessionId = System.currentTimeMillis() / 1000L;
        userProps = new LinkedHashMap<>();
        userProps.put("app_version", BuildConfig.VERSION_NAME);
        userProps.put("system_build", Build.DISPLAY);
        userProps.put("app_language", config.getLanguageMode());
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "analytics");
            t.setDaemon(true);
            return t;
        });
        http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();

        if (on) {
            sendOpen();
        }
    }

    /** app_open (and app_crash from the last run) once per process, when sending is allowed. */
    private static synchronized void sendOpen() {
        if (openSent || worker == null) {
            return;
        }
        openSent = true;
        event("app_open", "app_version", BuildConfig.VERSION_NAME, "system_build", Build.DISPLAY);
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String crash = prefs.getString(KEY_PENDING_CRASH, null);
        if (crash != null) {
            prefs.edit().remove(KEY_PENDING_CRASH).apply();
            event("app_crash", "exception", crash);
        }
    }

    /** Sending allowed: the person said yes (remembered, or this run). */
    static boolean enabled() {
        return on;
    }

    /**
     * The person answered (first-open question or Settings switch). Yes sends app_open if this run
     * has not yet; no drops what is waiting and sends nothing more.
     */
    public static void setEnabled(boolean enabled) {
        on = enabled;
        if (enabled) {
            sendOpen();
        } else {
            synchronized (queue) {
                queue.clear();
            }
        }
    }

    /** Queue an event. {@code params} are name/value pairs. Never throws, never blocks. */
    public static void event(String name, String... params) {
        try {
            if (worker == null || !enabled()) {
                return;
            }
            Map<String, String> map = new LinkedHashMap<>();
            for (int i = 0; i + 1 < params.length; i += 2) {
                map.put(params[i], params[i + 1]);
            }
            AnalyticsPayload.Event e = new AnalyticsPayload.Event(name, map, System.currentTimeMillis());
            synchronized (queue) {
                queue.add(e);
                while (queue.size() > MAX_QUEUED) {
                    queue.remove(0);
                }
                if (!flushScheduled) {
                    flushScheduled = true;
                    worker.schedule(Analytics::flush, BATCH_DELAY_S, TimeUnit.SECONDS);
                }
            }
        } catch (RuntimeException e) {
            AppLog.w(TAG, "event " + name + " dropped", e);
        }
    }

    /** A duration as a small set of values (0, 1, 5, 15, 30, 60, 120 minutes) instead of exact times. */
    public static String minutes(long ms) {
        return AnalyticsPayload.minutesBucket(ms);
    }

    /**
     * From the crash handler: remember the exception class (only the class, no message or stack)
     * so the next start sends app_crash. Written with commit, the process is about to end.
     */
    public static void noteCrash(Throwable throwable) {
        try {
            if (app == null || worker == null || !enabled() || throwable == null) {
                return;
            }
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_PENDING_CRASH, throwable.getClass().getName())
                    .commit();
        } catch (RuntimeException ignored) {
            // the crash handler goes on regardless
        }
    }

    private static void flush() {
        List<AnalyticsPayload.Event> batch;
        synchronized (queue) {
            flushScheduled = false;
            if (queue.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(queue.subList(0, Math.min(queue.size(), AnalyticsPayload.MAX_EVENTS)));
        }
        if (!enabled()) {
            return;
        }
        String body = AnalyticsPayload.build(clientId, sessionId, userProps, batch, BuildConfig.DEBUG);
        String url = ENDPOINT + "?measurement_id=" + BuildConfig.GA_MEASUREMENT_ID
                + "&api_secret=" + BuildConfig.GA_API_SECRET;
        boolean sent = false;
        try (Response response = http.newCall(new Request.Builder()
                .url(url)
                .post(RequestBody.create(body, JSON))
                .build()).execute()) {
            // 2xx means received; GA4 does not say whether the events were valid (see /debug/mp/collect)
            sent = response.isSuccessful();
            if (!sent) {
                AppLog.w(TAG, "send failed: HTTP " + response.code());
            }
        } catch (Exception e) {
            AppLog.d(TAG, "send failed: " + e.getMessage());
        }
        synchronized (queue) {
            if (sent) {
                queue.removeAll(batch);
                AppLog.d(TAG, String.format(Locale.ROOT, "sent %d events, %d bytes", batch.size(), body.length()));
            }
            // more waiting, or a failed send: try again later
            if (!queue.isEmpty() && !flushScheduled) {
                flushScheduled = true;
                worker.schedule(Analytics::flush, sent ? 1 : BATCH_DELAY_S * 5L, TimeUnit.SECONDS);
            }
        }
    }
}
