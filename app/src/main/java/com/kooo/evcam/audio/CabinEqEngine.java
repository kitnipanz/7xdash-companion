package com.kooo.evcam.audio;

import android.content.Context;
import android.media.audiofx.DynamicsProcessing;

import com.kooo.evcam.AppLog;

/**
 * Puts the fifteen-band curve on the head unit's output mix (audio session 0),
 * so music already playing in the car's own player is shaped.
 *
 * <p>The car's own equalizer is a second stage. Leave that one flat, or the two
 * curves stack.</p>
 */
public final class CabinEqEngine {

    private static final String TAG = "CabinEq";

    private static DynamicsProcessing effect;

    private CabinEqEngine() {
    }

    /** Apply whatever was saved. Called when the process starts, so the curve survives a screen close. */
    public static void applySaved(Context context) {
        CabinEqStore store = new CabinEqStore(context);
        apply(store.enabled(), store.preset(store.slot()));
    }

    /**
     * @return true when the output mix accepted the curve, or when the equalizer was turned off
     */
    public static synchronized boolean apply(boolean enabled, float[] gains) {
        if (!enabled) {
            release();
            return true;
        }
        float[] curve = CabinEq.copy(gains);
        try {
            if (effect == null) {
                effect = open(curve);
            } else {
                push(effect, curve);
            }
            effect.setEnabled(true);
            return true;
        } catch (RuntimeException e) {
            AppLog.w(TAG, "equalizer refused: " + e);
            release();
            return false;
        }
    }

    public static synchronized void release() {
        if (effect != null) {
            try {
                effect.release();
            } catch (RuntimeException ignored) {
                // already gone
            }
            effect = null;
        }
    }

    private static DynamicsProcessing open(float[] gains) {
        RuntimeException last = null;
        for (int channels : new int[] {2, 1}) {
            try {
                DynamicsProcessing created = new DynamicsProcessing(0, 0, config(channels, gains));
                AppLog.i(TAG, "equalizer on the output mix, " + channels + " ch");
                return created;
            } catch (RuntimeException e) {
                last = e;
            }
        }
        throw last == null ? new IllegalStateException("equalizer") : last;
    }

    private static DynamicsProcessing.Config config(int channels, float[] gains) {
        DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                channels,
                true, CabinEq.BANDS,
                false, 0,
                false, 0,
                false).build();
        float[] edges = CabinEq.cutoffs();
        for (int channel = 0; channel < channels; channel++) {
            DynamicsProcessing.Eq eq = config.getPreEqByChannelIndex(channel);
            eq.setEnabled(true);
            for (int band = 0; band < CabinEq.BANDS; band++) {
                eq.setBand(band, new DynamicsProcessing.EqBand(true, edges[band], gains[band]));
            }
            config.setPreEqByChannelIndex(channel, eq);
        }
        return config;
    }

    private static void push(DynamicsProcessing effect, float[] gains) {
        float[] edges = CabinEq.cutoffs();
        for (int band = 0; band < CabinEq.BANDS; band++) {
            effect.setPreEqBandAllChannelsTo(band,
                    new DynamicsProcessing.EqBand(true, edges[band], gains[band]));
        }
    }
}
