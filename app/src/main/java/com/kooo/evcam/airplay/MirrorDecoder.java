package com.kooo.evcam.airplay;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.view.Surface;

import androidx.annotation.NonNull;

import java.util.ArrayDeque;


/**
 * Decodes the iPhone's or Mac's mirrored screen (H.264 from UxPlay) straight onto the floating
 * window's surface. Frames arrive on a native thread; the codec runs in async mode on its own
 * thread, so each picture is shown the moment it is decoded instead of when the next packet comes
 * (a Mac sends nothing while its screen is still, which left the last change hidden).
 *
 * <p>Follows jqssun/android-airplay-server's VideoRenderer (GPL-3.0), cut down: one codec, no
 * EGL pipeline. When the window's surface goes away the codec stops, and it restarts on the
 * next keyframe once a surface is back.</p>
 */
final class MirrorDecoder {

    private static final String AVC = MediaFormat.MIMETYPE_VIDEO_AVC;
    /** Longest a packet waits for a free input buffer before it is dropped. */
    private static final long FEED_WAIT_MS = 200L;
    private static final long FIRST_FEED_WAIT_MS = 1000L;
    /**
     * Ask the decoder to hand pictures out at once. {@code low-latency} is Android 11's key,
     * {@code vdec-lowlatency} MediaTek's; the vendor keys reach Exynos and Amlogic decoders on older
     * Android. Unknown keys are ignored. Qualcomm gets its own below. Picked as in moonlight-android
     * and jqssun/android-airplay-server's DecoderSelector.
     */
    private static final String[] LOW_LATENCY_KEYS = {
            "low-latency",
            "vdec-lowlatency",
            "vendor.rtc-ext-dec-low-latency.enable",
            "vendor.low-latency.enable",
    };
    /** Qualcomm: hand pictures out in decode order (mirroring has no B-frames) and right away. */
    private static final String[] QTI_KEYS = {
            "vendor.qti-ext-dec-picture-order.enable",
            "vendor.qti-ext-dec-low-latency.enable",
    };

    private final int fps;

    private final Object lock = new Object();
    private MediaCodec codec;
    private HandlerThread thread;
    private final ArrayDeque<Integer> freeInputs = new ArrayDeque<>();
    private Surface surface;
    private int width;
    private int height;
    private boolean firstQueued;
    private boolean broken;
    private int dropped;
    private final DelayStats stats = new DelayStats();

    MirrorDecoder(int fps) {
        this.fps = fps;
    }

    void setSize(int w, int h) {
        synchronized (lock) {
            if (w == width && h == height) {
                return;
            }
            width = w;
            height = h;
            stopCodec();
        }
    }

    void setSurface(Surface s) {
        synchronized (lock) {
            if (surface == s) {
                return;
            }
            stopCodec();
            surface = s;
        }
    }

    void feed(byte[] data, long ntpTimeNs) {
        synchronized (lock) {
            if (broken) {
                stopCodec();
            }
            if (surface == null || width <= 0 || height <= 0) {
                return;
            }
            if (codec == null) {
                // a stale reference frame decodes to garbage: only (re)start on a keyframe
                if (!isKeyframe(data)) {
                    return;
                }
                try {
                    startCodec();
                } catch (Exception e) {
                    AirPlayLog.w("decoder start failed: " + e);
                    stopCodec();
                    return;
                }
            }
            try {
                queue(data, ntpTimeNs);
            } catch (Exception e) {
                AirPlayLog.w("decoder error, resetting: " + e);
                stopCodec();
            }
        }
    }

    void release() {
        synchronized (lock) {
            stopCodec();
            surface = null;
        }
    }

    private void startCodec() throws Exception {
        thread = new HandlerThread("MirrorDecoder", Process.THREAD_PRIORITY_DISPLAY);
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        String name = hardwareDecoder();
        boolean qti = name != null && isQti(name);
        MediaCodec c = create(name);
        boolean lowLatency = true;
        try {
            c.setCallback(new Callback(c), handler);
            c.configure(format(true, qti), surface, null, 0);
            c.start();
        } catch (Exception e) {
            // some decoders refuse the low-latency keys; try once without them
            AirPlayLog.w("decoder refused low latency: " + e);
            c.release();
            lowLatency = false;
            c = create(name);
            c.setCallback(new Callback(c), handler);
            c.configure(format(false, qti), surface, null, 0);
            c.start();
        }
        codec = c;
        firstQueued = false;
        broken = false;
        dropped = 0;
        stats.reset();
        AirPlayLog.i("decoder started " + width + "x" + height + " (" + c.getName()
                + (lowLatency ? ", low latency asked" : "") + ")");
    }

    private MediaFormat format(boolean lowLatency, boolean qti) {
        MediaFormat format = MediaFormat.createVideoFormat(AVC, width, height);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(width * height * 3 / 4, 1024 * 1024));
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        if (lowLatency) {
            if (qti) {
                // Qualcomm clocks up for the highest rate; others take realtime priority
                format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE);
                for (String key : QTI_KEYS) {
                    format.setInteger(key, 1);
                }
            } else {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0);
            }
            for (String key : LOW_LATENCY_KEYS) {
                format.setInteger(key, 1);
            }
        }
        return format;
    }

    private static MediaCodec create(String name) throws java.io.IOException {
        return name != null ? MediaCodec.createByCodecName(name) : MediaCodec.createDecoderByType(AVC);
    }

    /**
     * The first hardware H.264 decoder that plays High profile. Android's own pick can be a
     * software decoder on some head units, which falls behind and stutters.
     */
    static String hardwareDecoder() {
        try {
            String fallback = null;
            for (android.media.MediaCodecInfo info
                    : new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (info.isEncoder() || !supportsAvc(info) || isSoftware(info)) {
                    continue;
                }
                if (fallback == null) {
                    fallback = info.getName();
                }
                for (android.media.MediaCodecInfo.CodecProfileLevel pl
                        : info.getCapabilitiesForType(AVC).profileLevels) {
                    if (pl.profile == android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh) {
                        return info.getName();
                    }
                }
            }
            return fallback;
        } catch (RuntimeException e) {
            AirPlayLog.w("decoder list failed: " + e);
            return null;
        }
    }

    private static boolean supportsAvc(android.media.MediaCodecInfo info) {
        for (String type : info.getSupportedTypes()) {
            if (type.equalsIgnoreCase(AVC)) {
                return true;
            }
        }
        return false;
    }

    static boolean isSoftware(android.media.MediaCodecInfo info) {
        if (android.os.Build.VERSION.SDK_INT >= 29 && info.isSoftwareOnly()) {
            return true;
        }
        String n = info.getName().toLowerCase(java.util.Locale.US);
        return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw.")
                || n.endsWith(".sw") || n.contains("ffmpeg") || n.equals("avcdecoder");
    }

    static boolean isQti(String name) {
        String n = name.toLowerCase(java.util.Locale.US);
        return n.startsWith("omx.qcom") || n.startsWith("c2.qti");
    }

    /** Waits for a free input buffer (the codec thread hands them back); drops the packet if none comes. */
    private void queue(byte[] data, long ntpTimeNs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + (firstQueued ? FEED_WAIT_MS : FIRST_FEED_WAIT_MS);
        while (freeInputs.isEmpty() && codec != null && !broken) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                if (dropped++ % 30 == 0) {
                    AirPlayLog.w("decoder busy, dropped " + dropped + " packet(s)");
                }
                return;
            }
            lock.wait(left);
        }
        if (codec == null || broken) {
            return;
        }
        int idx = freeInputs.poll();
        java.nio.ByteBuffer buf = codec.getInputBuffer(idx);
        if (buf == null) {
            return;
        }
        buf.clear();
        buf.put(data);
        long ptsUs = ntpTimeNs / 1000;
        codec.queueInputBuffer(idx, 0, data.length, ptsUs, 0);
        firstQueued = true;
        stats.queued(ptsUs, System.currentTimeMillis() * 1_000_000L - ntpTimeNs, System.nanoTime());
    }

    private void stopCodec() {
        freeInputs.clear();
        broken = false;
        lock.notifyAll();
        if (codec != null) {
            try {
                codec.stop();
            } catch (RuntimeException ignored) {
                // already broken
            }
            codec.release();
            codec = null;
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
    }

    /** Runs on the codec's own thread; ignores a codec that has since been stopped. */
    private final class Callback extends MediaCodec.Callback {
        private final MediaCodec owner;

        Callback(MediaCodec owner) {
            this.owner = owner;
        }

        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec c, int index) {
            synchronized (lock) {
                if (codec == owner) {
                    freeInputs.add(index);
                    lock.notifyAll();
                }
            }
        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec c, int index, @NonNull MediaCodec.BufferInfo info) {
            try {
                // mirroring is live: show each picture as soon as it is decoded
                c.releaseOutputBuffer(index, true);
            } catch (IllegalStateException ignored) {
                // stopped meanwhile
                return;
            }
            String line;
            synchronized (lock) {
                line = stats.shown(info.presentationTimeUs, System.nanoTime(), dropped);
            }
            if (line != null) {
                AirPlayLog.i(line);
            }
        }

        @Override
        public void onError(@NonNull MediaCodec c, @NonNull MediaCodec.CodecException e) {
            AirPlayLog.w("decoder error: " + e.getDiagnosticInfo());
            synchronized (lock) {
                if (codec == owner) {
                    // stopped from the feeding thread; stopping here can hang some decoders
                    broken = true;
                    lock.notifyAll();
                }
            }
        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec c, @NonNull MediaFormat format) {
            AirPlayLog.i("decoder output " + format.getInteger(MediaFormat.KEY_WIDTH)
                    + "x" + format.getInteger(MediaFormat.KEY_HEIGHT));
        }
    }

    /**
     * How late the picture is, every few seconds: age is the sender's timestamp to arrival here
     * (the sender's encoding plus the network, on the synced AirPlay clock), decode is arrival to
     * the picture being handed to the screen. Guarded by the decoder's lock.
     */
    static final class DelayStats {
        static final long EVERY_NS = 5_000_000_000L;
        private static final int SLOTS = 64;
        private final long[] pts = new long[SLOTS];
        private final long[] at = new long[SLOTS];
        private int next;
        private long since;
        private int frames;
        private int aged;
        private long ageSum;
        private long ageMax;
        private long decodeSum;
        private long decodeMax;
        private int decoded;

        void reset() {
            java.util.Arrays.fill(at, 0L);
            since = 0;
            frames = aged = decoded = 0;
            ageSum = ageMax = decodeSum = decodeMax = 0;
        }

        void queued(long ptsUs, long ageNs, long nowNs) {
            pts[next] = ptsUs;
            at[next] = nowNs;
            next = (next + 1) % SLOTS;
            // before the clocks sync, or across a resync, the age means nothing
            if (ageNs > -1_000_000_000L && ageNs < 10_000_000_000L) {
                aged++;
                ageSum += ageNs;
                ageMax = Math.max(ageMax, ageNs);
            }
        }

        /** A log line when a period is over, else null. */
        String shown(long ptsUs, long nowNs, int dropped) {
            if (since == 0) {
                since = nowNs;
            }
            frames++;
            for (int i = 0; i < SLOTS; i++) {
                if (at[i] != 0 && pts[i] == ptsUs) {
                    long d = nowNs - at[i];
                    decoded++;
                    decodeSum += d;
                    decodeMax = Math.max(decodeMax, d);
                    at[i] = 0;
                    break;
                }
            }
            long span = nowNs - since;
            if (span < EVERY_NS) {
                return null;
            }
            String line = String.format(java.util.Locale.US,
                    "mirror delay: age avg %d max %d ms, decode avg %d max %d ms, %.0f fps, %d dropped",
                    aged == 0 ? -1 : ageSum / aged / 1_000_000, ageMax / 1_000_000,
                    decoded == 0 ? -1 : decodeSum / decoded / 1_000_000, decodeMax / 1_000_000,
                    frames * 1e9 / span, dropped);
            long keepSince = nowNs;
            reset();
            since = keepSince;
            return line;
        }
    }

    /** IDR slice (5) or SPS (7) after an Annex-B start code. */
    static boolean isKeyframe(byte[] data) {
        for (int i = 0; i + 4 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1) {
                int type = data[i + 4] & 0x1F;
                if (type == 5 || type == 7) {
                    return true;
                }
            }
        }
        return false;
    }
}
