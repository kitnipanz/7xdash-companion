package com.kooo.evcam.input;

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
 * Plain-text trace of every sticker link event, one file per day, in the same folder as the
 * diagnostic exports ({@code Download/EVCam_Log} on the USB stick, else internal storage).
 * The shared app log rolls sticker lines out under camera noise; this one keeps them so a
 * drop can be read back with its status, how long the link lasted and when the last press was.
 */
final class StickerLog {

    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final long KEEP_MS = 7L * 24 * 60 * 60 * 1000;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sticker-log");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyyMMdd", Locale.US);
    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static volatile Context app;
    private static boolean pruned;

    private StickerLog() {
    }

    static void attach(Context context) {
        if (context != null) {
            app = context.getApplicationContext();
        }
    }

    /** Never blocks the caller; the line is stamped now and written on a background thread. */
    static void line(String text) {
        Context context = app;
        if (context == null || text == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String stamped;
        String day;
        synchronized (StickerLog.class) {
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
            File file = new File(dir, "sticker-" + day + ".log");
            if (file.length() > MAX_BYTES) {
                File old = new File(dir, "sticker-" + day + ".1.log");
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
        File[] files = dir.listFiles((d, name) -> name.startsWith("sticker-") && name.endsWith(".log"));
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
