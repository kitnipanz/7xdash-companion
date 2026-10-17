package com.kooo.evcam.recording;

import android.content.Context;

import com.kooo.evcam.StorageHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Plain-text trace of every flash-to-pass / instant capture step, one file per day, next to
 * the sticker log ({@code Download/EVCam_Log/capture-YYYYMMDD.log} on the USB stick, else
 * internal storage). Every way a capture can fail shows the same toast; this says which.
 */
public final class CaptureLog {

    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final long KEEP_MS = 7L * 24 * 60 * 60 * 1000;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "capture-log");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyyMMdd", Locale.US);
    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static volatile Context app;
    private static boolean pruned;

    private CaptureLog() {
    }

    public static void attach(Context context) {
        if (context != null) {
            app = context.getApplicationContext();
        }
    }

    /** Never blocks the caller; the line is stamped now and written on a background thread. */
    public static void line(String text) {
        Context context = app;
        if (context == null || text == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String stamped;
        String day;
        synchronized (CaptureLog.class) {
            stamped = CLOCK.format(new Date(now)) + "  " + text + "\n";
            day = DAY.format(new Date(now));
        }
        IO.execute(() -> write(context, day, stamped));
    }

    private static void write(Context context, String day, String text) {
        try {
            File dir = StorageHelper.getLogDir(context, true);
            if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) {
                return;
            }
            if (!pruned) {
                pruned = true;
                prune(dir);
            }
            File file = new File(dir, "capture-" + day + ".log");
            if (file.length() > MAX_BYTES) {
                File old = new File(dir, "capture-" + day + ".1.log");
                //noinspection ResultOfMethodCallIgnored
                old.delete();
                //noinspection ResultOfMethodCallIgnored
                file.renameTo(old);
            }
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException ignored) {
            // USB pulled or full: the trace is best effort
        }
    }

    private static void prune(File dir) {
        File[] files = dir.listFiles((d, name) -> name.startsWith("capture-") && name.endsWith(".log"));
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - KEEP_MS;
        for (File f : files) {
            if (f.lastModified() < cutoff) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }
}
