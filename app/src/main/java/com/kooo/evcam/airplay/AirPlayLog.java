package com.kooo.evcam.airplay;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.StorageHelper;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * AirPlay's own log, written to the USB drive next to the other logs
 * ({@code EVCam_Log/airplay-yyyyMMdd.log}; internal storage when no drive is in). Every line
 * also goes to AppLog. Each line is written at once: if the native receiver takes the process
 * down, the last line on the drive is the last thing that worked.
 *
 * <p>Holds the Java side (start, stop, connections, video, audio), every message from the
 * native receiver (UxPlay at INFO level), the network interfaces at start, and how the previous
 * run ended (Android 11+).</p>
 */
public final class AirPlayLog {

    private static final String TAG = "AirPlay";
    private static final long MAX_BYTES = 4L * 1024 * 1024;
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyyMMdd", Locale.US);
    private static Context app;

    private AirPlayLog() {
    }

    public static void init(Context context) {
        synchronized (LOCK) {
            app = context.getApplicationContext();
        }
    }

    public static void i(String message) {
        AppLog.i(TAG, message);
        write("I", message);
    }

    public static void w(String message) {
        AppLog.w(TAG, message);
        write("W", message);
    }

    /** From the native receiver; already in logcat, so not repeated into AppLog's buffer twice. */
    static void nativeLine(String message) {
        AppLog.d(TAG + "Native", message);
        write("N", message);
    }

    private static void write(String level, String message) {
        synchronized (LOCK) {
            if (app == null) {
                return;
            }
            Date now = new Date();
            File dir = StorageHelper.getLogDir(app, true);
            if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) {
                return;
            }
            String day = DAY.format(now);
            File file = new File(dir, "airplay-" + day + ".log");
            if (file.length() > MAX_BYTES) {
                File old = new File(dir, "airplay-" + day + ".1.log");
                //noinspection ResultOfMethodCallIgnored
                old.delete();
                //noinspection ResultOfMethodCallIgnored
                file.renameTo(old);
            }
            try (FileWriter out = new FileWriter(file, true)) {
                out.write(TIME.format(now) + " " + level + " "
                        + Thread.currentThread().getName() + ": " + message + "\n");
            } catch (IOException ignored) {
                // log must never take AirPlay down
            }
        }
    }

    /** Where the log is going right now, for the AirPlay log's first line. */
    static String where() {
        Context context = app;
        File dir = context == null ? null : StorageHelper.getLogDir(context, true);
        return dir == null ? "nowhere" : dir.getAbsolutePath();
    }

    /** Every interface that is up, with its addresses: tells which network the iPhone must be on. */
    static void networks() {
        try {
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp() || iface.isLoopback()) {
                    continue;
                }
                StringBuilder sb = new StringBuilder();
                for (InetAddress a : Collections.list(iface.getInetAddresses())) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(a.getHostAddress());
                }
                i("network " + iface.getName() + " multicast=" + iface.supportsMulticast()
                        + " [" + sb + "]");
            }
        } catch (Exception e) {
            w("could not list networks: " + e);
        }
    }

    /** How this app's last runs ended (crash, native crash, killed…), Android 11 and up. */
    static void lastExits(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        try {
            ActivityManager am = context.getSystemService(ActivityManager.class);
            List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(null, 0, 3);
            for (ApplicationExitInfo e : exits) {
                i("earlier exit: " + TIME.format(new Date(e.getTimestamp()))
                        + " process=" + e.getProcessName()
                        + " reason=" + reason(e.getReason())
                        + (e.getDescription() != null ? " (" + e.getDescription() + ")" : ""));
            }
        } catch (RuntimeException e) {
            w("could not read earlier exits: " + e);
        }
    }

    private static String reason(int r) {
        switch (r) {
            case ApplicationExitInfo.REASON_CRASH: return "crash";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "native-crash";
            case ApplicationExitInfo.REASON_ANR: return "anr";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "low-memory";
            case ApplicationExitInfo.REASON_SIGNALED: return "signaled";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "user-stopped";
            case ApplicationExitInfo.REASON_EXIT_SELF: return "exit-self";
            case ApplicationExitInfo.REASON_OTHER: return "other";
            default: return String.valueOf(r);
        }
    }
}
