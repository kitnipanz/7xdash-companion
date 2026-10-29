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
     * Ask the decoder to hand pictures out at once. {@code low-latency} is Android 11's key; the
     * vendor keys reach Qualcomm and Exynos decoders on older Android. Unknown keys are ignored.
     */
    private static final String[] LOW_LATENCY_KEYS = {
            "low-latency",
            "vendor.qti-ext-dec-low-latency.enable",
            "vendor.rtc-ext-dec-low-latency.enable",
    };

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
        MediaCodec c = MediaCodec.createDecoderByType(AVC);
        boolean lowLatency = true;
        try {
            c.setCallback(new Callback(c), handler);
            c.configure(format(true), surface, null, 0);
            c.start();
        } catch (Exception e) {
            // some decoders refuse the low-latency keys; try once without them
            AirPlayLog.w("decoder refused low latency: " + e);
            c.release();
            lowLatency = false;
            c = MediaCodec.createDecoderByType(AVC);
            c.setCallback(new Callback(c), handler);
            c.configure(format(false), surface, null, 0);
            c.start();
        }
        codec = c;
        firstQueued = false;
        broken = false;
        dropped = 0;
        AirPlayLog.i("decoder started " + width + "x" + height + " (" + c.getName()
                + (lowLatency ? ", low latency asked" : "") + ")");
    }

    private MediaFormat format(boolean lowLatency) {
        MediaFormat format = MediaFormat.createVideoFormat(AVC, width, height);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(width * height * 3 / 4, 1024 * 1024));
        if (lowLatency) {
            // realtime: the decoder keeps up rather than saving power
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
            for (String key : LOW_LATENCY_KEYS) {
                format.setInteger(key, 1);
            }
        }
        return format;
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
        codec.queueInputBuffer(idx, 0, data.length, ntpTimeNs / 1000, 0);
        firstQueued = true;
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
