package com.kooo.evcam.airplay;

import com.kooo.evcam.AppLog;

import io.github.jqssun.airplay.bridge.NativeBridge;

/** The AirPlay engine's audio output, for callers outside the receiver. */
public final class AirPlayAudio {

    private static final String TAG = "AirPlayAudio";

    private AirPlayAudio() {
    }

    /**
     * Also play AirPlay audio on {@code deviceId} at {@code gain} (0..1); device 0 stops the copy.
     * Holds until changed, across AirPlay sessions.
     */
    public static void setOutsideMirror(int deviceId, float gain) {
        try {
            NativeBridge.nativeSetOutsideMirror(deviceId, gain);
        } catch (UnsatisfiedLinkError e) {
            AppLog.e(TAG, "AirPlay engine not available", e);
        }
    }
}
