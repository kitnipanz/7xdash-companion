package com.kooo.evcam.input;

import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.kooo.evcam.R;

/**
 * Live mic to every picked speaker at once (the outside bus by default). The numeric device id
 * changes; the address does not. A speaker that refuses the preferred-device call is skipped, never
 * swapped for the cabin speakers; with none left the megaphone does not start.
 */
public final class Megaphone {

    static final String BUS_ADDRESS = "BUS12_OUTER_NOTIFY";
    /** Cabin mic peaks around 1–2% of full scale. The probe tone that this bus played was about 20%. */
    static final int GAIN = 20;
    private static final int RATE = 48_000;
    private static final int CHUNK_BYTES = 960 * 2;
    private static final long MAX_MS = 60_000L;
    private static final int ENDED = 0;
    private static final int TIMED_OUT = -1;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Object lock = new Object();
    private volatile boolean go;
    private volatile AudioRecord mic;
    private Thread worker;

    public static boolean isOuterNotify(int type, String address) {
        return type == AudioDeviceInfo.TYPE_BUS && BUS_ADDRESS.equals(address);
    }

    public static AudioDeviceInfo find(AudioDeviceInfo[] devices, String address) {
        if (devices == null || address == null || address.isEmpty()) {
            return null;
        }
        for (AudioDeviceInfo device : devices) {
            if (device != null && device.isSink() && address.equals(device.getAddress())) {
                return device;
            }
        }
        return null;
    }

    /** Peak of a 16-bit little-endian chunk, 0 when silent and 100 at full scale. */
    static int percent(byte[] pcm, int length) {
        if (pcm == null || length < 2) {
            return 0;
        }
        int n = Math.min(length, pcm.length) & ~1;
        int peak = 0;
        for (int i = 0; i < n; i += 2) {
            int sample = (pcm[i] & 0xFF) | (pcm[i + 1] << 8);
            int abs = sample == Short.MIN_VALUE ? 32768 : Math.abs(sample);
            if (abs > peak) {
                peak = abs;
            }
        }
        if (peak >= 32767) {
            return 100;
        }
        return peak * 100 / 32767;
    }

    /** Amplifies in place and returns the level that will actually be written to the bus. */
    static int boost(byte[] pcm, int length, int gain) {
        if (pcm == null || length < 2) {
            return 0;
        }
        if (gain <= 0) {
            int silent = Math.min(length, pcm.length);
            for (int i = 0; i < silent; i++) {
                pcm[i] = 0;
            }
            return 0;
        }
        int n = Math.min(length, pcm.length) & ~1;
        int peak = 0;
        for (int i = 0; i < n; i += 2) {
            int sample = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            int amplified = sample * gain;
            if (amplified > 32767) {
                amplified = 32767;
            } else if (amplified < -32768) {
                amplified = -32768;
            }
            pcm[i] = (byte) amplified;
            pcm[i + 1] = (byte) (amplified >> 8);
            int abs = amplified == -32768 ? 32768 : Math.abs(amplified);
            if (abs > peak) {
                peak = abs;
            }
        }
        if (peak >= 32767) {
            return 100;
        }
        return peak * 100 / 32767;
    }

    /** Starts the pump. {@code onEnded} runs on the main thread after a failure, or not at all on success. */
    void begin(android.content.Context context, Runnable onEnded, Level level) {
        synchronized (lock) {
            if (go) {
                return;
            }
            go = true;
            android.content.Context app = context.getApplicationContext();
            com.kooo.evcam.AppConfig config = new com.kooo.evcam.AppConfig(app);
            java.util.List<String> speakers = config.getSpeakSpeakers();
            int gain = SpeakPlan.gain(config.getSpeakVolume());
            worker = new Thread(() -> {
                int failure = run(app, level, speakers, gain);
                synchronized (lock) {
                    go = false;
                    worker = null;
                }
                if (failure == TIMED_OUT) {
                    MAIN.post(() -> {
                        if (onEnded != null) {
                            onEnded.run();
                        }
                    });
                } else if (failure != ENDED) {
                    MAIN.post(() -> {
                        com.kooo.evcam.ui.Notice.makeText(app, failure, android.widget.Toast.LENGTH_SHORT).show();
                        if (onEnded != null) {
                            onEnded.run();
                        }
                    });
                }
            }, "megaphone");
            worker.start();
        }
    }

    void end() {
        Thread running;
        AudioRecord live;
        synchronized (lock) {
            go = false;
            running = worker;
            live = mic;
        }
        if (live != null) {
            try {
                live.stop();
            } catch (IllegalStateException ignored) {
                // read() unblocks once stop lands, even before startRecording
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

    boolean isGoing() {
        return go;
    }

    private int run(android.content.Context context, Level level, java.util.List<String> speakers, int gain) {
        AudioManager manager = context.getSystemService(AudioManager.class);
        if (manager == null) {
            return R.string.megaphone_no_speaker;
        }
        java.util.List<AudioDeviceInfo> devices = SpeakerSet.find(manager, speakers);
        if (devices.isEmpty()) {
            return R.string.megaphone_no_speaker;
        }
        int inMin = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (inMin <= 0) {
            return R.string.megaphone_open;
        }
        AudioRecord record = null;
        SpeakerSet out = null;
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(inMin, CHUNK_BYTES) * 2);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                return R.string.megaphone_open;
            }
            out = SpeakerSet.open(devices, RATE, CHUNK_BYTES);
            if (out.size() == 0) {
                com.kooo.evcam.blackbox.BlackBox.note("Megaphone: no speaker took a track: " + out.describe());
                return R.string.megaphone_route;
            }
            if (devices.size() > 1 || out.size() < devices.size()) {
                com.kooo.evcam.blackbox.BlackBox.note("Megaphone: " + out.describe());
            }
            mic = record;
            record.startRecording();
            byte[] buf = new byte[CHUNK_BYTES];
            long deadline = SystemClock.elapsedRealtime() + MAX_MS;
            long lastLevel = 0L;
            while (go && SystemClock.elapsedRealtime() < deadline) {
                int read = record.read(buf, 0, buf.length, AudioRecord.READ_NON_BLOCKING);
                if (read < 0) {
                    return R.string.megaphone_open;
                }
                if (read == 0) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        return ENDED;
                    }
                    continue;
                }
                int heard = boost(buf, read, gain);
                if (!out.write(buf, read)) {
                    return R.string.megaphone_open;
                }
                long now = SystemClock.uptimeMillis();
                if (level != null && now - lastLevel >= 50L) {
                    lastLevel = now;
                    MAIN.post(() -> {
                        if (go) {
                            level.onPercent(heard);
                        }
                    });
                }
            }
            return go ? TIMED_OUT : ENDED;
        } catch (SecurityException e) {
            return R.string.megaphone_need_mic;
        } catch (IllegalArgumentException | IllegalStateException e) {
            return R.string.megaphone_open;
        } finally {
            mic = null;
            release(record);
            if (out != null) {
                out.release();
            }
        }
    }

    private static void release(AudioRecord record) {
        if (record != null) {
            try {
                record.stop();
            } catch (IllegalStateException ignored) {
                // already stopped to unblock read
            }
            record.release();
        }
    }

    interface Level {
        void onPercent(int percent);
    }
}
