package com.kooo.evcam.airplay;

import com.kooo.evcam.AppLog;

import io.github.jqssun.airplay.bridge.NativeBridge;

/** The AirPlay engine's audio output, for callers outside the receiver. */
public final class AirPlayAudio {

    private static final String TAG = "AirPlayAudio";

    private AirPlayAudio() {
    }

    /** Start or stop the mono copy for the outside speaker, at gain 0..1. Holds across AirPlay sessions. */
    public static boolean setOutsideMirror(boolean on, float gain) {
        try {
            NativeBridge.nativeSetOutsideMirror(on, gain);
            return true;
        } catch (UnsatisfiedLinkError e) {
            AppLog.e(TAG, "AirPlay engine not available", e);
            return false;
        }
    }

    /** Frames of the outside copy, up to {@code dst.length}; 0 when nothing is queued. One reader. */
    public static int readOutsideMirror(short[] dst) {
        return NativeBridge.nativeReadOutsideMirror(dst);
    }

    /** Sample rate of the outside copy; 0 until the first AirPlay stream opens. */
    public static int outsideMirrorRate() {
        return NativeBridge.nativeOutsideMirrorRate();
    }

    /** Skip what queued up while nobody read. */
    public static void dropOutsideMirror() {
        NativeBridge.nativeDropOutsideMirror();
    }
}
