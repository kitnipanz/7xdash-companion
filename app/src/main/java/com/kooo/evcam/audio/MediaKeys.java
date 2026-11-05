package com.kooo.evcam.audio;

import android.content.Context;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;

import com.kooo.evcam.AppLog;

/** Media keys for whichever app is playing: one key down and up, as a headset button sends. */
public final class MediaKeys {

    private static final String TAG = "MediaKeys";

    private MediaKeys() {
    }

    public static void playPause(Context context) {
        send(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
    }

    public static void next(Context context) {
        send(context, KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    public static void previous(Context context) {
        send(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    private static void send(Context context, int code) {
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) {
            return;
        }
        try {
            long now = SystemClock.uptimeMillis();
            audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
            audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
        } catch (RuntimeException e) {
            AppLog.w(TAG, "media key " + code + " failed: " + e);
        }
    }
}
