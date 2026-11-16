package com.kooo.evcam.audio;

/**
 * The "tone and dynamics" controls that sit on top of the fifteen-band curve: bass and treble
 * shelves, loudness, a compressor, pre-amp and left/right balance.
 *
 * <p>Bass, treble and loudness are folded into the curve ({@link #shape}) so the engine needs no
 * extra stage. The compressor reuses the engine's limiter; pre-amp and balance are its input gain.</p>
 */
public final class EqTone {

    public static final int TONE_MIN = -12;
    public static final int TONE_MAX = 12;
    public static final int PREAMP_MIN = -12;
    public static final int PREAMP_MAX = 6;

    /** Shelf corners (Hz). */
    static final float BASS_HZ = 120f;
    static final float TREBLE_HZ = 5000f;
    /** Loudness at 100: this much more bass and treble, the way ears hear quiet music. */
    static final float LOUDNESS_BASS_DB = 9f;
    static final float LOUDNESS_TREBLE_DB = 4f;
    /** The engine takes any gain, but past this the curve only adds distortion. */
    static final float SHAPED_MAX = 18f;

    public static final EqTone NEUTRAL = new EqTone(0, 0, 0, 0, 0, 0);

    /** dB, {@link #TONE_MIN}..{@link #TONE_MAX}. */
    public final int bass;
    public final int treble;
    /** 0..100. */
    public final int loudness;
    /** 0 (only the safety limiter) .. 100 (night mode: loud and quiet parts brought close). */
    public final int compressor;
    /** dB, {@link #PREAMP_MIN}..{@link #PREAMP_MAX}. */
    public final int preamp;
    /** -100 all left .. 0 centre .. 100 all right. */
    public final int balance;

    public EqTone(int bass, int treble, int loudness, int compressor, int preamp, int balance) {
        this.bass = clamp(bass, TONE_MIN, TONE_MAX);
        this.treble = clamp(treble, TONE_MIN, TONE_MAX);
        this.loudness = clamp(loudness, 0, 100);
        this.compressor = clamp(compressor, 0, 100);
        this.preamp = clamp(preamp, PREAMP_MIN, PREAMP_MAX);
        this.balance = clamp(balance, -100, 100);
    }

    public EqTone withBass(int v) {
        return new EqTone(v, treble, loudness, compressor, preamp, balance);
    }

    public EqTone withTreble(int v) {
        return new EqTone(bass, v, loudness, compressor, preamp, balance);
    }

    public EqTone withLoudness(int v) {
        return new EqTone(bass, treble, v, compressor, preamp, balance);
    }

    public EqTone withCompressor(int v) {
        return new EqTone(bass, treble, loudness, v, preamp, balance);
    }

    public EqTone withPreamp(int v) {
        return new EqTone(bass, treble, loudness, compressor, v, balance);
    }

    public EqTone withBalance(int v) {
        return new EqTone(bass, treble, loudness, compressor, preamp, v);
    }

    /** Low shelf, 1 well below the corner, 0.5 at it, 0 well above. */
    static double lowShelf(double hz, double corner) {
        double r = hz / corner;
        return 1.0 / (1.0 + r * r);
    }

    /** High shelf, the mirror of {@link #lowShelf}. */
    static double highShelf(double hz, double corner) {
        double r = hz / corner;
        return r * r / (1.0 + r * r);
    }

    /** Extra dB at {@code hz} from bass, treble and loudness. */
    public double extraDb(double hz) {
        double low = lowShelf(hz, BASS_HZ);
        double high = highShelf(hz, TREBLE_HZ);
        double l = loudness / 100.0;
        return bass * low + treble * high
                + l * LOUDNESS_BASS_DB * lowShelf(hz, 90) + l * LOUDNESS_TREBLE_DB * highShelf(hz, 9000);
    }

    /** The slider curve with bass, treble and loudness added, at {@code hz} points. */
    public float[] shape(float[] gains, float[] hz) {
        float[] out = new float[gains.length];
        for (int i = 0; i < gains.length; i++) {
            double v = gains[i] + extraDb(hz[i]);
            out[i] = (float) Math.max(-SHAPED_MAX, Math.min(SHAPED_MAX, v));
        }
        return out;
    }

    /** The fifteen slider gains with tone added (what the curve line on screen shows). */
    public float[] shapeSliders(float[] gains) {
        float[] hz = new float[CabinEq.BANDS];
        for (int i = 0; i < CabinEq.BANDS; i++) {
            hz[i] = CabinEq.CENTRES_HZ[i];
        }
        return shape(CabinEq.copy(gains), hz);
    }

    /** Extra input gain for one channel from balance (0 for the louder side). 0 left, 1 right. */
    public float balanceDb(int channel) {
        float cut = channel == 0 ? Math.max(0, balance) : Math.max(0, -balance);
        if (cut <= 0) {
            return 0f;
        }
        if (cut >= 100) {
            return -60f;
        }
        return (float) (20 * Math.log10(1 - cut / 100.0));
    }

    /** Compressor threshold (dBFS); -1 is the plain safety limiter. */
    public float compressorThresholdDb() {
        return -1f - 29f * compressor / 100f;
    }

    public float compressorRatio() {
        return compressor == 0 ? 10f : 1.5f + 6.5f * compressor / 100f;
    }

    /** Make-up gain so a compressed track is about as loud as before. */
    public float compressorMakeupDb() {
        if (compressor == 0) {
            return 0f;
        }
        float over = -compressorThresholdDb();
        return 0.5f * over * (1f - 1f / compressorRatio());
    }

    public boolean isNeutral() {
        return bass == 0 && treble == 0 && loudness == 0 && compressor == 0 && preamp == 0 && balance == 0;
    }

    /** "bass,treble,loudness,compressor,preamp,balance". */
    public String write() {
        return bass + "," + treble + "," + loudness + "," + compressor + "," + preamp + "," + balance;
    }

    /** Anything broken reads as {@link #NEUTRAL}. */
    public static EqTone read(String raw) {
        if (raw == null || raw.isEmpty()) {
            return NEUTRAL;
        }
        String[] p = raw.split(",", -1);
        if (p.length != 6) {
            return NEUTRAL;
        }
        try {
            return new EqTone(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()),
                    Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()),
                    Integer.parseInt(p[4].trim()), Integer.parseInt(p[5].trim()));
        } catch (NumberFormatException e) {
            return NEUTRAL;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
