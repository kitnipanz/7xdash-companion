package com.kooo.evcam.remote;

import android.content.Context;
import android.os.SystemClock;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.StorageHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Latest-frame mailbox for remote watch.
 *
 * <p>Producers put JPEG bytes (wire) and optional PNG bytes (USB dump).
 * CarLink reads {@link #jpegFor(String)} for the phone's current source.
 * USB gets PNGs under {@code 7xDash/live/<slot>.png}, throttled.</p>
 */
public final class RemoteLive {

    public static final String DRIVE = "drive";
    public static final String CH1 = "ch1";
    public static final String CH2 = "ch2";
    public static final String CH3 = "ch3";
    public static final String CH4 = "ch4";
    public static final String DRIVER = "driver";
    public static final String BACKSEAT = "backseat";

    private static final String TAG = "RemoteLive";
    private static final String USB_FOLDER = "7xDash/live";
    private static final long USB_MIN_MS = 1500L;

    private final ConcurrentHashMap<String, byte[]> jpeg = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, byte[]> png = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastUsbMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, byte[]> pendingPng = new ConcurrentHashMap<>();
    private final AtomicBoolean usbPosted = new AtomicBoolean(false);
    private final ExecutorService usbExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "remote-live-usb");
        t.setPriority(Thread.NORM_PRIORITY - 2);
        return t;
    });

    private volatile Context app;

    public void attach(Context context) {
        this.app = context.getApplicationContext();
    }

    /** Phone {@code source} → mailbox slot. Unknown → drive mosaic. */
    public static String slotFor(String source) {
        String s = source == null ? "" : source.trim().toLowerCase();
        if (DRIVER.equals(s) || BACKSEAT.equals(s)
                || CH1.equals(s) || CH2.equals(s) || CH3.equals(s) || CH4.equals(s)) {
            return s;
        }
        return DRIVE;
    }

    public void put(String slot, byte[] jpegBytes, byte[] pngBytes) {
        if (slot == null || slot.isEmpty()) {
            return;
        }
        if (jpegBytes != null && jpegBytes.length > 0) {
            jpeg.put(slot, jpegBytes);
        }
        if (pngBytes != null && pngBytes.length > 0) {
            png.put(slot, pngBytes);
            offerUsb(slot, pngBytes);
        }
    }

    /** Latest JPEG for the phone's watch source, or null if not captured yet. */
    public byte[] jpegFor(String source) {
        return jpeg.get(slotFor(source));
    }

    public void clear() {
        jpeg.clear();
        png.clear();
        pendingPng.clear();
    }

    public void shutdown() {
        clear();
        usbExec.shutdownNow();
    }

    private void offerUsb(String slot, byte[] pngBytes) {
        long now = SystemClock.elapsedRealtime();
        Long last = lastUsbMs.get(slot);
        if (last != null && now - last < USB_MIN_MS) {
            return;
        }
        lastUsbMs.put(slot, now);
        pendingPng.put(slot, pngBytes);
        if (!usbPosted.compareAndSet(false, true)) {
            return;
        }
        usbExec.execute(this::drainUsb);
    }

    private void drainUsb() {
        try {
            while (true) {
                Context ctx = app;
                if (ctx == null) {
                    break;
                }
                File root = StorageHelper.getExternalSdCardRoot(ctx);
                if (root == null) {
                    pendingPng.clear();
                    break;
                }
                File dir = new File(root, USB_FOLDER);
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    AppLog.w(TAG, "cannot mkdir " + dir);
                    break;
                }
                java.util.List<String> keys = new java.util.ArrayList<>(pendingPng.keySet());
                if (keys.isEmpty()) {
                    break;
                }
                for (String key : keys) {
                    byte[] data = pendingPng.remove(key);
                    if (data == null) {
                        continue;
                    }
                    writeAtom(dir, key + ".png", data);
                }
            }
        } catch (RuntimeException e) {
            AppLog.w(TAG, "usb drain: " + e.getMessage());
        } finally {
            usbPosted.set(false);
            if (!pendingPng.isEmpty() && usbPosted.compareAndSet(false, true)) {
                usbExec.execute(this::drainUsb);
            }
        }
    }

    private static void writeAtom(File dir, String name, byte[] data) {
        File dest = new File(dir, name);
        File tmp = new File(dir, name + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
                out.flush();
            }
            if (!tmp.renameTo(dest)) {
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(dest);
            }
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }
}
