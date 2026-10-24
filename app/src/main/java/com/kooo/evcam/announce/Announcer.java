package com.kooo.evcam.announce;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.widget.Toast;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.input.SpeakPlan;
import com.kooo.evcam.input.SpeakerSet;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Plays announcements on every speaker picked for the megaphone, at the megaphone volume. Phrases
 * go through the head unit's text-to-speech into a WAV first, since speech cannot be sent to a
 * chosen speaker directly; sounds are made in code. One at a time: a new one cuts the last one off.
 * While one plays, the outside speaker's AirPlay music drops as it does for the megaphone.
 */
public final class Announcer {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "announcer");
        t.setPriority(Thread.MAX_PRIORITY);
        return t;
    });
    private static final int CHUNK = 960;
    private static final long TTS_WAIT_S = 8;

    private static volatile boolean playing;
    /** Bumped by every play and stop: a running playback stops when it no longer matches. */
    private static volatile int generation;
    private static TextToSpeech tts;
    private static volatile boolean ttsReady;

    private Announcer() {
    }

    public static boolean isPlaying() {
        return playing;
    }

    public static void stop() {
        generation++;
    }

    /** {@code why} goes to the black box: "picker", "settings", "sentry", "web"… */
    public static void play(Context context, Announcement announcement, String why) {
        if (context == null || announcement == null) {
            return;
        }
        Context app = context.getApplicationContext();
        int mine = ++generation;
        WORKER.execute(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            int failure = run(app, announcement, why, mine);
            if (failure != 0) {
                MAIN.post(() -> com.kooo.evcam.ui.Notice.makeText(app, failure, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private static int run(Context context, Announcement a, String why, int mine) {
        int rate;
        short[] pcm;
        if (a.isSound()) {
            pcm = Tones.render(a.sound);
            rate = Tones.RATE;
        } else {
            Wav wav = speak(context, a);
            if (wav == null) {
                return R.string.announce_no_voice;
            }
            pcm = wav.mono;
            rate = wav.rate;
        }
        if (pcm == null || mine != generation) {
            return 0;
        }
        AppConfig config = new AppConfig(context);
        List<AudioDeviceInfo> devices = SpeakerSet.find(context.getSystemService(AudioManager.class),
                config.getSpeakSpeakers());
        if (devices.isEmpty()) {
            return R.string.megaphone_no_speaker;
        }
        SpeakerSet out = SpeakerSet.open(devices, rate, CHUNK * 2 * 4);
        BlackBox.note("Announcement (" + why + "): " + (a.isSound() ? "sound " + a.sound : "\"" + a.text + "\"")
                + " on " + out.describe());
        if (out.size() == 0) {
            out.release();
            return R.string.megaphone_route;
        }
        playing = true;
        try {
            out.setVolume(SpeakPlan.volume(config.getSpeakVolume()) / 100f);
            for (int at = 0; at < pcm.length && mine == generation; at += CHUNK) {
                int n = Math.min(CHUNK, pcm.length - at);
                short[] chunk = new short[n];
                System.arraycopy(pcm, at, chunk, 0, n);
                if (!out.write(chunk, n)) {
                    break;
                }
            }
            if (mine == generation) {
                // let the last buffer play out before the tracks go
                Thread.sleep(300);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            out.release();
            playing = false;
        }
        return 0;
    }

    /** Worker thread. The phrase as a WAV from the head unit's speech engine, or null. */
    private static Wav speak(Context context, Announcement a) {
        File cache = new File(context.getCacheDir(), "announce");
        if (!cache.isDirectory() && !cache.mkdirs()) {
            return null;
        }
        File file = new File(cache, Integer.toHexString((a.text + a.isThai()).hashCode()) + ".wav");
        if (file.isFile() && file.length() > 44) {
            return read(file);
        }
        if (!ensureTts(context)) {
            BlackBox.note("Announcement: text-to-speech did not start");
            return null;
        }
        Locale locale = a.isThai() ? new Locale("th", "TH") : Locale.US;
        int available = tts.isLanguageAvailable(locale);
        if (available < TextToSpeech.LANG_AVAILABLE) {
            BlackBox.note("Announcement: no " + locale + " voice (" + available + ")");
            return null;
        }
        tts.setLanguage(locale);
        CountDownLatch done = new CountDownLatch(1);
        String utterance = "announce-" + System.nanoTime();
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String id) {
            }

            @Override
            public void onDone(String id) {
                done.countDown();
            }

            @Override
            public void onError(String id) {
                done.countDown();
            }
        });
        File part = new File(cache, file.getName() + ".part");
        int queued = tts.synthesizeToFile(a.text, new Bundle(), part, utterance);
        try {
            if (queued != TextToSpeech.SUCCESS || !done.await(TTS_WAIT_S, TimeUnit.SECONDS)) {
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        Wav wav = read(part);
        if (wav != null && !part.renameTo(file)) {
            part.delete();
        }
        return wav;
    }

    private static Wav read(File file) {
        try {
            return Wav.parse(Files.readAllBytes(file.toPath()));
        } catch (Exception e) {
            return null;
        }
    }

    /** Worker thread. Starts the speech engine once and waits for it. */
    private static boolean ensureTts(Context context) {
        if (tts != null && ttsReady) {
            return true;
        }
        CountDownLatch ready = new CountDownLatch(1);
        boolean[] ok = new boolean[1];
        MAIN.post(() -> tts = new TextToSpeech(context, status -> {
            ok[0] = status == TextToSpeech.SUCCESS;
            ready.countDown();
        }));
        try {
            ready.await(TTS_WAIT_S, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        ttsReady = ok[0];
        return ttsReady;
    }
}
