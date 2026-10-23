package com.kooo.evcam.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Process;

import androidx.annotation.RequiresApi;

import com.kooo.evcam.R;

/**
 * Beta: what the head unit plays (music, radio, AirPlay) copied to the outside bus.
 * Playback capture hears every app that allows it, this one included, so the outside track
 * refuses capture itself or it would hear its own echo.
 */
@RequiresApi(api = Build.VERSION_CODES.Q)
final class OutsideSpeaker {

    private static final int RATE = 48_000;
    /** 10 ms of 16-bit stereo. */
    private static final int CHUNK_BYTES = 480 * 2 * 2;
    static final int ENDED = 0;
    /** While the megaphone talks on the same bus, the music drops to this share. */
    static final float DUCK = 0.15f;
    /**
     * Capture that stays exactly zero this long while something plays is a block, not a quiet song:
     * Android hands back silence, not an error, for players that refuse capture (the car's own
     * CarPlay / phone link app is privileged and refuses by default).
     */
    static final long SILENT_MS = 6_000L;

    private final Object lock = new Object();
    private volatile boolean go;
    private volatile float gain = 1f;
    private volatile AudioRecord capture;
    private Thread worker;

    interface Ended {
        /** {@code failure} is a string resource, or {@link #ENDED} after {@link #end()}. */
        void onEnded(int failure);
    }

    interface Ducking {
        boolean now();
    }

    /** Volume slider (0–100) to track gain. Squared so the low half of the slider is usable. */
    static float gain(int percent) {
        int p = Math.max(0, Math.min(100, percent));
        float linear = p / 100f;
        return linear * linear;
    }

    /**
     * 16-bit little-endian stereo to mono, average of the two sides. Returns the bytes
     * written to {@code mono}: half of {@code length}, rounded down to whole frames.
     */
    static int downmix(byte[] stereo, int length, byte[] mono) {
        if (stereo == null || mono == null || length < 4) {
            return 0;
        }
        int frames = Math.min(Math.min(length, stereo.length) / 4, mono.length / 2);
        for (int f = 0; f < frames; f++) {
            int i = f * 4;
            int left = (short) ((stereo[i] & 0xFF) | (stereo[i + 1] << 8));
            int right = (short) ((stereo[i + 2] & 0xFF) | (stereo[i + 3] << 8));
            int mixed = (left + right) / 2;
            mono[f * 2] = (byte) mixed;
            mono[f * 2 + 1] = (byte) (mixed >> 8);
        }
        return frames * 2;
    }

    /** True when every 16-bit sample is exactly zero. A real song never is, even when quiet. */
    static boolean allZero(byte[] pcm, int length) {
        if (pcm == null) {
            return true;
        }
        int n = Math.min(length, pcm.length);
        for (int i = 0; i < n; i++) {
            if (pcm[i] != 0) {
                return false;
            }
        }
        return true;
    }

    void setVolume(int percent) {
        gain = gain(percent);
    }

    boolean isGoing() {
        return go;
    }

    /** {@code onEnded} runs on the pump thread, once, when the pump stops for any reason. */
    void begin(Context context, MediaProjection projection, String speaker, int volume,
               Ducking ducking, Ended onEnded) {
        synchronized (lock) {
            if (go) {
                return;
            }
            go = true;
            gain = gain(volume);
            Context app = context.getApplicationContext();
            worker = new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
                int failure = run(app, projection, speaker, ducking);
                synchronized (lock) {
                    go = false;
                    worker = null;
                }
                if (onEnded != null) {
                    onEnded.onEnded(failure);
                }
            }, "outside-speaker");
            worker.start();
        }
    }

    void end() {
        Thread running;
        AudioRecord live;
        synchronized (lock) {
            go = false;
            running = worker;
            live = capture;
        }
        if (live != null) {
            try {
                live.stop();
            } catch (IllegalStateException ignored) {
                // read() unblocks once stop lands
            }
        }
        if (running != null && Thread.currentThread() != running) {
            try {
                running.join(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private int run(Context context, MediaProjection projection, String speaker, Ducking ducking) {
        AudioManager manager = context.getSystemService(AudioManager.class);
        if (manager == null) {
            return R.string.megaphone_no_speaker;
        }
        AudioDeviceInfo device = com.kooo.evcam.input.Megaphone.find(manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS), speaker);
        if (device == null) {
            return R.string.megaphone_no_speaker;
        }
        int inMin = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        int outMin = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (inMin <= 0 || outMin <= 0) {
            return R.string.outside_speaker_open;
        }
        AudioRecord record = null;
        AudioTrack track = null;
        try {
            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            record = new AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                            .build())
                    .setBufferSizeInBytes(Math.max(inMin, CHUNK_BYTES) * 2)
                    .build();
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                return R.string.outside_speaker_open;
            }
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(Math.max(outMin, CHUNK_BYTES))
                    .build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                return R.string.outside_speaker_open;
            }
            if (!track.setPreferredDevice(device)) {
                return R.string.megaphone_route;
            }
            capture = record;
            record.startRecording();
            track.play();
            byte[] in = new byte[CHUNK_BYTES];
            byte[] out = new byte[CHUNK_BYTES / 2];
            float applied = -1f;
            long silentSince = android.os.SystemClock.elapsedRealtime();
            boolean heard = false;
            boolean reported = false;
            while (go) {
                int read = record.read(in, 0, in.length);
                if (read < 0) {
                    return go ? R.string.outside_speaker_open : ENDED;
                }
                if (read == 0) {
                    continue;
                }
                float want = gain * (ducking != null && ducking.now() ? DUCK : 1f);
                if (want != applied) {
                    track.setVolume(want);
                    applied = want;
                }
                if (!allZero(in, read)) {
                    if (!heard) {
                        heard = true;
                        com.kooo.evcam.blackbox.BlackBox.note("Outside speaker: hearing head unit audio");
                    }
                    silentSince = android.os.SystemClock.elapsedRealtime();
                } else if (!reported && android.os.SystemClock.elapsedRealtime() - silentSince >= SILENT_MS
                        && (ducking == null || !ducking.now())) {
                    reported = true;
                    reportSilence(context, manager);
                }
                int mono = downmix(in, read, out);
                if (mono > 0 && track.write(out, 0, mono) < 0) {
                    return R.string.outside_speaker_open;
                }
            }
            return ENDED;
        } catch (SecurityException | UnsupportedOperationException e) {
            return R.string.outside_speaker_refused;
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.string.outside_speaker_open;
        } finally {
            capture = null;
            release(record, track);
        }
    }

    /**
     * Writes every active player's usage and capture policy to the black box, and tells the driver
     * when another player is going but nothing reaches us. Our own track (capture refused) is one of them.
     */
    private static void reportSilence(Context context, AudioManager manager) {
        java.util.List<android.media.AudioPlaybackConfiguration> players;
        try {
            players = manager.getActivePlaybackConfigurations();
        } catch (RuntimeException e) {
            players = java.util.Collections.emptyList();
        }
        StringBuilder line = new StringBuilder("Outside speaker: silent for ").append(SILENT_MS / 1000)
                .append(" s, active players: ");
        int others = 0;
        for (android.media.AudioPlaybackConfiguration player : players) {
            AudioAttributes attributes = player.getAudioAttributes();
            int policy = attributes.getAllowedCapturePolicy();
            line.append("[usage=").append(attributes.getUsage())
                    .append(" content=").append(attributes.getContentType())
                    .append(" capture=").append(policyName(policy)).append("] ");
            if (policy != AudioAttributes.ALLOW_CAPTURE_BY_NONE) {
                others++;
            }
        }
        com.kooo.evcam.blackbox.BlackBox.noteImportant(line.toString());
        if (others > 0) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                    com.kooo.evcam.ui.Notice.makeText(context, R.string.outside_speaker_blocked,
                            android.widget.Toast.LENGTH_LONG).show());
        }
    }

    private static String policyName(int policy) {
        switch (policy) {
            case AudioAttributes.ALLOW_CAPTURE_BY_ALL:
                return "all";
            case AudioAttributes.ALLOW_CAPTURE_BY_SYSTEM:
                return "system";
            case AudioAttributes.ALLOW_CAPTURE_BY_NONE:
                return "none";
            default:
                return Integer.toString(policy);
        }
    }

    private static void release(AudioRecord record, AudioTrack track) {
        if (record != null) {
            try {
                record.stop();
            } catch (IllegalStateException ignored) {
                // already stopped to unblock read
            }
            record.release();
        }
        if (track != null) {
            try {
                track.pause();
                track.flush();
            } catch (IllegalStateException ignored) {
                // not playing yet
            }
            track.release();
        }
    }
}
