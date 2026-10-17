package com.kooo.evcam.airplay;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Surface;

import com.kooo.evcam.AppLog;

/**
 * Decodes the iPhone's mirrored screen (H.264 from UxPlay) straight onto the floating window's
 * surface. Frames arrive on a native thread; everything here is guarded by one lock.
 *
 * <p>Follows jqssun/android-airplay-server's VideoRenderer (GPL-3.0), cut down: one codec, no
 * EGL pipeline. When the window's surface goes away the codec stops, and it restarts on the
 * next keyframe once a surface is back.</p>
 */
final class MirrorDecoder {

    private static final String TAG = "AirPlayVideo";
    private static final String AVC = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final long FEED_WAIT_US = 20_000L;
    private static final int FEED_RETRIES = 10;
    private static final int FIRST_FEED_RETRIES = 50;

    private final Object lock = new Object();
    private MediaCodec codec;
    private Surface surface;
    private int width;
    private int height;
    private boolean firstQueued;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

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
                    AppLog.w(TAG, "decoder start failed: " + e);
                    stopCodec();
                    return;
                }
            }
            try {
                queue(data, ntpTimeNs);
                drain();
            } catch (Exception e) {
                AppLog.w(TAG, "decoder error, resetting: " + e);
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
        MediaFormat format = MediaFormat.createVideoFormat(AVC, width, height);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(width * height * 3 / 4, 1024 * 1024));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        }
        MediaCodec c = MediaCodec.createDecoderByType(AVC);
        try {
            c.configure(format, surface, null, 0);
            c.start();
        } catch (Exception e) {
            // some decoders refuse low-latency; try once without it
            c.release();
            format = MediaFormat.createVideoFormat(AVC, width, height);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(width * height * 3 / 4, 1024 * 1024));
            c = MediaCodec.createDecoderByType(AVC);
            c.configure(format, surface, null, 0);
            c.start();
        }
        codec = c;
        firstQueued = false;
        AppLog.i(TAG, "decoder started " + width + "x" + height + " (" + c.getName() + ")");
    }

    private void queue(byte[] data, long ntpTimeNs) {
        int retries = firstQueued ? FEED_RETRIES : FIRST_FEED_RETRIES;
        for (int i = 0; i < retries; i++) {
            int idx = codec.dequeueInputBuffer(FEED_WAIT_US);
            if (idx >= 0) {
                java.nio.ByteBuffer buf = codec.getInputBuffer(idx);
                if (buf == null) {
                    return;
                }
                buf.clear();
                buf.put(data);
                codec.queueInputBuffer(idx, 0, data.length, ntpTimeNs / 1000, 0);
                firstQueued = true;
                return;
            }
            drain();
        }
    }

    private void drain() {
        while (true) {
            int idx = codec.dequeueOutputBuffer(info, 0);
            if (idx < 0) {
                return;
            }
            // mirroring is live: show each frame as soon as it is decoded
            codec.releaseOutputBuffer(idx, true);
        }
    }

    private void stopCodec() {
        if (codec == null) {
            return;
        }
        try {
            codec.stop();
        } catch (RuntimeException ignored) {
            // already broken
        }
        codec.release();
        codec = null;
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
