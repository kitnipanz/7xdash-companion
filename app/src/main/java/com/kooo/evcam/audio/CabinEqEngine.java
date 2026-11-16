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
        apply(store.enabled(), store.currentGains(), store.tone());
    }

    /** The curve with no tone controls (kept for callers that only know the sliders). */
    public static boolean apply(boolean enabled, float[] gains) {
        return apply(enabled, gains, tone);
    }

    /**
     * @return true when the output mix accepted the curve, or when the equalizer was turned off
     */
    public static synchronized boolean apply(boolean enabled, float[] gains, EqTone newTone) {
        if (!enabled) {
            release();
            return true;
        }
        float[] curve = CabinEq.copy(gains);
        EqTone previous = tone;
        tone = newTone == null ? EqTone.NEUTRAL : newTone;
        if (effect != null && profile != null && profile.limiter && previous.compressor != tone.compressor) {
            // the limiter's settings are fixed when the effect is made: make it again
            release();
        }
        try {
            if (effect == null) {
                effect = open(curve);
            } else {
                try {
                    push(effect, curve);
                } catch (RuntimeException e) {
                    AppLog.w(TAG, "equalizer update refused, reopening: " + e);
                    release();
                    effect = open(curve);
                }
            }
            effect.setEnabled(!bypassed);
            return true;
        } catch (RuntimeException e) {
            AppLog.w(TAG, "equalizer refused: " + e);
            release();
            return false;
        }
    }

    /** Tone controls in use; kept across {@link #release()} so a reopen uses them. */
    private static EqTone tone = EqTone.NEUTRAL;
    private static boolean bypassed;

    /**
     * A/B compare: while bypassed the music plays untouched; the curve stays loaded so letting go
     * brings it straight back.
     */
    public static synchronized void setBypassed(boolean bypass) {
        bypassed = bypass;
        if (effect != null) {
            try {
                effect.setEnabled(!bypass);
            } catch (RuntimeException e) {
                AppLog.w(TAG, "bypass refused: " + e);
            }
        }
    }

    /** True when the compressor can run (the engine took a limiter). */
    public static synchronized boolean hasDynamics() {
        return profile == null || profile.limiter;
    }

    /** Session the output-mix effect runs on, for the spectrum (0: the whole mix). */
    public static final int MIX_SESSION = 0;

    public static synchronized void release() {
        if (effect != null) {
            try {
                effect.release();
            } catch (RuntimeException ignored) {
                // already gone
            }
            effect = null;
        }
        profile = null;
    }

    /**
     * One way to set the engine up. The head unit may refuse the richer ones, so {@link #open}
     * walks {@link #PROFILES} from best to plainest; the last one is the 1.0.2 setup that the
     * car is known to take.
     */
    private static final class Profile {
        final String name;
        final int variant;
        final boolean blended;
        final boolean headroom;
        final boolean limiter;

        Profile(String name, int variant, boolean blended, boolean headroom, boolean limiter) {
            this.name = name;
            this.variant = variant;
            this.blended = blended;
            this.headroom = headroom;
            this.limiter = limiter;
        }

        int bands() {
            return blended ? CabinEq.ENGINE_BANDS : CabinEq.BANDS;
        }

        float[] edges() {
            return blended ? CabinEq.engineCutoffs() : CabinEq.cutoffs();
        }

        float[] curve(float[] gains, EqTone tone) {
            return blended ? tone.shape(CabinEq.engineGains(gains), CabinEq.engineCentres())
                    : tone.shape(CabinEq.copy(gains), centres());
        }

        private static float[] centres() {
            float[] hz = new float[CabinEq.BANDS];
            for (int i = 0; i < CabinEq.BANDS; i++) {
                hz[i] = CabinEq.CENTRES_HZ[i];
            }
            return hz;
        }
    }

    private static final Profile[] PROFILES = {
            new Profile("time/30/limiter", DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION, true, true, true),
            new Profile("freq/30/limiter", DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, true, true, true),
            new Profile("freq/30", DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, true, true, false),
            new Profile("freq/15/headroom", DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, false, true, false),
            new Profile("freq/15", DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, false, false, false),
    };

    /** The profile the open effect was built with. */
    private static Profile profile;

    /** First profile worth trying: ones the head unit already refused are skipped next time. */
    private static int firstTry;

    private static DynamicsProcessing open(float[] gains) {
        RuntimeException last = null;
        for (int i = firstTry; i < PROFILES.length; i++) {
            Profile p = PROFILES[i];
            for (int channels : new int[] {2, 1}) {
                DynamicsProcessing created = null;
                try {
                    created = new DynamicsProcessing(0, 0, config(p, channels, gains));
                    if (p.headroom) {
                        // some builds take the config but refuse a live input-gain change
                        for (int ch = 0; ch < channels; ch++) {
                            created.setInputGainbyChannel(ch, inputGain(p, gains, ch, channels));
                        }
                    }
                    profile = p;
                    firstTry = i;
                    AppLog.i(TAG, "equalizer on the output mix, " + channels + " ch, " + p.name);
                    return created;
                } catch (RuntimeException e) {
                    if (created != null) {
                        try {
                            created.release();
                        } catch (RuntimeException ignored) {
                            // already gone
                        }
                    }
                    AppLog.w(TAG, "equalizer profile " + p.name + " / " + channels + " ch refused: " + e);
                    last = e;
                }
            }
        }
        throw last == null ? new IllegalStateException("equalizer") : last;
    }

    /**
     * Pre-EQ, optionally with thirty blended bands, the input turned down by the biggest boost
     * (headroom), and a gentle limiter just under full scale to catch what still gets through.
     * FAVOR_TIME_RESOLUTION keeps latency and smearing low on music.
     */
    private static DynamicsProcessing.Config config(Profile p, int channels, float[] gains) {
        DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
                p.variant,
                channels,
                true, p.bands(),
                false, 0,
                false, 0,
                p.limiter).build();
        float[] edges = p.edges();
        float[] curve = p.curve(gains, tone);
        for (int channel = 0; channel < channels; channel++) {
            DynamicsProcessing.Eq eq = config.getPreEqByChannelIndex(channel);
            eq.setEnabled(true);
            for (int band = 0; band < p.bands(); band++) {
                eq.setBand(band, new DynamicsProcessing.EqBand(true, edges[band], curve[band]));
            }
            config.setPreEqByChannelIndex(channel, eq);
            if (p.headroom) {
                config.setInputGainByChannelIndex(channel, inputGain(p, gains, channel, channels));
            }
            if (p.limiter) {
                config.setLimiterByChannelIndex(channel, limiter(tone));
            }
        }
        return config;
    }

    /**
     * Input gain for a channel: down by the biggest boost in the shaped curve (headroom), plus
     * pre-amp, plus balance. With one channel balance does nothing.
     */
    private static float inputGain(Profile p, float[] gains, int channel, int channels) {
        float max = 0f;
        for (float g : p.curve(gains, tone)) {
            max = Math.max(max, g);
        }
        float balance = channels > 1 ? tone.balanceDb(channel) : 0f;
        return -max + tone.preamp + balance;
    }

    /**
     * Compressor 0: soft safety limiter at -1 dBFS (fast attack, quick release, no make-up).
     * Higher: the same stage as a compressor, threshold down to -30 dBFS, gentler ratio, slower
     * release, and make-up gain so quiet parts come up ("night mode").
     */
    private static DynamicsProcessing.Limiter limiter(EqTone t) {
        if (t.compressor == 0) {
            return new DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f);
        }
        return new DynamicsProcessing.Limiter(true, true, 0, 5f, 150f, t.compressorRatio(),
                t.compressorThresholdDb(), t.compressorMakeupDb());
    }

    private static void push(DynamicsProcessing effect, float[] gains) {
        Profile p = profile;
        float[] edges = p.edges();
        float[] curve = p.curve(gains, tone);
        if (p.headroom) {
            int channels = effect.getChannelCount();
            for (int ch = 0; ch < channels; ch++) {
                effect.setInputGainbyChannel(ch, inputGain(p, gains, ch, channels));
            }
        }
        for (int band = 0; band < p.bands(); band++) {
            effect.setPreEqBandAllChannelsTo(band,
                    new DynamicsProcessing.EqBand(true, edges[band], curve[band]));
        }
    }
}
