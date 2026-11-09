package com.kooo.evcam.telemetry;

import android.content.Context;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.BuildConfig;

import android.os.SystemClock;

import com.kooo.evcam.StorageHelper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * A plain text log of blind-spot signals for one session of {@link BlindSpotSniffer}, to send to
 * the phone and read later: first every car setting with its value, then each change of the
 * blind-spot-looking ones, and of gear, speed bands and turn signals, with the time.
 *
 * <p>Files live in the app's own folder ({@code files/bsd-log}, the newest {@link #KEEP} kept, sent
 * to the phone from Settings) and, when a USB drive is in, the same file in
 * {@code <USB>/7xDash/bsd-log/} (never pruned). A drive put in later is picked up within
 * {@link #USB_RETRY_MS} and gets everything written so far. Both files stay open and are flushed
 * at most every {@link #FLUSH_MS}, so a slow drive does not hold up the loggers.</p>
 */
public final class BlindSpotLog {

    private static final String TAG = "BlindSpotLog";
    private static final String DIR = "bsd-log";
    private static final String USB_DIR = "7xDash/bsd-log";
    static final int KEEP = 10;
    static final long FLUSH_MS = 1_000L;
    static final long USB_RETRY_MS = 30_000L;

    /** The open session, so {@link SettingZeekrWatch} can write into the same file; null when none. */
    private static volatile BlindSpotLog current;

    private final File file;
    private final Context app;
    private Writer out;
    private Writer usb;
    private File usbFile;
    private long usbTriedAt = -USB_RETRY_MS;
    private long flushedAt;
    private boolean closed;
    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    /** The open session, or null. */
    static BlindSpotLog current() {
        return current;
    }

    /** Makes {@code log} the open session (null closes it). */
    static void setCurrent(BlindSpotLog log) {
        current = log;
    }

    private BlindSpotLog(Context context, File file) {
        this.app = context.getApplicationContext();
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
        BlindSpotLog log = new BlindSpotLog(context, new File(dir, "bsd-log-" + stamp + ".txt"));
        log.raw("7xDash " + BuildConfig.VERSION_NAME + " blind-spot signal log, started "
                + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        log.raw("Lines: time  NAME 0xID: before -> now  | gear, km/h, turn signal (L/R/-), hazard");
        return log;
    }

    /** The newest log file, or null when there is none. Flushes the open one first, so it is complete. */
    public static File latest(Context context) {
        BlindSpotLog open = current;
        if (open != null) {
            synchronized (open) {
                open.flush();
            }
        }
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
        if (closed) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        try {
            if (out == null) {
                out = open(file, true);
            }
            out.write(text);
            out.write('\n');
        } catch (IOException e) {
            AppLog.w(TAG, "write failed: " + e.getMessage());
        }
        if (usb == null && now - usbTriedAt >= USB_RETRY_MS) {
            usbTriedAt = now;
            openUsb();
        }
        if (usb != null) {
            try {
                usb.write(text);
                usb.write('\n');
            } catch (IOException e) {
                AppLog.w(TAG, "USB write failed, drive gone? " + e.getMessage());
                closeQuietly(usb);
                usb = null;
            }
        }
        if (now - flushedAt >= FLUSH_MS) {
            flushedAt = now;
            flush();
        }
    }

    /** The USB copy, when a drive is in: everything so far first, then it follows. */
    private void openUsb() {
        File root = StorageHelper.getExternalSdCardRoot(app);
        if (root == null || !root.isDirectory()) {
            return;
        }
        File dir = new File(root, USB_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            AppLog.w(TAG, "cannot make " + dir);
            return;
        }
        File target = new File(dir, file.getName());
        try {
            if (out != null) {
                out.flush();
            }
            OutputStream copy = new FileOutputStream(target, false);
            if (file.isFile()) {
                try (InputStream in = new FileInputStream(file)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        copy.write(buf, 0, n);
                    }
                }
            }
            usb = new BufferedWriter(new OutputStreamWriter(copy, StandardCharsets.UTF_8), 64 * 1024);
            usbFile = target;
            AppLog.i(TAG, "also writing " + target);
            com.kooo.evcam.blackbox.BlackBox.note("Blind-spot log on USB: " + target);
        } catch (IOException e) {
            AppLog.w(TAG, "USB log failed: " + e.getMessage());
        }
    }

    /** The USB copy's path, or null when there is none. */
    synchronized File usbFile() {
        return usb == null ? null : usbFile;
    }

    private void flush() {
        try {
            if (out != null) {
                out.flush();
            }
        } catch (IOException e) {
            AppLog.w(TAG, "flush failed: " + e.getMessage());
        }
        if (usb != null) {
            try {
                usb.flush();
            } catch (IOException e) {
                AppLog.w(TAG, "USB flush failed: " + e.getMessage());
                closeQuietly(usb);
                usb = null;
            }
        }
    }

    /** End of the session: everything out and both files closed. */
    synchronized void close() {
        if (closed) {
            return;
        }
        flush();
        closeQuietly(out);
        closeQuietly(usb);
        out = null;
        usb = null;
        closed = true;
    }

    private static Writer open(File f, boolean append) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f, append), StandardCharsets.UTF_8), 16 * 1024);
    }

    private static void closeQuietly(Writer w) {
        if (w == null) {
            return;
        }
        try {
            w.close();
        } catch (IOException ignored) {
            // nothing more to do
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
