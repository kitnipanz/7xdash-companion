package com.kooo.evcam.telemetry;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.BuildConfig;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.screen.ScreenState;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Experimental: writes every signal the car interface offers to the USB drive, to find out what
 * the car really sends (blind-spot, cross traffic and anything else). Off by default
 * ({@link AppConfig#isSignalRecorderEnabled}).
 *
 * <p>While the screen is on and a USB drive is in, each session writes one tab-separated text file
 * to {@code <USB>/7xDash/signals/}:</p>
 * <ol>
 *   <li>every id the ECARX classes name, read three ways (car function, sensor event, sensor
 *   value), so nothing is missed even when the kind is not known;</li>
 *   <li>then every change, read the way that answered, with the time, gear, speed, turn signal
 *   and hazard.</li>
 * </ol>
 * <p>All ids are read in one pass every {@link #MIN_PASS_MS} or slower: the pass waits twice as
 * long as the last one took, so the car interface is never kept busy.</p>
 * <p>The blind-spot candidates ({@link #pushedCandidate}) are also watched by callback: some values
 * are only pushed by the car and read 255 when asked. Each push is written at once, with its zone
 * and the reading "callback".</p>
 */
public final class SignalRecorder {

    private static final String TAG = "SignalRecorder";
    private static final String DIR = "7xDash/signals";
    static final long MIN_PASS_MS = 1_000L;
    private static final long RETRY_MS = 10_000L;
    /** Names watched by callback as well as read: blind spot, cross traffic, door open, lane change, turn signal. */
    private static final Pattern PUSHED = Pattern.compile(
            "BLIND|RCTA|RCTB|CROSS_TRAFFIC|DOOR_OPEN_WARN|LANE_CHANGE|REAR_COLLISION|INDCR|(^|_)(DOW|LCA|BSD|BSW)(_|$)");
    /** A file stops growing past this; a new session starts a new file. */
    private static final long MAX_BYTES = 200L * 1024 * 1024;

    private static SignalRecorder installed;

    private final Context app;
    private final AppConfig config;
    private final HandlerThread thread = new HandlerThread("signal-recorder");
    private final Handler work;
    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    // Worker thread only
    private boolean running;
    private BufferedWriter out;
    private volatile File file;
    private long written;
    private CarAmbientLab.Reader reader;
    private List<CarAmbientLab.Constant> ids;
    /** For each id, the way it answered at the start; -1 = no way did. */
    private int[] way;
    private String[] last;
    private String lastSituation;
    private long changes;
    /** Callback: id to name, and the last value pushed per id and zone. */
    private Map<Integer, String> pushedNames;
    private final Map<Long, String> pushedLast = new HashMap<>();

    private final Runnable pass = this::pass;

    private SignalRecorder(Context context) {
        app = context.getApplicationContext();
        config = new AppConfig(app);
        thread.start();
        work = new Handler(thread.getLooper());
    }

    /** Application start. Repeat calls do nothing. */
    public static void install(Context context) {
        if (installed != null) {
            return;
        }
        installed = new SignalRecorder(context);
        ScreenState.addListener(new ScreenState.Listener() {
            @Override
            public void onScreenOff() {
                applyConfig();
            }

            @Override
            public void onScreenOn() {
                applyConfig();
            }
        });
        applyConfig();
    }

    /** The switch or the screen changed: start or stop. */
    public static void applyConfig() {
        SignalRecorder r = installed;
        if (r != null) {
            r.work.post(r::reconsider);
        }
    }

    /** The file being written now, or null. For the Settings summary. */
    public static File current() {
        SignalRecorder r = installed;
        return r == null ? null : r.file;
    }

    private void reconsider() {
        boolean want = config.isSignalRecorderEnabled() && !ScreenState.dark();
        if (want == running) {
            return;
        }
        running = want;
        work.removeCallbacks(pass);
        if (want) {
            work.post(pass);
        } else {
            stop("stopped");
        }
    }

    private void pass() {
        if (!running) {
            return;
        }
        if (out == null && !begin()) {
            work.postDelayed(pass, RETRY_MS);
            return;
        }
        long started = SystemClock.uptimeMillis();
        VehicleState s = Telemetry.get().latest();
        String situation = BlindSpotLog.context(s);
        try {
            for (int i = 0; i < ids.size(); i++) {
                if (way[i] < 0) {
                    continue;
                }
                CarAmbientLab.Constant k = ids.get(i);
                String now = reader.read(way[i], k.value);
                if (!Objects.equals(now, last[i])) {
                    write(time.format(new Date()) + "\t" + k.name + "\t" + k.hex() + "\t"
                            + CarAmbientLab.Reader.name(way[i]) + "\t" + last[i] + "\t" + now + "\t| " + situation);
                    last[i] = now;
                    changes++;
                }
            }
            String brief = s == null ? "?" : s.gear + "/" + s.turnSignal + "/" + s.hazard;
            if (!brief.equals(lastSituation)) {
                write(time.format(new Date()) + "\t(car)\t\t\t\t\t| " + situation);
                lastSituation = brief;
            }
            out.flush();
        } catch (IOException e) {
            AppLog.w(TAG, "write failed, USB drive gone? " + e.getMessage());
            close();
            work.postDelayed(pass, RETRY_MS);
            return;
        }
        if (written > MAX_BYTES) {
            stop("file reached " + (MAX_BYTES >> 20) + " MB");
            running = false;
            return;
        }
        long took = SystemClock.uptimeMillis() - started;
        work.postDelayed(pass, Math.max(MIN_PASS_MS, 2 * took));
    }

    /** Opens the file on the USB drive and writes the full dump. False when not possible yet. */
    private boolean begin() {
        File root = StorageHelper.getExternalSdCardRoot(app);
        if (root == null || !root.isDirectory()) {
            return false;
        }
        reader = CarAmbientLab.Reader.open(app);
        if (reader == null) {
            return false;
        }
        CarAmbientLab.Scan scan = CarAmbientLab.scan(app);
        if (!scan.carReady || scan.functions.isEmpty()) {
            return false;
        }
        File dir = new File(root, DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            AppLog.w(TAG, "cannot make " + dir);
            return false;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        file = new File(dir, "signals-" + stamp + ".txt");
        try {
            out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
            written = 0;
            ids = new ArrayList<>(scan.functions);
            way = new int[ids.size()];
            last = new String[ids.size()];
            changes = 0;
            lastSituation = null;
            long started = SystemClock.uptimeMillis();
            write("7xDash " + BuildConfig.VERSION_NAME + " car signal recording, started "
                    + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
            write("Car interface: " + scan.status);
            write("");
            write("== All signals (" + ids.size() + ") ==");
            write("name\tid\tclass\tfunction\tsensor-event\tsensor-value\treading");
            int readable = 0;
            for (int i = 0; i < ids.size(); i++) {
                CarAmbientLab.Constant k = ids.get(i);
                String[] v = new String[3];
                way[i] = -1;
                for (int w = 0; w < 3; w++) {
                    v[w] = reader.read(w, k.value);
                    if (way[i] < 0 && usable(v[w])) {
                        way[i] = w;
                    }
                }
                if (way[i] >= 0) {
                    last[i] = v[way[i]];
                    readable++;
                }
                write(k.name + "\t" + k.hex() + "\t" + k.owner + "\t" + v[0] + "\t" + v[1] + "\t" + v[2]
                        + "\t" + CarAmbientLab.Reader.name(way[i]));
            }
            write("");
            write("== Changes ==  (" + readable + " of " + ids.size() + " readable; first pass took "
                    + (SystemClock.uptimeMillis() - started) + " ms)");
            write("time\tname\tid\treading\tbefore\tnow\t| gear, km/h, turn, hazard");
            write(time.format(new Date()) + "\t(start)\t\t\t\t\t| " + BlindSpotLog.context(Telemetry.get().latest()));
            watchCandidates();
            out.flush();
        } catch (IOException e) {
            AppLog.w(TAG, "cannot write " + file + ": " + e.getMessage());
            close();
            return false;
        }
        AppLog.i(TAG, "recording " + ids.size() + " signals to " + file);
        BlackBox.note("All car signals: recording to " + file.getName());
        return true;
    }

    /** True for a name worth watching by callback too. */
    static boolean pushedCandidate(String name) {
        return name != null && PUSHED.matcher(name).find();
    }

    /** Registers the callback for the candidates and writes what the car said. Worker thread, file open. */
    private void watchCandidates() throws IOException {
        pushedNames = new LinkedHashMap<>();
        pushedLast.clear();
        for (CarAmbientLab.Constant k : ids) {
            if (pushedCandidate(k.name) && !pushedNames.containsKey(k.value)) {
                pushedNames.put(k.value, k.name);
            }
        }
        int[] list = new int[pushedNames.size()];
        int i = 0;
        for (Integer id : pushedNames.keySet()) {
            list[i++] = id;
        }
        String status = list.length == 0 ? "no candidates" : reader.watch(list, new CarAmbientLab.Reader.Pushed() {
            @Override
            public void value(int id, int zone, Object value) {
                String at = time.format(new Date());
                String now = String.valueOf(value);
                work.post(() -> pushed(at, id, zone, now));
            }

            @Override
            public void other(String method, Object[] args) {
                String at = time.format(new Date());
                String text = method + " " + Arrays.toString(args);
                work.post(() -> pushedOther(at, text));
            }
        });
        write(time.format(new Date()) + "\t(callback)\t\t\t\t\t| " + status + ": "
                + String.join(", ", pushedNames.values()));
        AppLog.i(TAG, "callback " + status);
    }

    private void pushed(String at, int id, int zone, String now) {
        if (out == null) {
            return;
        }
        long key = ((long) id << 32) | (zone & 0xFFFFFFFFL);
        String before = pushedLast.put(key, now);
        String name = pushedNames == null ? null : pushedNames.get(id);
        try {
            write(at + "\t" + (name == null ? "?" : name) + "\t" + String.format(Locale.US, "0x%08X", id)
                    + "\tcallback zone " + zone + "\t" + before + "\t" + now + "\t| "
                    + BlindSpotLog.context(Telemetry.get().latest()));
            changes++;
            out.flush();
        } catch (IOException e) {
            AppLog.w(TAG, "write failed, USB drive gone? " + e.getMessage());
        }
    }

    private void pushedOther(String at, String text) {
        if (out == null) {
            return;
        }
        try {
            write(at + "\t(callback)\t\t\t\t\t| " + text);
            out.flush();
        } catch (IOException e) {
            AppLog.w(TAG, "write failed, USB drive gone? " + e.getMessage());
        }
    }

    /** What {@link #snapshot} did, for the Settings message. */
    public interface Done {
        void done(boolean ok, String message);
    }

    /**
     * One file with every signal the car offers right now, parked or driving:
     * {@code <USB>/7xDash/signals/snapshot-<time>.txt}. Each id read three ways, with whether the car
     * says the function is supported, then 7xDash's own decoded readings. {@code done} is called on
     * the main thread.
     */
    public static void snapshot(Context context, Done done) {
        install(context);
        SignalRecorder r = installed;
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        r.work.post(() -> {
            String[] result = r.writeSnapshot();
            main.post(() -> done.done(result[0] != null, result[0] != null ? result[0] : result[1]));
        });
    }

    /** {saved path, null} or {null, why not}. Worker thread. */
    private String[] writeSnapshot() {
        File root = StorageHelper.getExternalSdCardRoot(app);
        if (root == null || !root.isDirectory()) {
            return new String[]{null, app.getString(com.kooo.evcam.R.string.signal_snapshot_no_usb)};
        }
        CarAmbientLab.Reader r = CarAmbientLab.Reader.open(app);
        CarAmbientLab.Scan scan = CarAmbientLab.scan(app);
        if (r == null || !scan.carReady || scan.functions.isEmpty()) {
            return new String[]{null, app.getString(com.kooo.evcam.R.string.signal_snapshot_no_car)};
        }
        File dir = new File(root, DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return new String[]{null, app.getString(com.kooo.evcam.R.string.signal_snapshot_no_write)};
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File target = new File(dir, "snapshot-" + stamp + ".txt");
        long started = SystemClock.uptimeMillis();
        int readable = 0;
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8))) {
            VehicleState s = Telemetry.get().latest();
            w.write("7xDash " + BuildConfig.VERSION_NAME + " car signal snapshot, "
                    + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + "\n");
            w.write("Car interface: " + scan.status + "\n");
            w.write("Car now: " + BlindSpotLog.context(s) + "\n\n");
            w.write("== All signals (" + scan.functions.size() + ") ==\n");
            w.write("name\tid\tclass\tsupported\tfunction\tsensor-event\tsensor-value\treading\n");
            for (CarAmbientLab.Constant k : scan.functions) {
                String[] v = new String[3];
                int way = -1;
                for (int i = 0; i < 3; i++) {
                    v[i] = r.read(i, k.value);
                    if (way < 0 && usable(v[i])) {
                        way = i;
                    }
                }
                if (way >= 0) {
                    readable++;
                }
                w.write(k.name + "\t" + k.hex() + "\t" + k.owner + "\t" + CarAmbientLab.support(app, k.value)
                        + "\t" + v[0] + "\t" + v[1] + "\t" + v[2] + "\t" + CarAmbientLab.Reader.name(way) + "\n");
            }
            w.write("\n== 7xDash readings ==\n");
            w.write(Telemetry.get().readings().describe().replace(' ', '\n') + "\n");
            w.write("\n== Done: " + readable + " of " + scan.functions.size() + " readable, took "
                    + (SystemClock.uptimeMillis() - started) + " ms ==\n");
        } catch (IOException e) {
            AppLog.w(TAG, "snapshot failed: " + e.getMessage());
            return new String[]{null, app.getString(com.kooo.evcam.R.string.signal_snapshot_no_write)};
        }
        AppLog.i(TAG, "snapshot of " + scan.functions.size() + " signals to " + target);
        BlackBox.note("All car signals: snapshot " + target.getName());
        return new String[]{app.getString(com.kooo.evcam.R.string.signal_snapshot_saved_detail,
                DIR + "/" + target.getName(), readable, scan.functions.size()), null};
    }

    /** A reading worth following: the call worked and gave something. */
    static boolean usable(String value) {
        return value != null && !value.equals("null") && !value.startsWith("error");
    }

    private void write(String line) throws IOException {
        out.write(line);
        out.write('\n');
        written += line.length() + 1;
    }

    private void stop(String why) {
        if (out != null) {
            try {
                write("");
                write("== Stopped: " + why + " (" + changes + " changes) ==");
                out.flush();
            } catch (IOException ignored) {
                // drive gone: nothing more to write
            }
            AppLog.i(TAG, "stopped: " + why);
        }
        close();
    }

    private void close() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
            }
        }
        out = null;
        file = null;
        if (reader != null) {
            reader.unwatch();
        }
        reader = null;
    }
}
