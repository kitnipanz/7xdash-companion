package com.kooo.evcam.zeekr;

import android.content.Context;
import android.graphics.SurfaceTexture;

import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.camera.CameraNeeds;
import com.kooo.evcam.camera.ChannelRules;
import com.kooo.evcam.camera.SingleCamera;
import com.kooo.evcam.screen.ScreenState;

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
 * Plain-text trace of the cabin passenger window, one file per day ({@code cabin-yyyyMMdd.log}),
 * next to the diagnostic exports ({@code Download/EVCam_Log} on the USB stick, else internal
 * storage). Written to find why the window can open blank while the cabin camera works:
 * every open, canvas event, bind, frame and watchdog decision, with the camera's state.
 */
public final class CabinLog {

    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final long KEEP_MS = 7L * 24 * 60 * 60 * 1000;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cabin-log");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyyMMdd", Locale.US);
    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static volatile Context app;
    private static boolean pruned;

    private CabinLog() {
    }

    /** Never blocks the caller; the line is stamped now and written on a background thread. */
    public static void line(Context context, String text) {
        if (context != null && app == null) {
            app = context.getApplicationContext();
        }
        Context target = app;
        if (target == null || text == null) {
            return;
        }
        long now = System.currentTimeMillis();
        String stamped;
        String day;
        synchronized (CabinLog.class) {
            stamped = CLOCK.format(new Date(now)) + "  " + text + "\n";
            day = DAY.format(new Date(now));
        }
        com.kooo.evcam.AppLog.d("CabinLog", text);
        IO.execute(() -> write(target, day, stamped));
    }

    /** Screen, camera needs, and whether the main screen is up: the things that decide the cabin lane. */
    static String world() {
        return "dark=" + ScreenState.dark()
                + " needs=" + CameraNeeds.current().describe()
                + " main=" + main();
    }

    private static String main() {
        com.kooo.evcam.MainActivity main = com.kooo.evcam.MainActivity.getInstance();
        if (main == null) {
            return "none";
        }
        try {
            return main.hasWindowFocus() ? "focused" : "background";
        } catch (RuntimeException e) {
            return "up";
        }
    }

    /** One line of camera state, and whether {@code texture} is in its live session. */
    static String camera(SingleCamera camera, SurfaceTexture texture) {
        if (camera == null) {
            return "camera=null";
        }
        StringBuilder sb = new StringBuilder(camera.describeForStall());
        if (texture != null) {
            ChannelRules.Out mine = new ChannelRules.Out(ChannelRules.Kind.MIRROR, texture);
            sb.append(" | canvas ").append(id(texture))
                    .append(" declared=").append(camera.declaredOutputs().contains(mine))
                    .append(" inSession=").append(camera.sessionOutputs().carries(mine))
                    .append(" gen=").append(camera.sessionGeneration());
        }
        return sb.toString();
    }

    static String id(Object o) {
        return o == null ? "null" : "@" + Integer.toHexString(System.identityHashCode(o));
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
            File file = new File(dir, "cabin-" + day + ".log");
            if (file.length() > MAX_BYTES) {
                File old = new File(dir, "cabin-" + day + ".1.log");
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
        File[] files = dir.listFiles((d, name) -> name.startsWith("cabin-") && name.endsWith(".log"));
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
