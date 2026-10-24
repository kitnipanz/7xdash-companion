package com.kooo.evcam.audio;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.input.MegaphoneService;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Beta outside speaker: what is AirPlayed to 7xDash also plays on the outside bus, the one the
 * megaphone uses. 7xDash is the player, so no capture grant is needed.
 *
 * <p>Copying other apps' sound (CarPlay, Spotify, radio) does not work on this car: their players
 * refuse playback capture, and even players that allow it come back silent through the car's
 * audio routing (1.1.10 black box). The copy happens in the AirPlay engine's native output
 * ({@code OutsideMirror} in audio_output.h).</p>
 */
public final class OutsideSpeaker {

    /** While the megaphone talks on the same bus, the music drops to this share. */
    static final float DUCK = 0.15f;
    private static final long DUCK_POLL_MS = 200L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    private static volatile boolean on;
    private static int deviceId;
    private static float volume = 1f;
    private static float sent = -1f;
    private static final Runnable duck = new Runnable() {
        @Override
        public void run() {
            if (!on) {
                return;
            }
            push();
            MAIN.postDelayed(this, DUCK_POLL_MS);
        }
    };

    private OutsideSpeaker() {
    }

    /** Volume slider (0–100) to gain. Squared so the low half of the slider is usable. */
    static float gain(int percent) {
        int p = Math.max(0, Math.min(100, percent));
        float linear = p / 100f;
        return linear * linear;
    }

    public static boolean isSupported() {
        return true;
    }

    public static boolean isOn() {
        return on;
    }

    /** Runs on the main thread whenever on or the volume changes. */
    public static void addListener(Runnable listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public static void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    public static void toggle(Context context) {
        if (on) {
            stop(context);
        } else {
            start(context);
        }
    }

    public static void start(Context context) {
        MAIN.post(() -> begin(context.getApplicationContext()));
    }

    public static void stop(Context context) {
        MAIN.post(OutsideSpeaker::end);
    }

    /** Saves the slider and applies it to the music already playing. */
    public static void setVolume(Context context, int percent) {
        if (context == null) {
            return;
        }
        AppConfig config = new AppConfig(context);
        config.setOutsideSpeakerVolume(percent);
        MAIN.post(() -> {
            volume = gain(config.getOutsideSpeakerVolume());
            push();
        });
        changed();
    }

    private static void begin(Context context) {
        if (on) {
            return;
        }
        AppConfig config = new AppConfig(context);
        AudioManager manager = context.getSystemService(AudioManager.class);
        AudioDeviceInfo device = manager == null ? null : com.kooo.evcam.input.Megaphone.find(
                manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS), config.getSpeakSpeaker());
        if (device == null) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("Outside speaker: no outside speaker device ("
                    + config.getSpeakSpeaker() + ")");
            com.kooo.evcam.ui.Notice.makeText(context, R.string.megaphone_no_speaker, Toast.LENGTH_SHORT).show();
            return;
        }
        deviceId = device.getId();
        volume = gain(config.getOutsideSpeakerVolume());
        sent = -1f;
        on = true;
        push();
        MAIN.postDelayed(duck, DUCK_POLL_MS);
        boolean airplay = com.kooo.evcam.airplay.AirPlayService.isRunning();
        com.kooo.evcam.blackbox.BlackBox.noteImportant("Outside speaker: on, device " + deviceId
                + " (" + device.getAddress() + "), AirPlay " + (airplay ? "running" : "off"));
        com.kooo.evcam.ui.Notice.makeText(context, airplay ? R.string.outside_speaker_playing
                : R.string.outside_speaker_needs_airplay, Toast.LENGTH_LONG).show();
        changed();
    }

    private static void end() {
        if (!on) {
            return;
        }
        on = false;
        MAIN.removeCallbacks(duck);
        com.kooo.evcam.airplay.AirPlayAudio.setOutsideMirror(0, 0f);
        com.kooo.evcam.blackbox.BlackBox.noteImportant("Outside speaker: off");
        changed();
    }

    /** Main thread. Sends the gain only when it changed: the megaphone check runs five times a second. */
    private static void push() {
        if (!on) {
            return;
        }
        float gain = MegaphoneService.isOpen() ? volume * DUCK : volume;
        if (gain == sent) {
            return;
        }
        sent = gain;
        com.kooo.evcam.airplay.AirPlayAudio.setOutsideMirror(deviceId, gain);
    }

    private static void changed() {
        MAIN.post(() -> {
            for (Runnable listener : listeners) {
                listener.run();
            }
        });
    }
}
