package com.kooo.evcam.audio;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.widget.Toast;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.input.MegaphoneService;
import com.kooo.evcam.input.SpeakerSet;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Outside speaker: what is AirPlayed to 7xDash also plays on the outside bus, the one the
 * megaphone uses (every speaker picked for it). 7xDash is the player, so no capture grant is needed. The AirPlay engine keeps a
 * mono copy of what it plays ({@code OutsideMirror} in audio_output.h); a thread here pulls it and
 * plays it on a track set up exactly like the megaphone's, which the car routes to the outside bus.
 *
 * <p>Copying other apps' sound (CarPlay, Spotify, radio) does not work on this car: their players
 * refuse playback capture, and even players that allow it come back silent through the car's
 * audio routing (1.1.10 black box). 1.1.12 opened the copy straight on the bus device from native
 * code and stayed silent; the megaphone's track is the route known to reach the speaker.</p>
 */
public final class OutsideSpeaker {

    /** While the megaphone talks on the same bus, the music drops to this share. */
    static final float DUCK = 0.15f;
    private static final long DUCK_POLL_MS = 200L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** 10 ms at 48 kHz; the track paces the reads. */
    private static final int CHUNK_FRAMES = 480;

    private static volatile boolean on;
    private static Thread pump;
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
        List<AudioDeviceInfo> found = SpeakerSet.find(manager, config.getSpeakSpeakers());
        if (found.isEmpty()) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("Outside speaker: none of the picked speakers is there ("
                    + String.join(", ", config.getSpeakSpeakers()) + ")");
            com.kooo.evcam.ui.Notice.makeText(context, R.string.megaphone_no_speaker, Toast.LENGTH_SHORT).show();
            return;
        }
        volume = gain(config.getOutsideSpeakerVolume());
        sent = -1f;
        on = true;
        push();
        if (sent < 0f) {
            on = false;
            com.kooo.evcam.ui.Notice.makeText(context, R.string.megaphone_open, Toast.LENGTH_SHORT).show();
            return;
        }
        MAIN.postDelayed(duck, DUCK_POLL_MS);
        pump = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            run(found);
        }, "outside-speaker");
        pump.start();
        boolean airplay = com.kooo.evcam.airplay.AirPlayService.isRunning();
        StringBuilder names = new StringBuilder();
        for (AudioDeviceInfo device : found) {
            names.append(names.length() == 0 ? "" : ", ").append(device.getAddress());
        }
        com.kooo.evcam.blackbox.BlackBox.noteImportant("Outside speaker: on, speakers " + names
                + ", AirPlay " + (airplay ? "running" : "off"));
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
        com.kooo.evcam.airplay.AirPlayAudio.setOutsideMirror(false, 0f);
        Thread running = pump;
        pump = null;
        if (running != null) {
            try {
                running.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
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
        if (com.kooo.evcam.airplay.AirPlayAudio.setOutsideMirror(true, gain)) {
            sent = gain;
        }
    }

    /** Picked speakers changed: a running pump moves to the new set, and listeners (web) hear of it. */
    public static void speakersChanged(Context context) {
        changed();
        if (on) {
            Context app = context.getApplicationContext();
            MAIN.post(() -> {
                end();
                begin(app);
            });
        }
    }

    /**
     * Pump thread: AirPlay copy to every picked speaker ({@link SpeakerSet}, the megaphone's route).
     * Reopens the speakers when the AirPlay sample rate changes; idles until AirPlay first plays.
     */
    private static void run(List<AudioDeviceInfo> targets) {
        short[] buf = new short[CHUNK_FRAMES];
        SpeakerSet out = null;
        int outRate = 0;
        boolean heard = false;
        try {
            com.kooo.evcam.airplay.AirPlayAudio.dropOutsideMirror();
            while (on) {
                int rate = com.kooo.evcam.airplay.AirPlayAudio.outsideMirrorRate();
                if (rate <= 0) {
                    Thread.sleep(50);
                    continue;
                }
                if (out == null || rate != outRate) {
                    if (out != null) {
                        out.release();
                    }
                    out = SpeakerSet.open(targets, rate, CHUNK_FRAMES * 2 * 4);
                    outRate = rate;
                    com.kooo.evcam.blackbox.BlackBox.noteImportant("Outside speaker: " + rate + " Hz on "
                            + out.describe());
                    if (out.size() == 0) {
                        return;
                    }
                }
                int frames = com.kooo.evcam.airplay.AirPlayAudio.readOutsideMirror(buf);
                if (frames == 0) {
                    Thread.sleep(5);
                    continue;
                }
                if (!heard && !allZero(buf, frames)) {
                    heard = true;
                    com.kooo.evcam.blackbox.BlackBox.note("Outside speaker: AirPlay audio flowing to the speakers");
                }
                if (!out.write(buf, frames)) {
                    com.kooo.evcam.blackbox.BlackBox.noteImportant("Outside speaker: track write failed");
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (out != null) {
                out.release();
            }
            if (on) {
                // the speakers failed: show the switch as off rather than playing nothing
                MAIN.post(OutsideSpeaker::end);
            }
        }
    }

    /** True when every sample is exactly zero. A real song never is, even when quiet. */
    static boolean allZero(short[] pcm, int frames) {
        int n = Math.min(frames, pcm == null ? 0 : pcm.length);
        for (int i = 0; i < n; i++) {
            if (pcm[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static void changed() {
        MAIN.post(() -> {
            for (Runnable listener : listeners) {
                listener.run();
            }
        });
    }
}
