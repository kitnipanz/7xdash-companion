package com.kooo.evcam.audio;

/**
 * Fifteen-band graphic equalizer for the cabin.
 *
 * <p>Band centres are the usual one-octave graphic set. Each band's cutoff is the
 * geometric mean up to the next centre, which is the top of that band for
 * {@link android.media.audiofx.DynamicsProcessing}. The top band runs out to 20 kHz.</p>
 */
public final class CabinEq {

    public static final int BANDS = 15;
    public static final float GAIN_MIN = -12f;
    public static final float GAIN_MAX = 12f;
    /** Two saved curves. The sliders always edit one of them. */
    public static final int PRESETS = 2;

    /** Centre frequency of each band, low to high. */
    public static final int[] CENTRES_HZ = {
            25, 40, 63, 100, 160, 250, 400, 630,
            1000, 1600, 2500, 4000, 6300, 10000, 16000
    };

    private static final float TOP_HZ = 20000f;

    private CabinEq() {
    }

    /** Upper edge of each band, strictly increasing, ending at {@code 20 kHz}. */
    public static float[] cutoffs() {
        float[] edges = new float[BANDS];
        for (int i = 0; i < BANDS - 1; i++) {
            edges[i] = (float) Math.sqrt(CENTRES_HZ[i] * (double) CENTRES_HZ[i + 1]);
        }
        edges[BANDS - 1] = TOP_HZ;
        return edges;
    }

    public static String label(int hz) {
        if (hz >= 1000 && hz % 1000 == 0) {
            return (hz / 1000) + "k";
        }
        if (hz >= 1000) {
            return (hz / 1000) + "." + ((hz % 1000) / 100) + "k";
        }
        return Integer.toString(hz);
    }

    public static String formatDb(float gain) {
        int rounded = Math.round(clamp(gain));
        if (rounded > 0) {
            return "+" + rounded;
        }
        return Integer.toString(rounded);
    }

    public static float clamp(float gain) {
        if (gain < GAIN_MIN) {
            return GAIN_MIN;
        }
        if (gain > GAIN_MAX) {
            return GAIN_MAX;
        }
        return gain;
    }

    public static float[] flat() {
        return new float[BANDS];
    }

    public static float[] copy(float[] gains) {
        float[] out = flat();
        if (gains == null) {
            return out;
        }
        for (int i = 0; i < BANDS && i < gains.length; i++) {
            out[i] = clamp(gains[i]);
        }
        return out;
    }

    public static String write(float[] gains) {
        float[] safe = copy(gains);
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < BANDS; i++) {
            if (i > 0) {
                raw.append(',');
            }
            raw.append(Math.round(safe[i]));
        }
        return raw.toString();
    }

    /** A short or broken line comes back as a flat curve. */
    public static float[] read(String raw) {
        float[] out = flat();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        String[] parts = raw.split(",", -1);
        if (parts.length != BANDS) {
            return out;
        }
        try {
            for (int i = 0; i < BANDS; i++) {
                out[i] = clamp(Float.parseFloat(parts[i].trim()));
            }
        } catch (NumberFormatException ignored) {
            return flat();
        }
        return out;
    }
}
