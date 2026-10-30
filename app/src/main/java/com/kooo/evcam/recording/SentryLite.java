package com.kooo.evcam.recording;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.screen.ScreenState;
import com.kooo.evcam.storage.AutoLock;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Telemetry;
import com.kooo.evcam.telemetry.VehicleState;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;

/**
 * Sentry-lite: while the car is parked and the head unit still records the surround view, someone
 * moving near the car saves an instant capture (10 s before and after, {@link InstantCapture}),
 * takes a photo from each side and sends the event to 7xDash web, which lists it.
 *
 * <p>Motion comes from the surround recorder's frame sizes ({@link MotionMeter}); the recorder
 * hands every frame to {@link #onSample}. Armed only when the setting is on, recording, in P and
 * either the screen has been dark for {@link #SETTLE_MS} (the driver has walked away) or the car's
 * own Sentry Mode is on. It only works while the head unit is awake: it cannot wake it.</p>
 */
public final class SentryLite implements Telemetry.Listener, ScreenState.Listener {

    private static final String TAG = "SentryLite";
    /** The screen goes dark as the driver leaves: give them this long to walk away. */
    static final long SETTLE_MS = 60_000L;
    /** One event, then quiet for this long (a capture covers 20 s). */
    static final long COOLDOWN_MS = 60_000L;
    private static final int PHOTO_W = 320;
    private static final int PHOTO_H = 240;
    private static final int PHOTO_QUALITY = 60;
    /** Stream lanes of the surround picture, in order. */
    private static final String[] LANE_NAMES = {"front", "rear", "left", "right"};

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static SentryLite installed;

    private final Context app;
    private final AppConfig config;
    private final MotionMeter meter = new MotionMeter();
    /** Read on the encoder thread, set on the main thread. */
    private volatile boolean armed;
    private boolean recording;
    private long lastEventAt = -COOLDOWN_MS;
    private final Runnable settle = this::reconsider;

    private SentryLite(Context context) {
        app = context.getApplicationContext();
        config = new AppConfig(app);
    }

    /** Application start. Repeat calls do nothing. */
    public static void install(Context context) {
        if (installed != null) {
            return;
        }
        installed = new SentryLite(context);
        Telemetry.get().addListener(installed);
        ScreenState.addListener(installed);
        applyConfig();
    }

    /** The setting changed. Any thread. */
    public static void applyConfig() {
        SentryLite s = installed;
        if (s != null) {
            MAIN.post(s::reconsider);
        }
    }

    /** Recording started or stopped. Any thread. */
    public static void setRecording(boolean on) {
        SentryLite s = installed;
        if (s != null) {
            MAIN.post(() -> {
                s.recording = on;
                s.reconsider();
            });
        }
    }

    /** One encoded frame of the surround recorder. Encoder thread; cheap when not armed. */
    public static void onSample(int size, boolean keyframe) {
        SentryLite s = installed;
        if (s == null || !s.armed) {
            return;
        }
        if (s.meter.feed(size, keyframe, SystemClock.elapsedRealtime())) {
            String why = s.meter.describe();
            MAIN.post(() -> s.motion(why));
        }
    }

    @Override
    public void onReadingsChanged(Readings readings) {
        MAIN.post(this::reconsider);
    }

    @Override
    public void onScreenOff() {
        MAIN.removeCallbacks(settle);
        MAIN.postDelayed(settle, SETTLE_MS);
        MAIN.post(this::reconsider);
    }

    @Override
    public void onScreenOn() {
        MAIN.removeCallbacks(settle);
        MAIN.post(this::reconsider);
    }

    /** Main thread. */
    private void reconsider() {
        VehicleState s = Telemetry.get().latest();
        boolean want = shouldArm(config.isSentryLiteEnabled(), recording, s.gear,
                ScreenState.dark(), ScreenState.darkForMs(), s.sentry);
        if (want == armed) {
            return;
        }
        if (want) {
            // the meter is only fed while armed: its still level starts fresh
            meter.reset();
        }
        armed = want;
        AppLog.i(TAG, want ? "armed" : "disarmed");
        BlackBox.note(want ? "哨兵简版：开始看画面动静" : "哨兵简版：不再看画面动静");
    }

    /** Pure, see the test. Gear is often unreadable once the car is locked: dark and unknown counts as parked. */
    static boolean shouldArm(boolean enabled, boolean recording, String gear, boolean dark, long darkForMs,
                             Integer carSentry) {
        if (!enabled || !recording) {
            return false;
        }
        boolean parked = "P".equals(gear) || (gear == null && dark);
        if (!parked) {
            return false;
        }
        boolean carWatching = carSentry != null && carSentry >= 1;
        return carWatching || (dark && darkForMs >= SETTLE_MS);
    }

    /** Main thread. */
    private void motion(String why) {
        if (!armed) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastEventAt < COOLDOWN_MS) {
            AppLog.d(TAG, "motion within cooldown: " + why);
            return;
        }
        lastEventAt = now;
        long at = System.currentTimeMillis();
        AppLog.i(TAG, "motion: " + why);
        BlackBox.noteImportant("哨兵简版：画面有动静（" + why + "），存即时片段");
        AutoLock.get().instantFromSentry(app);
        grabAndSend(at, why);
    }

    /** Photo from each side if the camera picture can be grabbed, then the event to 7xDash web. */
    private void grabAndSend(long at, String why) {
        MainActivity activity = MainActivity.getInstance();
        if (activity == null) {
            send(at, why, null);
            return;
        }
        activity.grabParkingLanes(channels -> {
            JSONObject photos = null;
            if (channels != null) {
                photos = new JSONObject();
                try {
                    for (int i = 0; i < channels.length && i < LANE_NAMES.length; i++) {
                        String jpeg = channels[i] == null ? null : jpegBase64(channels[i]);
                        if (jpeg != null) {
                            photos.put(LANE_NAMES[i], jpeg);
                        }
                    }
                } catch (Exception e) {
                    AppLog.w(TAG, "photo failed: " + e.getMessage());
                } finally {
                    for (Bitmap b : channels) {
                        if (b != null) {
                            b.recycle();
                        }
                    }
                }
            }
            send(at, why, photos);
        });
    }

    private void send(long at, String why, JSONObject photos) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("t", "sentry");
            msg.put("at", at);
            msg.put("note", why);
            if (photos != null && photos.length() > 0) {
                msg.put("photos", photos);
            }
            String json = msg.toString();
            MAIN.post(() -> {
                MainActivity activity = MainActivity.getInstance();
                if (activity != null && activity.getCarLink() != null) {
                    activity.getCarLink().sendSentry(json);
                } else {
                    AppLog.w(TAG, "no Car Link: event stays on the USB stick only");
                }
            });
        } catch (Exception e) {
            AppLog.w(TAG, "sentry message failed: " + e.getMessage());
        }
    }

    private static String jpegBase64(Bitmap frame) {
        Bitmap small = Bitmap.createScaledBitmap(frame, PHOTO_W, PHOTO_H, true);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
            if (!small.compress(Bitmap.CompressFormat.JPEG, PHOTO_QUALITY, out)) {
                return null;
            }
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } finally {
            if (small != frame) {
                small.recycle();
            }
        }
    }
}
