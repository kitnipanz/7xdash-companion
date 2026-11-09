package com.kooo.evcam.blackbox;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import com.kooo.evcam.AppLog;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Finds out what freezes the screen. A background thread pings the main thread every
 * {@link #PING_MS}; when a ping waits longer than {@link #STALL_MS}, the main thread's stack goes
 * to the black box (what it is stuck in), again every {@link #REPEAT_MS} while it stays stuck, and
 * one line with how long it lasted once it moves again.
 *
 * <p>At start it also writes the last few exits the system recorded for this app
 * ({@code getHistoricalProcessExitReasons}, Android 11+), with the start of the ANR trace when the
 * system killed it for not responding.</p>
 */
public final class MainThreadWatch {

    private static final String TAG = "MainThreadWatch";
    static final long PING_MS = 500L;
    static final long STALL_MS = 2_000L;
    static final long REPEAT_MS = 5_000L;
    private static final int FRAMES = 40;
    private static final int TRACE_LINES = 120;

    private static MainThreadWatch installed;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("main-thread-watch");
    private final Handler watch;
    /** When the last ping was posted and whether the main thread has answered it. Watch thread reads, main writes. */
    private volatile long sentAt;
    private volatile boolean answered = true;
    private long stallSince = -1L;
    private long lastDump;

    private final Runnable pong = () -> answered = true;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            if (answered) {
                if (stallSince >= 0) {
                    BlackBox.noteImportant("Main thread moving again after " + (now - stallSince) + " ms");
                    stallSince = -1L;
                }
                answered = false;
                sentAt = now;
                main.post(pong);
            } else if (now - sentAt >= STALL_MS) {
                if (stallSince < 0) {
                    stallSince = sentAt;
                    lastDump = 0;
                }
                if (now - lastDump >= REPEAT_MS) {
                    lastDump = now;
                    dump(now - stallSince);
                }
            }
            watch.postDelayed(this, PING_MS);
        }
    };

    private MainThreadWatch() {
        thread.start();
        watch = new Handler(thread.getLooper());
    }

    /** Application start. Repeat calls do nothing. */
    public static void install(Context context) {
        if (installed != null) {
            return;
        }
        installed = new MainThreadWatch();
        installed.watch.post(installed.tick);
        Context app = context.getApplicationContext();
        installed.watch.post(() -> noteExits(app));
    }

    private void dump(long stuckMs) {
        StackTraceElement[] stack = Looper.getMainLooper().getThread().getStackTrace();
        StringBuilder sb = new StringBuilder("Main thread stuck ").append(stuckMs).append(" ms:");
        for (int i = 0; i < Math.min(FRAMES, stack.length); i++) {
            sb.append("\n  at ").append(stack[i]);
        }
        String text = sb.toString();
        AppLog.w(TAG, text);
        BlackBox.noteImportant(text);
    }

    /** The system's record of how this app ended the last few times. */
    private static void noteExits(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(context.getPackageName(), 0, 3);
            for (ApplicationExitInfo e : exits) {
                StringBuilder sb = new StringBuilder("Last exit: ")
                        .append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                                .format(new java.util.Date(e.getTimestamp())))
                        .append(" reason ").append(e.getReason())
                        .append(", ").append(e.getDescription());
                if (e.getReason() == ApplicationExitInfo.REASON_ANR) {
                    sb.append("\nANR trace:");
                    try (InputStream in = e.getTraceInputStream()) {
                        if (in != null) {
                            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                            String line;
                            int n = 0;
                            // the main thread comes first in an ANR trace
                            while ((line = r.readLine()) != null && n < TRACE_LINES) {
                                sb.append('\n').append(line);
                                n++;
                            }
                        }
                    }
                }
                BlackBox.noteImportant(sb.toString());
            }
        } catch (Throwable t) {
            AppLog.w(TAG, "exit reasons: " + t);
        }
    }
}
