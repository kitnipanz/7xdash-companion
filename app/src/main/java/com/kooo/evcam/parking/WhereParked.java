package com.kooo.evcam.parking;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Where did I park. When the car goes into P (or on the shortcut), read the floor and bay
 * signs from the front and side cameras, put them in the shade with a photo, and send them
 * to 7xDash web over the Car Link.
 *
 * <p>Needs the main screen alive: the frames come from its surround preview.</p>
 */
public final class WhereParked implements Telemetry.Listener {

    private static final String TAG = "WhereParked";
    private static final String USER = "where-parked";
    /** Let the picture settle after the shift (the side mirrors fold, the car rocks). */
    private static final long SETTLE_MS = 2000L;
    private static final int PHOTO_W = 640;
    private static final int PHOTO_H = 360;
    private static final int PHOTO_QUALITY = 60;
    private static final int MAX_LINES = 30;

    /** Stream lanes we read, with the name the web shows. Back is useless for signs. */
    private static final int[] LANES = {0, 2, 3};
    private static final String[] NAMES = {"front", "left", "right"};

    private static final WhereParked INSTANCE = new WhereParked();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "where-parked");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final Runnable autoCapture = () -> capture(false);

    private Context app;
    /** The main screen is up (frames can be grabbed). */
    private boolean started;
    private boolean watching;
    private String lastGear;
    private TextRecognizer recognizer;

    private WhereParked() {
    }

    public static WhereParked get() {
        return INSTANCE;
    }

    /** The main screen is up: watch the gear if the home switch is on. Main thread. */
    public void start(Context context) {
        app = context.getApplicationContext();
        started = true;
        update();
    }

    /** Main thread. */
    public void stop() {
        started = false;
        update();
    }

    /** The home "Auto on P" switch moved. Main thread. */
    public void settingsChanged(Context context) {
        if (app == null) {
            app = context.getApplicationContext();
        }
        update();
    }

    public static boolean autoEnabled(Context context) {
        return new AppConfig(context).isParkingAutoEnabled();
    }

    private void update() {
        boolean want = started && app != null && autoEnabled(app);
        if (want == watching) {
            return;
        }
        watching = want;
        Telemetry telemetry = Telemetry.get();
        if (want) {
            lastGear = telemetry.readings().text(Signal.GEAR);
            telemetry.addListener(this);
            telemetry.acquire(app, USER);
        } else {
            main.removeCallbacks(autoCapture);
            telemetry.removeListener(this);
            telemetry.release(USER);
            lastGear = null;
        }
        AppLog.d(TAG, "auto on P " + (want ? "on" : "off"));
    }

    @Override
    public void onReadingsChanged(Readings readings) {
        if (!watching) {
            return;
        }
        String gear = readings.text(Signal.GEAR);
        String before = lastGear;
        lastGear = gear;
        if (gear == null || gear.equals(before)) {
            return;
        }
        main.removeCallbacks(autoCapture);
        if ("P".equals(gear) && ("D".equals(before) || "R".equals(before) || "N".equals(before))) {
            main.postDelayed(autoCapture, SETTLE_MS);
        }
    }

    /** Shortcut "Where did I park". */
    public void captureNow(Context context) {
        if (app == null) {
            app = context.getApplicationContext();
        }
        main.post(() -> capture(true));
    }

    private void capture(boolean manual) {
        Context ctx = app;
        if (ctx == null || !busy.compareAndSet(false, true)) {
            return;
        }
        MainActivity activity = MainActivity.getInstance();
        if (activity == null) {
            busy.set(false);
            if (manual) {
                ParkingNote.noCamera(ctx);
            }
            return;
        }
        AppLog.d(TAG, (manual ? "shortcut" : "shifted to P") + ": reading signs");
        if (manual) {
            ParkingNote.reading(ctx);
        }
        activity.grabParkingLanes(channels -> {
            if (channels == null) {
                busy.set(false);
                ParkingNote.noCamera(ctx);
                return;
            }
            worker.execute(() -> {
                try {
                    read(ctx, channels);
                } catch (RuntimeException e) {
                    AppLog.w(TAG, "read failed: " + e.getMessage());
                    ParkingNote.noCamera(ctx);
                } finally {
                    for (Bitmap b : channels) {
                        if (b != null) {
                            b.recycle();
                        }
                    }
                    busy.set(false);
                }
            });
        });
    }

    /** Worker thread. */
    private void read(Context ctx, Bitmap[] channels) {
        List<String> all = new ArrayList<>();
        int best = -1;
        int bestScore = -1;
        String[] photos = new String[LANES.length];
        for (int i = 0; i < LANES.length; i++) {
            Bitmap frame = LANES[i] < channels.length ? channels[LANES[i]] : null;
            if (frame == null) {
                continue;
            }
            List<String> lines = ocr(frame);
            all.addAll(lines);
            ParkingText.Spot own = ParkingText.pick(lines);
            int score = (own.floor.isEmpty() ? 0 : 2) + (own.bay.isEmpty() ? 0 : 2) + Math.min(1, lines.size());
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
            photos[i] = jpegBase64(frame);
        }
        ParkingText.Spot spot = ParkingText.pick(all);
        long at = System.currentTimeMillis();
        AppLog.d(TAG, "read " + all.size() + " line(s) -> floor=" + spot.floor + " bay=" + spot.bay);

        Bitmap picture = null;
        if (best >= 0) {
            Bitmap src = channels[LANES[best]];
            picture = Bitmap.createScaledBitmap(src, PHOTO_W, PHOTO_H, true);
        }
        ParkingNote.show(ctx, spot, at, picture);

        try {
            JSONObject msg = new JSONObject();
            msg.put("t", "park");
            msg.put("floor", spot.floor);
            msg.put("bay", spot.bay);
            msg.put("at", at);
            msg.put("best", best >= 0 ? NAMES[best] : "");
            JSONArray lines = new JSONArray();
            for (int i = 0; i < all.size() && i < MAX_LINES; i++) {
                lines.put(all.get(i));
            }
            msg.put("lines", lines);
            JSONObject pics = new JSONObject();
            for (int i = 0; i < NAMES.length; i++) {
                if (photos[i] != null) {
                    pics.put(NAMES[i], photos[i]);
                }
            }
            msg.put("photos", pics);
            String json = msg.toString();
            main.post(() -> {
                MainActivity activity = MainActivity.getInstance();
                if (activity != null && activity.getCarLink() != null) {
                    activity.getCarLink().sendPark(json);
                }
            });
        } catch (Exception e) {
            AppLog.w(TAG, "park message failed: " + e.getMessage());
        }
    }

    private List<String> ocr(Bitmap frame) {
        List<String> out = new ArrayList<>();
        if (recognizer == null) {
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        }
        try {
            Text text = Tasks.await(recognizer.process(InputImage.fromBitmap(frame, 0)), 8, TimeUnit.SECONDS);
            for (Text.TextBlock block : text.getTextBlocks()) {
                for (Text.Line line : block.getLines()) {
                    String s = line.getText().trim();
                    if (!s.isEmpty()) {
                        out.add(s);
                    }
                }
            }
        } catch (Exception e) {
            AppLog.w(TAG, "ocr failed: " + e.getMessage());
        }
        return out;
    }

    private static String jpegBase64(Bitmap frame) {
        Bitmap small = Bitmap.createScaledBitmap(frame, PHOTO_W, PHOTO_H, true);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(48 * 1024);
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
