package io.github.jqssun.airplay.bridge;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * JNI entry points of libairplay_native (app/src/main/cpp). The package and names must stay as
 * they are: the native side is jqssun/android-airplay-server's bridge (GPL-3.0), which binds to
 * {@code Java_io_github_jqssun_airplay_bridge_NativeBridge_*}.
 */
public final class NativeBridge {

    public static final int FT_MIRROR = 7;
    public static final int FT_AUDIO = 9;

    public static final int LOG_INFO = 6;
    public static final int LOG_DEBUG = 7;

    public static final int CT_ALAC = 2;
    public static final int CT_AAC_LC = 4;
    public static final int CT_AAC_ELD = 8;

    static {
        System.loadLibrary("airplay_native");
    }

    private NativeBridge() {
    }

    public static native long nativeInit(RaopCallbackHandler callback, byte[] hwAddr, String name,
                                         String keyFile, boolean nohold, boolean requirePin);

    public static native int nativeStart(long handle, int port);

    public static native void nativeStop(long handle);

    public static native void nativeDestroy(long handle);

    /** UxPlay logger level: 6 = info, 7 = debug (7xDash addition). */
    public static native void nativeSetLogLevel(long handle, int level);

    public static native void nativeSetDisplaySize(long handle, int w, int h, int fps);

    public static native void nativeSetPlist(long handle, String key, int value);

    public static native void nativeSetH265Enabled(long handle, boolean enabled);

    public static native void nativeSetCodecs(long handle, boolean alac, boolean aac);

    public static native void nativeSetHlsEnabled(long handle, boolean enabled);

    public static native void nativeSetLang(long handle, String requested, String subtitles, String system);

    public static native void nativeSetFeature(long handle, int bit, boolean enabled);

    public static native void nativeUpdatePlaybackInfo(long handle, float position, float duration,
                                                       float rate, boolean readyToPlay);

    public static native Map<String, String> nativeGetRaopTxtRecords(long handle);

    public static native Map<String, String> nativeGetAirplayTxtRecords(long handle);

    public static native String nativeGetRaopServiceName(long handle);

    public static native String nativeGetServerName(long handle);

    public static native void nativeSetDefaultStreamValues(int sampleRate, int framesPerBurst);

    public static native boolean nativeServerAudioConfigure(long handle, int cushionMs, int percentilePct,
                                                            int oboeBufferFrames, boolean forceSwAlac,
                                                            boolean forceSwAac, boolean realtimePriority,
                                                            boolean lowLatency, boolean benchmarkLog);

    /** Mono copy of AirPlay audio for the outside speaker, at gain 0..1. */
    public static native void nativeSetOutsideMirror(boolean on, float gain);

    /** Frames of the outside copy, up to the array length; 0 when nothing is queued. One reader. */
    public static native int nativeReadOutsideMirror(short[] dst);

    /** Sample rate of the outside copy; 0 until the first AirPlay stream opens. */
    public static native int nativeOutsideMirrorRate();

    /** Feed the outside copy from another player: interleaved 16-bit PCM in that format. */
    public static native void nativePushOutsideMirror(short[] pcm, int frames, int sampleRate, int channels);

    /** Skip what queued up while nobody read. */
    public static native void nativeDropOutsideMirror();

    public static native boolean nativeServerAudioStart(long handle);

    public static native void nativeServerAudioStop(long handle);

    public static native void nativeServerAudioFormat(long handle, int ct, int spf);

    public static native boolean nativeServerAudioDebug(long handle, ByteBuffer buf);
}
