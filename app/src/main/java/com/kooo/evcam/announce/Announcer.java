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
 * Plays announcements on every speaker picked for the megaphone, at the megaphone volume. A phrase
 * plays the driver's own recording when there is one, else goes through text-to-speech into a WAV
 * first, since speech cannot be sent to a chosen speaker directly; sounds are made in code. One at a time: a new one cuts the last one off.
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
    /** Started speech engines by package ("" is the system default); null for one that failed to start. */
    private static final java.util.Map<String, TextToSpeech> ENGINES = new java.util.HashMap<>();
    private static volatile boolean enginesLogged;

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
            AnnouncementStore store = new AnnouncementStore(context);
            Wav wav = store.hasRecording(a.id) ? read(store.recording(a.id))
                    : !a.isThai() && OfflineVoice.isReady(context) ? OfflineVoice.speak(context, a.text) : null;
            if (wav == null) {
                wav = speak(context, a);
            }
            if (wav == null) {
                return a.isThai() ? R.string.announce_record_needed : R.string.announce_no_voice;
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

    /** Worker thread. The phrase as a WAV from the first speech engine with a voice for it, or null. */
    private static Wav speak(Context context, Announcement a) {
        File cache = new File(context.getCacheDir(), "announce");
        if (!cache.isDirectory() && !cache.mkdirs()) {
            return null;
        }
        // Same name as 1.3.0-1.3.2 gave English phrases, so their cached speech is kept
        File file = new File(cache, Integer.toHexString((a.text + "false").hashCode()) + ".wav");
        if (file.isFile() && file.length() > 44) {
            return read(file);
        }
        if (a.isThai()) {
            // No voice reads Thai on this car: Thai plays only when recorded
            BlackBox.note("Announcement: Thai phrase not recorded: \"" + a.text + "\"");
            return null;
        }
        Locale[] wanted = ENGLISH;
        StringBuilder tried = new StringBuilder();
        for (String engine : engines(context)) {
            TextToSpeech engineTts = engine(context, engine);
            if (engineTts == null) {
                tried.append(engine).append(" did not start; ");
                continue;
            }
            Locale locale = voice(engineTts, wanted, tried.append(engine).append(':'));
            tried.append("; ");
            if (locale == null) {
                continue;
            }
            Wav wav = synthesize(engineTts, locale, a.text, cache, file);
            if (wav != null) {
                return wav;
            }
            tried.append(engine).append(" wrote no audio; ");
        }
        BlackBox.note("Announcement: no voice for \"" + a.text + "\". Tried " + tried);
        return null;
    }

    private static final Locale[] ENGLISH = {Locale.US, Locale.UK, Locale.ENGLISH, new Locale("en", "AU")};

    /** First locale the engine can speak, noting each answer (1+ yes, -1 missing data, -2 unsupported). */
    private static Locale voice(TextToSpeech engineTts, Locale[] wanted, StringBuilder note) {
        for (Locale locale : wanted) {
            int available = engineTts.isLanguageAvailable(locale);
            note.append(' ').append(locale).append('=').append(available);
            if (available >= TextToSpeech.LANG_AVAILABLE) {
                return locale;
            }
        }
        return null;
    }

    private static Wav synthesize(TextToSpeech engineTts, Locale locale, String text, File cache, File file) {
        engineTts.setLanguage(locale);
        CountDownLatch done = new CountDownLatch(1);
        String utterance = "announce-" + System.nanoTime();
        engineTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
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
        int queued = engineTts.synthesizeToFile(text, new Bundle(), part, utterance);
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

    /**
     * Worker thread. Installed speech engines, the default first. The list is written to the black box
     * once, so a car with no usable voice shows what it does have.
     */
    private static List<String> engines(Context context) {
        List<String> out = new java.util.ArrayList<>();
        TextToSpeech probe = engine(context, "");
        if (probe == null) {
            if (!enginesLogged) {
                enginesLogged = true;
                BlackBox.note("Announcement: no text-to-speech engine started on this head unit");
            }
            return out;
        }
        String preferred = probe.getDefaultEngine();
        if (preferred != null && !preferred.isEmpty()) {
            out.add(preferred);
        }
        StringBuilder list = new StringBuilder();
        for (TextToSpeech.EngineInfo info : probe.getEngines()) {
            list.append(list.length() == 0 ? "" : ", ").append(info.name).append(" (").append(info.label).append(')');
            if (!out.contains(info.name)) {
                out.add(info.name);
            }
        }
        if (!enginesLogged) {
            enginesLogged = true;
            BlackBox.note("Announcement: text-to-speech engines [" + list + "], default " + preferred);
        }
        return out;
    }

    /** Worker thread. Starts that engine once ("" for the system default) and waits for it; null when it fails. */
    private static TextToSpeech engine(Context context, String name) {
        synchronized (ENGINES) {
            if (ENGINES.containsKey(name)) {
                return ENGINES.get(name);
            }
        }
        CountDownLatch ready = new CountDownLatch(1);
        boolean[] ok = new boolean[1];
        TextToSpeech[] made = new TextToSpeech[1];
        MAIN.post(() -> {
            TextToSpeech.OnInitListener done = status -> {
                ok[0] = status == TextToSpeech.SUCCESS;
                ready.countDown();
            };
            made[0] = name.isEmpty() ? new TextToSpeech(context, done) : new TextToSpeech(context, done, name);
        });
        try {
            ready.await(TTS_WAIT_S, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        TextToSpeech result = ok[0] ? made[0] : null;
        synchronized (ENGINES) {
            ENGINES.put(name, result);
        }
        return result;
    }
}
