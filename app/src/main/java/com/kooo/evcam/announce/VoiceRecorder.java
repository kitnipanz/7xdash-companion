package com.kooo.evcam.announce;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;

import java.io.File;
import java.nio.file.Files;

/**
 * Records the driver saying a phrase with the cabin mic (the one the megaphone uses), so the phrase
 * plays in their own voice when the head unit has none. Mono 16-bit at {@link #RATE}, at most
 * {@link #MAX_SECONDS}, raised to a usable level when saved.
 */
final class VoiceRecorder {

    static final int RATE = 48_000;
    static final int MAX_SECONDS = 20;
    /** The cabin mic is quiet: the megaphone multiplies it by 20. */
    private static final float MAX_GAIN = 30f;

    interface Level {
        /** Pump thread: loudest sample of the last chunk, 0–100. */
        void onLevel(int percent, int elapsedMs);
    }

    private final short[] pcm = new short[RATE * MAX_SECONDS];
    private volatile boolean go;
    private volatile int frames;
    private Thread worker;

    /** False when the mic would not open (permission, or another app holds it). */
    boolean start(Level level) {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            return false;
        }
        AudioRecord record;
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 4096) * 2);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                record.release();
                return false;
            }
            record.startRecording();
        } catch (RuntimeException e) {
            return false;
        }
        go = true;
        frames = 0;
        worker = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            short[] chunk = new short[RATE / 20];
            try {
                while (go && frames < pcm.length) {
                    int n = record.read(chunk, 0, Math.min(chunk.length, pcm.length - frames));
                    if (n <= 0) {
                        break;
                    }
                    System.arraycopy(chunk, 0, pcm, frames, n);
                    frames += n;
                    int peak = 0;
                    for (int i = 0; i < n; i++) {
                        peak = Math.max(peak, Math.abs((int) chunk[i]));
                    }
                    if (level != null) {
                        level.onLevel(Math.min(100, peak * 100 * 20 / 32767), frames * 1000 / RATE);
                    }
                }
            } finally {
                go = false;
                try {
                    record.stop();
                } catch (IllegalStateException ignored) {
                    // never started
                }
                record.release();
            }
        }, "announce-record");
        worker.start();
        return true;
    }

    boolean isRecording() {
        return go;
    }

    /** Stops and waits for the mic to close. */
    void stop() {
        go = false;
        Thread running = worker;
        if (running != null) {
            try {
                running.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Stops, raises the level and saves; false when under half a second was heard or the write failed. */
    boolean saveTo(File file) {
        stop();
        int n = frames;
        if (n < RATE / 2) {
            return false;
        }
        Wav.normalize(pcm, n, MAX_GAIN);
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return false;
            }
            Files.write(file.toPath(), Wav.write(RATE, pcm, n));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
