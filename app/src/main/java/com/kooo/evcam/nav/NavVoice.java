package com.kooo.evcam.nav;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.announce.OfflineVoice;
import com.kooo.evcam.announce.Wav;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Speaks the turns in the cabin, as navigation guidance: the car lowers the music while it talks.
 * The head unit's own speech engine first; for English, when it has no English voice, 7xDash's
 * offline voice (if downloaded, see Announcements). Thai needs a Thai voice on the head unit.
 */
final class NavVoice {

    private static final String TAG = "NavVoice";
    private static final AudioAttributes GUIDANCE = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();

    private final Context context;
    private final AudioManager audio;
    private final AudioFocusRequest focus;
    private final ExecutorService offline = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nav-voice");
        t.setDaemon(true);
        return t;
    });
    private TextToSpeech tts;
    /** Null until the engine has started; then whether it can speak our language. */
    private Boolean ttsSpeaks;
    private String pending;

    NavVoice(Context context) {
        this.context = context.getApplicationContext();
        audio = this.context.getSystemService(AudioManager.class);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(GUIDANCE)
                .build();
    }

    /** Main thread. Says it now, cutting off anything still being said. */
    void say(String text, Locale locale) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (tts == null) {
            pending = text;
            tts = new TextToSpeech(context, status -> onInit(status, locale));
            return;
        }
        if (ttsSpeaks == null) {
            pending = text;
            return;
        }
        if (ttsSpeaks) {
            speakTts(text);
        } else if (!"th".equals(locale.getLanguage())) {
            speakOffline(text);
        } else {
            AppLog.w(TAG, "no Thai voice on this head unit");
        }
    }

    private void onInit(int status, Locale locale) {
        boolean ok = false;
        if (status == TextToSpeech.SUCCESS && tts != null) {
            int available = tts.setLanguage(locale);
            ok = available >= TextToSpeech.LANG_AVAILABLE;
            if (ok) {
                tts.setAudioAttributes(GUIDANCE);
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String id) {
                    }

                    @Override
                    public void onDone(String id) {
                        audio.abandonAudioFocusRequest(focus);
                    }

                    @Override
                    public void onError(String id) {
                        audio.abandonAudioFocusRequest(focus);
                    }
                });
            }
            AppLog.i(TAG, "speech engine " + tts.getDefaultEngine() + ": " + locale + " = " + available);
        } else {
            AppLog.w(TAG, "speech engine did not start: " + status);
        }
        ttsSpeaks = ok;
        String text = pending;
        pending = null;
        if (text != null) {
            say(text, locale);
        }
    }

    private void speakTts(String text) {
        audio.requestAudioFocus(focus);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, new Bundle(), "nav-" + System.nanoTime());
    }

    private void speakOffline(String text) {
        if (!OfflineVoice.isReady(context)) {
            AppLog.w(TAG, "no English voice: download it in Announcements");
            return;
        }
        offline.execute(() -> {
            Wav wav = OfflineVoice.speak(context, text);
            if (wav == null) {
                return;
            }
            play(wav);
        });
    }

    /** Worker thread: the whole phrase through one static track, with focus held while it plays. */
    private void play(Wav wav) {
        int bytes = wav.mono.length * 2;
        AudioTrack track = null;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(GUIDANCE)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(wav.rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(bytes)
                    .build();
            track.write(wav.mono, 0, wav.mono.length);
            audio.requestAudioFocus(focus);
            track.play();
            Thread.sleep(wav.mono.length * 1000L / Math.max(1, wav.rate) + 200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            AppLog.w(TAG, "offline voice did not play: " + e.getMessage());
        } finally {
            if (track != null) {
                track.release();
            }
            audio.abandonAudioFocusRequest(focus);
        }
    }

    void stop() {
        if (tts != null) {
            tts.stop();
        }
        audio.abandonAudioFocusRequest(focus);
    }

    void shutdown() {
        if (tts != null) {
            tts.shutdown();
            tts = null;
            ttsSpeaks = null;
        }
        audio.abandonAudioFocusRequest(focus);
    }
}
