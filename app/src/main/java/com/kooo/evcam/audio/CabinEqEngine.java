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
 *
 * <p>Sound quality: the curve is spread over thirty blended bands (no staircase between
 * sliders), the input is lowered by the biggest boost so loud tracks do not clip, and a
 * soft limiter just under full scale catches the rest.</p>
 */
public final class CabinEqEngine {

    private static final String TAG = "CabinEq";

    private static DynamicsProcessing effect;

    private CabinEqEngine() {
    }

    /** Apply whatever was saved. Called when the process starts, so the curve survives a screen close. */
    public static void applySaved(Context context) {
        CabinEqStore store = new CabinEqStore(context);
        apply(store.enabled(), store.currentGains());
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
                AppLog.i(TAG, "equalizer on the output mix, " + channels + " ch, "
                        + CabinEq.ENGINE_BANDS + " bands");
                return created;
            } catch (RuntimeException e) {
                last = e;
            }
        }
        throw last == null ? new IllegalStateException("equalizer") : last;
    }

    /**
     * Pre-EQ with thirty blended bands, the input turned down by the biggest boost (headroom),
     * and a gentle limiter just under full scale to catch what still gets through.
     * FAVOR_TIME_RESOLUTION keeps latency and smearing low on music.
     */
    private static DynamicsProcessing.Config config(int channels, float[] gains) {
        DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION,
                channels,
                true, CabinEq.ENGINE_BANDS,
                false, 0,
                false, 0,
                true).build();
        float[] edges = CabinEq.engineCutoffs();
        float[] curve = CabinEq.engineGains(gains);
        for (int channel = 0; channel < channels; channel++) {
            DynamicsProcessing.Eq eq = config.getPreEqByChannelIndex(channel);
            eq.setEnabled(true);
            for (int band = 0; band < CabinEq.ENGINE_BANDS; band++) {
                eq.setBand(band, new DynamicsProcessing.EqBand(true, edges[band], curve[band]));
            }
            config.setPreEqByChannelIndex(channel, eq);
            config.setInputGainByChannelIndex(channel, -CabinEq.headroomDb(gains));
            config.setLimiterByChannelIndex(channel, limiter());
        }
        return config;
    }

    /** Soft safety limiter at -1 dBFS: fast attack, quick release, no make-up gain. */
    private static DynamicsProcessing.Limiter limiter() {
        return new DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f);
    }

    private static void push(DynamicsProcessing effect, float[] gains) {
        float[] edges = CabinEq.engineCutoffs();
        float[] curve = CabinEq.engineGains(gains);
        effect.setInputGainAllChannelsTo(-CabinEq.headroomDb(gains));
        for (int band = 0; band < CabinEq.ENGINE_BANDS; band++) {
            effect.setPreEqBandAllChannelsTo(band,
                    new DynamicsProcessing.EqBand(true, edges[band], curve[band]));
        }
    }
}
