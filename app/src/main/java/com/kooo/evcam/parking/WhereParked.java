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
import java.util.function.Consumer;

/**
 * Where did I park. When the car goes into P (or on the shortcut), read the floor and bay
 * signs from the front and side cameras, put them in the shade with a photo, and send them
 * to 7xDash web over the Car Link.
 *
 * <p>Pillar scan: while creeping under {@link #CREEP_KMH} in D or R, keep reading the signs every
 * few seconds and remember the latest floor and bay seen. The HUD shows each new one; at P the
 * remembered floor or bay fills in what the parked read could not see.</p>
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
    /** Pillar scan runs below this speed. */
    private static final float CREEP_KMH = 3f;
    /** Gap between pillar scans (after the last one finished). */
    private static final long SCAN_MS = 3000L;
    /** A remembered floor or bay older than this is dropped (left the car park and came back). */
    private static final long MEMORY_MS = 10 * 60 * 1000L;
    /** The read at P waits for a running pillar scan this often, this many times. */
    private static final long BUSY_RETRY_MS = 500L;
    private static final int BUSY_RETRIES = 20;

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
    private final Runnable autoCapture = () -> capture(false, 0);
    private final Runnable creepScan = this::scan;

    private Context app;
    /** The main screen is up (frames can be grabbed). */
    private boolean started;
    private boolean watching;
    private String lastGear;
    /** In D or R under {@link #CREEP_KMH} with the pillar scan on. Main thread. */
    private boolean creeping;
    private TextRecognizer recognizer;

    /** What the pillar scan saw last. Guarded by {@code memoryLock}. */
    private final Object memoryLock = new Object();
    private String seenFloor = "";
    private String seenBay = "";
    private long seenFloorAt;
    private long seenBayAt;

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

    /** The home "Auto on P" or "Pillar scan" switch or the Beta features switch moved. Main thread. */
    public void settingsChanged(Context context) {
        if (app == null) {
            app = context.getApplicationContext();
        }
        update();
    }

    /** Auto on P runs only while Beta features is on (this is a beta feature). */
    public static boolean autoEnabled(Context context) {
        AppConfig config = new AppConfig(context);
        return config.isBetaFeaturesEnabled() && config.isParkingAutoEnabled();
    }

    /** Pillar scan, also beta only. */
    public static boolean creepEnabled(Context context) {
        AppConfig config = new AppConfig(context);
        return config.isBetaFeaturesEnabled() && config.isParkingCreepEnabled();
    }

    private void update() {
        boolean want = started && app != null && (autoEnabled(app) || creepEnabled(app));
        if (want) {
            evaluateCreep(Telemetry.get().readings());
        } else {
            setCreeping(false);
        }
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
        AppLog.d(TAG, "watching gear " + (want ? "on" : "off"));
    }

    @Override
    public void onReadingsChanged(Readings readings) {
        if (!watching) {
            return;
        }
        evaluateCreep(readings);
        String gear = readings.text(Signal.GEAR);
        String before = lastGear;
        lastGear = gear;
        if (gear == null || gear.equals(before)) {
            return;
        }
        main.removeCallbacks(autoCapture);
        if ("P".equals(before) && ("D".equals(gear) || "R".equals(gear))) {
            // Driving off: what was seen belongs to the last spot
            forget();
        }
        if ("P".equals(gear) && ("D".equals(before) || "R".equals(before) || "N".equals(before))
                && (autoEnabled(app) || hasMemory())) {
            main.postDelayed(autoCapture, SETTLE_MS);
        }
    }

    // ---------------- Pillar scan ----------------

    private void evaluateCreep(Readings readings) {
        boolean want = false;
        if (started && app != null && creepEnabled(app) && readings != null) {
            String gear = readings.text(Signal.GEAR);
            Float speed = readings.number(Signal.SPEED);
            want = ("D".equals(gear) || "R".equals(gear)) && speed != null && speed >= 0f && speed < CREEP_KMH;
        }
        setCreeping(want);
    }

    private void setCreeping(boolean want) {
        if (want == creeping) {
            return;
        }
        creeping = want;
        main.removeCallbacks(creepScan);
        if (want) {
            main.post(creepScan);
        }
        AppLog.d(TAG, "pillar scan " + (want ? "on" : "off"));
    }

    /** Main thread. One read, then the next one {@link #SCAN_MS} after it finished. */
    private void scan() {
        Context ctx = app;
        if (!creeping || ctx == null) {
            return;
        }
        MainActivity activity = MainActivity.getInstance();
        if (activity == null || !busy.compareAndSet(false, true)) {
            scheduleScan();
            return;
        }
        activity.grabParkingLanes(channels -> {
            if (channels == null) {
                busy.set(false);
                scheduleScan();
                return;
            }
            worker.execute(() -> {
                try {
                    remember(ctx, readLanes(channels));
                } catch (RuntimeException e) {
                    AppLog.w(TAG, "pillar scan failed: " + e.getMessage());
                } finally {
                    recycle(channels);
                    busy.set(false);
                    scheduleScan();
                }
            });
        });
    }

    /** What a scan from the scan screen read. */
    public static final class ScanResult {
        /** False when the surround camera is not up (nothing else is set). */
        public final boolean ok;
        /** This read alone. */
        public final ParkingText.Spot spot;
        /** What the pillar scan remembers now, this read included. */
        public final ParkingText.Spot remembered;
        public final List<String> lines;
        /** "front", "left", "right" or "" when no lane read anything. */
        public final String bestLane;
        /** The best lane at 640 x 360, or null. The receiver owns it. */
        public final Bitmap picture;
        public final long at;

        ScanResult(boolean ok, ParkingText.Spot spot, ParkingText.Spot remembered, List<String> lines,
                String bestLane, Bitmap picture, long at) {
            this.ok = ok;
            this.spot = spot;
            this.remembered = remembered;
            this.lines = lines;
            this.bestLane = bestLane;
            this.picture = picture;
            this.at = at;
        }
    }

    /**
     * Scan screen "Scan now": one pillar scan whatever the gear and speed. What it sees goes into
     * the memory like a creeping scan (and onto the HUD when new) and, with the gaps filled from
     * memory, to 7xDash web; {@code done} gets the result on the main thread.
     */
    public void scanNow(Context context, Consumer<ScanResult> done) {
        if (app == null) {
            app = context.getApplicationContext();
        }
        main.post(() -> scanNow(done, 0));
    }

    private void scanNow(Consumer<ScanResult> done, int tries) {
        Context ctx = app;
        MainActivity activity = MainActivity.getInstance();
        long now = System.currentTimeMillis();
        if (activity == null) {
            done.accept(new ScanResult(false, null, remembered(), new ArrayList<>(), "", null, now));
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            if (tries < BUSY_RETRIES) {
                main.postDelayed(() -> scanNow(done, tries + 1), BUSY_RETRY_MS);
            } else {
                done.accept(new ScanResult(false, null, remembered(), new ArrayList<>(), "", null, now));
            }
            return;
        }
        activity.grabParkingLanes(channels -> {
            if (channels == null) {
                busy.set(false);
                main.post(() -> done.accept(new ScanResult(false, null, remembered(), new ArrayList<>(),
                        "", null, System.currentTimeMillis())));
                return;
            }
            worker.execute(() -> {
                ScanResult result;
                try {
                    Reading reading = readLanes(channels);
                    remember(ctx, reading);
                    long at = System.currentTimeMillis();
                    // Scanned by hand: the web shows it as where the car is, gaps from memory
                    sendToWeb(reading, withMemory(reading.spot), at);
                    result = new ScanResult(true, reading.spot, remembered(), reading.lines,
                            reading.best >= 0 ? NAMES[reading.best] : "", reading.picture, at);
                } catch (RuntimeException e) {
                    AppLog.w(TAG, "manual scan failed: " + e.getMessage());
                    result = new ScanResult(false, null, remembered(), new ArrayList<>(), "", null,
                            System.currentTimeMillis());
                } finally {
                    recycle(channels);
                    busy.set(false);
                }
                ScanResult out = result;
                main.post(() -> done.accept(out));
            });
        });
    }

    /** What the pillar scan remembers now. Any thread. */
    public ParkingText.Spot remembered() {
        synchronized (memoryLock) {
            expire(System.currentTimeMillis());
            return ParkingText.spot(seenFloor, seenBay);
        }
    }

    /** Any thread. Exactly one scan queued, so a quick off-on never runs two loops. */
    private void scheduleScan() {
        main.post(() -> {
            main.removeCallbacks(creepScan);
            if (creeping) {
                main.postDelayed(creepScan, SCAN_MS);
            }
        });
    }

    /** Worker thread. Keeps the newest floor and bay; the HUD shows when either is new. */
    private void remember(Context ctx, Reading reading) {
        long now = System.currentTimeMillis();
        boolean changed = false;
        ParkingText.Spot seen;
        synchronized (memoryLock) {
            expire(now);
            if (!reading.spot.floor.isEmpty()) {
                changed |= !reading.spot.floor.equals(seenFloor);
                seenFloor = reading.spot.floor;
                seenFloorAt = now;
            }
            if (!reading.spot.bay.isEmpty()) {
                changed |= !reading.spot.bay.equals(seenBay);
                seenBay = reading.spot.bay;
                seenBayAt = now;
            }
            seen = ParkingText.spot(seenFloor, seenBay);
        }
        if (changed) {
            AppLog.d(TAG, "pillar scan saw floor=" + seen.floor + " bay=" + seen.bay);
            ParkingNote.seen(ctx, seen, now);
        }
    }

    /** Holding {@code memoryLock}. */
    private void expire(long now) {
        if (now - seenFloorAt > MEMORY_MS) {
            seenFloor = "";
        }
        if (now - seenBayAt > MEMORY_MS) {
            seenBay = "";
        }
    }

    private boolean hasMemory() {
        synchronized (memoryLock) {
            expire(System.currentTimeMillis());
            return !seenFloor.isEmpty() || !seenBay.isEmpty();
        }
    }

    private void forget() {
        synchronized (memoryLock) {
            seenFloor = "";
            seenBay = "";
            seenFloorAt = 0L;
            seenBayAt = 0L;
        }
    }

    /** The parked read, with the gaps filled from what the pillar scan saw. */
    private ParkingText.Spot withMemory(ParkingText.Spot spot) {
        synchronized (memoryLock) {
            expire(System.currentTimeMillis());
            return ParkingText.spot(spot.floor.isEmpty() ? seenFloor : spot.floor,
                    spot.bay.isEmpty() ? seenBay : spot.bay);
        }
    }

    /** Shortcut "Where did I park". */
    public void captureNow(Context context) {
        if (app == null) {
            app = context.getApplicationContext();
        }
        main.post(() -> capture(true, 0));
    }

    private void capture(boolean manual, int tries) {
        Context ctx = app;
        if (ctx == null) {
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            // A pillar scan is reading: go right after it
            if (tries < BUSY_RETRIES) {
                main.postDelayed(() -> capture(manual, tries + 1), BUSY_RETRY_MS);
            }
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
                    recycle(channels);
                    busy.set(false);
                }
            });
        });
    }

    private static void recycle(Bitmap[] channels) {
        for (Bitmap b : channels) {
            if (b != null) {
                b.recycle();
            }
        }
    }

    /** One pass of OCR over the lanes. */
    private static final class Reading {
        ParkingText.Spot spot;
        List<String> lines;
        String[] photos;
        /** Index into {@link #LANES} of the lane that read best, or -1. */
        int best;
        /** The best lane at {@link #PHOTO_W} x {@link #PHOTO_H}, or null. */
        Bitmap picture;
    }

    /** Worker thread. */
    private Reading readLanes(Bitmap[] channels) {
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
        Reading reading = new Reading();
        reading.spot = ParkingText.pick(all);
        reading.lines = all;
        reading.photos = photos;
        reading.best = best;
        if (best >= 0) {
            reading.picture = Bitmap.createScaledBitmap(channels[LANES[best]], PHOTO_W, PHOTO_H, true);
        }
        return reading;
    }

    /** Worker thread. */
    private void read(Context ctx, Bitmap[] channels) {
        Reading reading = readLanes(channels);
        List<String> all = reading.lines;
        String[] photos = reading.photos;
        int best = reading.best;
        ParkingText.Spot spot = withMemory(reading.spot);
        long at = System.currentTimeMillis();
        AppLog.d(TAG, "read " + all.size() + " line(s) -> floor=" + reading.spot.floor + " bay=" + reading.spot.bay
                + ", with pillar scan floor=" + spot.floor + " bay=" + spot.bay);

        ParkingNote.show(ctx, spot, at, reading.picture);
        sendToWeb(reading, spot, at);
    }

    /** Worker thread. The "Where I parked" card on 7xDash web (newest wins there). */
    private void sendToWeb(Reading reading, ParkingText.Spot spot, long at) {
        List<String> all = reading.lines;
        String[] photos = reading.photos;
        int best = reading.best;
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
