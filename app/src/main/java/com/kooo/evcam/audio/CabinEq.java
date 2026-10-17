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
    /** The user's own curves ("My 1" … "My 5"). */
    public static final int PRESETS = 5;

    /** Built-in curves. Read only: moving a slider on one of them saves the result into a "My" preset. */
    public enum Builtin {
        FLAT(new float[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}),
        ROCK(new float[]{5, 5, 4, 3, 1, -1, -2, -2, -1, 1, 3, 4, 4, 4, 3}),
        JAZZ(new float[]{3, 3, 2, 2, 1, 0, -1, -1, 0, 1, 1, 2, 2, 3, 3}),
        HIPHOP(new float[]{6, 6, 5, 4, 2, 0, -1, -1, 0, 1, 1, 1, 2, 2, 2}),
        POP(new float[]{-1, 0, 1, 2, 3, 3, 2, 1, 0, 0, 1, 2, 2, 2, 1}),
        KPOP(new float[]{3, 4, 4, 3, 1, 0, -1, 0, 1, 2, 3, 4, 4, 4, 3});

        private final float[] gains;

        Builtin(float[] gains) {
            this.gains = gains;
        }

        public float[] gains() {
            return copy(gains);
        }
    }

    /**
     * Engine bands: half-octave steps, twice as many as the sliders. The engine's bands are
     * flat inside and jump at each edge, so fifteen of them sound like a staircase; thirty
     * with the gain blended between slider centres follow the curve smoothly.
     */
    public static final int ENGINE_BANDS = BANDS * 2;

    /** Engine band centres (Hz): every slider centre and the geometric midpoint after it. */
    public static float[] engineCentres() {
        float[] out = new float[ENGINE_BANDS];
        for (int i = 0; i < BANDS; i++) {
            out[i * 2] = CENTRES_HZ[i];
            float next = i + 1 < BANDS ? CENTRES_HZ[i + 1] : TOP_HZ;
            out[i * 2 + 1] = (float) Math.sqrt(CENTRES_HZ[i] * (double) next);
        }
        return out;
    }

    /** Upper edge of each engine band: geometric mean up to the next engine centre, last one 20 kHz. */
    public static float[] engineCutoffs() {
        float[] centres = engineCentres();
        float[] edges = new float[ENGINE_BANDS];
        for (int i = 0; i < ENGINE_BANDS - 1; i++) {
            edges[i] = (float) Math.sqrt(centres[i] * (double) centres[i + 1]);
        }
        edges[ENGINE_BANDS - 1] = TOP_HZ;
        return edges;
    }

    /**
     * The slider curve spread over the engine bands: at each slider centre the slider's own gain,
     * halfway between two sliders the average of the two (linear in dB along log frequency).
     */
    public static float[] engineGains(float[] gains) {
        float[] g = copy(gains);
        float[] out = new float[ENGINE_BANDS];
        for (int i = 0; i < BANDS; i++) {
            out[i * 2] = g[i];
            out[i * 2 + 1] = i + 1 < BANDS ? (g[i] + g[i + 1]) / 2f : g[i];
        }
        return out;
    }

    /**
     * How far to turn the whole signal down before the curve, so a boost cannot push a
     * full-scale track into clipping (the "harsh / crackly" sound). Equal to the biggest boost;
     * a curve that only cuts needs none.
     */
    public static float headroomDb(float[] gains) {
        float max = 0f;
        for (float g : copy(gains)) {
            max = Math.max(max, g);
        }
        return max;
    }

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
