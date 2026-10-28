package com.kooo.evcam.audio;

import android.app.Dialog;
import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

import com.kooo.evcam.blackbox.BlackBox;

/**
 * Turns the car's main volume up or down one step, as the steering wheel button does.
 *
 * <p>The car refuses its volume API to apps (CarAudioManager needs CAR_CONTROL_AUDIO_VOLUME)
 * and ignores AudioManager (fixed volume), but takes a volume key handed in through a window:
 * the window passes keys nobody handled to the system's volume handling, which on the 7X turns
 * the car's volume (found with the Volume lab, way 3). The window is a never-shown dialog's, so
 * this works from a service with no screen of ours open.</p>
 */
public final class CarVolume {

    private static Dialog window;

    private CarVolume() {
    }

    /** One step: {@code +1} up, {@code -1} down. Any thread. */
    public static void step(Context context, int direction) {
        key(context, direction > 0 ? KeyEvent.KEYCODE_VOLUME_UP : KeyEvent.KEYCODE_VOLUME_DOWN);
    }

    /**
     * Presses {@code code} the same way: down and up through the window, which hands volume,
     * mute and media keys to the system. Other keys stop at the window. Any thread.
     */
    public static void key(Context context, int code) {
        Context app = context.getApplicationContext();
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(() -> press(app, code));
        } else {
            press(app, code);
        }
    }

    private static void press(Context app, int code) {
        try {
            if (window == null) {
                window = new Dialog(app);
                // Media, not the ring volume, when nothing is playing
                window.setVolumeControlStream(AudioManager.STREAM_MUSIC);
            }
            android.view.View root = window.getWindow().getDecorView();
            long now = SystemClock.uptimeMillis();
            root.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
            root.dispatchKeyEvent(new KeyEvent(now, now + 40, KeyEvent.ACTION_UP, code, 0));
        } catch (RuntimeException e) {
            BlackBox.note("car-volume: " + KeyEvent.keyCodeToString(code) + " failed: " + e);
        }
    }
}
