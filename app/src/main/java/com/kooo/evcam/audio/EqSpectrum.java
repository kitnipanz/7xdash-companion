package com.kooo.evcam.audio;

import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.Looper;

import com.kooo.evcam.AppLog;

/**
 * Live spectrum of the car's output mix, cut into the equalizer's fifteen bands.
 *
 * <p>Uses the system Visualizer on session 0. It needs the record permission, but only reads
 * the levels of what is already playing; nothing is recorded or kept. Some head units refuse it,
 * then {@link #start} returns false.</p>
 */
public final class EqSpectrum {

    public interface Listener {
        /** Main thread: 0..1 per band. */
        void onLevels(float[] levels);
    }

    private static final String TAG = "EqSpectrum";
    /** Shown range: this many dB below full scale is the bottom of a bar. */
    private static final float FLOOR_DB = 66f;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Visualizer visualizer;
    private final float[] smooth = new float[CabinEq.BANDS];

    /** False when the head unit refuses (no permission, or no Visualizer on the mix). */
    public synchronized boolean start(Listener listener) {
        stop();
        try {
            Visualizer v = new Visualizer(CabinEqEngine.MIX_SESSION);
            int[] range = Visualizer.getCaptureSizeRange();
            v.setCaptureSize(Math.min(range[1], 2048));
            v.setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED);
            int rate = Math.min(Visualizer.getMaxCaptureRate(), 25000);
            v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer vis, byte[] waveform, int samplingRate) {
                }

                @Override
                public void onFftDataCapture(Visualizer vis, byte[] fft, int samplingRate) {
                    float[] levels = bands(fft, samplingRate / 1000f);
                    main.post(() -> listener.onLevels(levels));
                }
            }, rate, false, true);
            v.setEnabled(true);
            visualizer = v;
            return true;
        } catch (RuntimeException e) {
            AppLog.w(TAG, "spectrum refused: " + e);
            stop();
            return false;
        }
    }

    public synchronized void stop() {
        if (visualizer != null) {
            try {
                visualizer.setEnabled(false);
                visualizer.release();
            } catch (RuntimeException ignored) {
                // already gone
            }
            visualizer = null;
        }
    }

    /**
     * Power in each band from Visualizer FFT bytes ({@code fft[2k]}, {@code fft[2k+1]} = real,
     * imaginary of bin k), as 0..1 on a dB scale, rising fast and falling slowly.
     */
    float[] bands(byte[] fft, float sampleRateHz) {
        int bins = fft.length / 2;
        float binHz = sampleRateHz / 2f / bins;
        float[] edges = CabinEq.cutoffs();
        float[] out = new float[CabinEq.BANDS];
        float lower = 18f;
        for (int b = 0; b < CabinEq.BANDS; b++) {
            float upper = edges[b];
            double power = 0;
            int from = Math.max(1, (int) Math.floor(lower / binHz));
            int to = Math.max(from, Math.min(bins - 1, (int) Math.ceil(upper / binHz)));
            for (int k = from; k <= to; k++) {
                double re = fft[2 * k];
                double im = fft[2 * k + 1];
                power += re * re + im * im;
            }
            // full scale is about 128 per bin in these bytes
            double db = 10 * Math.log10(power / (128.0 * 128.0) + 1e-9);
            float level = (float) Math.max(0, Math.min(1, (db + FLOOR_DB) / FLOOR_DB));
            smooth[b] = level > smooth[b] ? level : smooth[b] * 0.82f + level * 0.18f;
            out[b] = smooth[b];
            lower = upper;
        }
        return out;
    }
}
