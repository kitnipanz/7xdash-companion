package com.kooo.evcam.telemetry;

import android.content.Context;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.BuildConfig;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * A plain text log of blind-spot signals for one session of {@link BlindSpotSniffer}, to send to
 * the phone and read later: first every car setting with its value, then each change of the
 * blind-spot-looking ones, and of gear, speed bands and turn signals, with the time.
 *
 * <p>Files live in the app's own folder ({@code files/bsd-log}); the newest {@link #KEEP} are kept.
 * Calls come from the sniffer's own thread.</p>
 */
public final class BlindSpotLog {

    private static final String TAG = "BlindSpotLog";
    private static final String DIR = "bsd-log";
    static final int KEEP = 10;

    /** The open session, so {@link SettingZeekrWatch} can write into the same file; null when none. */
    private static volatile BlindSpotLog current;

    private final File file;
    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    /** The open session, or null. */
    static BlindSpotLog current() {
        return current;
    }

    /** Makes {@code log} the open session (null closes it). */
    static void setCurrent(BlindSpotLog log) {
        current = log;
    }

    private BlindSpotLog(File file) {
        this.file = file;
    }

    /** Starts a new file and writes its header. Null when the folder cannot be made. */
    static BlindSpotLog start(Context context) {
        File dir = new File(context.getFilesDir(), DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            AppLog.w(TAG, "cannot make " + dir);
            return null;
        }
        prune(dir);
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        BlindSpotLog log = new BlindSpotLog(new File(dir, "bsd-log-" + stamp + ".txt"));
        log.raw("7xDash " + BuildConfig.VERSION_NAME + " blind-spot signal log, started "
                + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        log.raw("Lines: time  NAME 0xID: before -> now  | gear, km/h, turn signal (L/R/-), hazard");
        return log;
    }

    /** The newest log file, or null when there is none. */
    public static File latest(Context context) {
        File[] files = list(new File(context.getFilesDir(), DIR));
        return files.length == 0 ? null : files[files.length - 1];
    }

    /** A section heading. */
    void section(String title) {
        raw("");
        raw("== " + title + " ==");
    }

    /** One line with the time in front. */
    synchronized void line(String text) {
        raw(time.format(new Date()) + "  " + text);
    }

    /** One line as it is. Two threads write (sniffer, settings service), so one at a time. */
    synchronized void raw(String text) {
        try (FileWriter w = new FileWriter(file, true)) {
            w.write(text);
            w.write('\n');
        } catch (IOException e) {
            AppLog.w(TAG, "write failed: " + e.getMessage());
        }
    }

    /** "D, 42 km/h, turn L, hazard off" from the latest telemetry. Pure enough to test with a state. */
    public static String context(VehicleState s) {
        if (s == null) {
            return "?";
        }
        String turn = s.turnSignal == null ? "?" : s.turnSignal == VehicleState.TURN_LEFT ? "L"
                : s.turnSignal == VehicleState.TURN_RIGHT ? "R" : s.turnSignal == 0 ? "-" : "LR";
        String speed = s.speedKmh == null ? "?" : String.valueOf(Math.round(s.speedKmh));
        return (s.gear == null ? "?" : s.gear) + ", " + speed + " km/h, turn " + turn
                + ", hazard " + (s.hazard == null ? "?" : s.hazard ? "on" : "off");
    }

    private static File[] list(File dir) {
        File[] files = dir.listFiles((d, name) -> name.startsWith("bsd-log-") && name.endsWith(".txt"));
        if (files == null) {
            return new File[0];
        }
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        return files;
    }

    /** Keeps the newest {@link #KEEP} - 1, so with the new one there are {@link #KEEP}. */
    private static void prune(File dir) {
        File[] files = list(dir);
        for (int i = 0; i < files.length - (KEEP - 1); i++) {
            if (!files[i].delete()) {
                AppLog.w(TAG, "could not delete " + files[i].getName());
            }
        }
    }
}
