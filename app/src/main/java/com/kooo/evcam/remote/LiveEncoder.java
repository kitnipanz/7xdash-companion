package com.kooo.evcam.remote;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import com.kooo.evcam.AppLog;

import java.nio.ByteBuffer;

/**
 * H.264 encoder for remote watch. Surface input: a GL pipe (surround) or a canvas
 * (cabin) draws into {@link #inputSurface()}, and every encoded access unit goes to the
 * sink as Annex-B bytes. Keyframes carry SPS/PPS in front so the phone can start
 * decoding from any of them.
 */
public final class LiveEncoder {

    /** One encoded access unit. {@code data} is Annex-B, SPS/PPS included on keyframes. */
    public interface Sink {
        void onPacket(byte[] data, boolean key, long ptsUs, int width, int height);
    }

    private static final String TAG = "LiveEncoder";
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;

    public final int width;
    public final int height;
    private final Sink sink;
    private final HandlerThread thread;
    private MediaCodec codec;
    private Surface input;
    private volatile byte[] config = new byte[0];
    private volatile boolean stopped;

    private LiveEncoder(int width, int height, Sink sink) {
        this.width = width;
        this.height = height;
        this.sink = sink;
        this.thread = new HandlerThread("live-enc");
        this.thread.start();
    }

    /** Null when the device has no usable AVC encoder at this size. */
    public static LiveEncoder start(int width, int height, int fps, int bitrate, Sink sink) {
        LiveEncoder enc = new LiveEncoder(width & ~1, height & ~1, sink);
        try {
            enc.open(fps, bitrate);
            return enc;
        } catch (Exception e) {
            AppLog.w(TAG, "encoder " + width + "x" + height + " failed: " + e);
            enc.stop();
            return null;
        }
    }

    private void open(int fps, int bitrate) throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        // One second GOP: a late viewer or a dropped packet heals within a second.
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        // Baseline: no B-frames, so decode order is display order and latency stays low.
        format.setInteger(MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
        format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31);
        format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        // A parked car barely changes; keep frames flowing so the phone never looks frozen.
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 250_000L);

        MediaCodec c = MediaCodec.createEncoderByType(MIME);
        c.setCallback(new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(MediaCodec mc, int index) {
            }

            @Override
            public void onOutputBufferAvailable(MediaCodec mc, int index, MediaCodec.BufferInfo info) {
                drain(mc, index, info);
            }

            @Override
            public void onError(MediaCodec mc, MediaCodec.CodecException e) {
                AppLog.w(TAG, "codec error: " + e.getDiagnosticInfo());
            }

            @Override
            public void onOutputFormatChanged(MediaCodec mc, MediaFormat fmt) {
            }
        }, new Handler(thread.getLooper()));
        try {
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (Exception e) {
            // Some encoders refuse the profile/level hints; retry with defaults.
            format.removeKey(MediaFormat.KEY_PROFILE);
            format.removeKey(MediaFormat.KEY_LEVEL);
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        }
        input = c.createInputSurface();
        c.start();
        codec = c;
        AppLog.i(TAG, "started " + width + "x" + height + " " + fps + "fps " + bitrate / 1000 + "kbps");
    }

    public Surface inputSurface() {
        return input;
    }

    /** Next frame out is a keyframe: new viewer, camera switch, or the hub dropped packets. */
    public void requestKeyFrame() {
        MediaCodec c = codec;
        if (c == null || stopped) {
            return;
        }
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            c.setParameters(b);
        } catch (IllegalStateException ignored) {
        }
    }

    private void drain(MediaCodec mc, int index, MediaCodec.BufferInfo info) {
        try {
            if (stopped) {
                return;
            }
            ByteBuffer buf = mc.getOutputBuffer(index);
            if (buf == null || info.size <= 0) {
                return;
            }
            byte[] data = new byte[info.size];
            buf.position(info.offset);
            buf.limit(info.offset + info.size);
            buf.get(data);
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                config = data;
                return;
            }
            boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
            if (key && config.length > 0) {
                byte[] withConfig = new byte[config.length + data.length];
                System.arraycopy(config, 0, withConfig, 0, config.length);
                System.arraycopy(data, 0, withConfig, config.length, data.length);
                data = withConfig;
            }
            sink.onPacket(data, key, info.presentationTimeUs, width, height);
        } catch (IllegalStateException ignored) {
        } finally {
            try {
                mc.releaseOutputBuffer(index, false);
            } catch (IllegalStateException ignored) {
            }
        }
    }

    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        MediaCodec c = codec;
        codec = null;
        if (c != null) {
            try {
                c.stop();
            } catch (Exception ignored) {
            }
            try {
                c.release();
            } catch (Exception ignored) {
            }
        }
        if (input != null) {
            input.release();
            input = null;
        }
        thread.quitSafely();
    }
}
