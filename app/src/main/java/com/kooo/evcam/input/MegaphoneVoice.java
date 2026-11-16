package com.kooo.evcam.input;

/**
 * Live cleanup for the megaphone, one chunk at a time, so the outside speaker does not feed back
 * into the cabin mic as an echo (or a howl).
 *
 * <ol>
 *   <li>Voice band only: a 150 Hz high-pass and a 5 kHz low-pass (12 dB/octave each). Rumble and
 *   hiss carry no words, and the loop through the car body rings outside the voice band first.</li>
 *   <li>Gain, as before.</li>
 *   <li>A gate that learns the room: it follows the quiet level, opens when the voice is well above
 *   it, and drops 24 dB a moment after the voice stops, so the echo coming back in through the mic
 *   cannot keep going round.</li>
 *   <li>A soft limiter instead of hard clipping.</li>
 * </ol>
 *
 * Not thread-safe: one per megaphone run.
 */
final class MegaphoneVoice {

    /** How far the gate turns down between words (−24 dB). */
    static final float CLOSED = 0.063f;
    /** Voice must be this many times the room level to open the gate (+12 dB). */
    static final float OPEN_RATIO = 4f;
    /** After amplification, below this is never voice. */
    static final float MIN_OPEN = 500f;

    private final Biquad highPass;
    private final Biquad lowPass;
    private final float envAttack;
    private final float envRelease;
    private final float gainAttack;
    private final float gainRelease;
    private final int holdSamples;

    private float env;
    /** Quiet level of the room: falls fast, rises slowly. */
    private float floor = -1f;
    private float gate = CLOSED;
    private int hold;

    MegaphoneVoice(int rate) {
        highPass = Biquad.highPass(rate, 150);
        lowPass = Biquad.lowPass(rate, 5000);
        envAttack = coeff(rate, 0.003);
        envRelease = coeff(rate, 0.040);
        gainAttack = coeff(rate, 0.002);
        gainRelease = coeff(rate, 0.080);
        holdSamples = rate * 120 / 1000;
    }

    private static float coeff(int rate, double seconds) {
        return 1f - (float) Math.exp(-1.0 / (rate * seconds));
    }

    /**
     * Cleans and amplifies a 16-bit little-endian chunk in place; returns the peak written, 0–100.
     */
    int process(byte[] pcm, int length, int gain) {
        if (pcm == null || length < 2) {
            return 0;
        }
        int n = Math.min(length, pcm.length) & ~1;
        if (gain <= 0) {
            java.util.Arrays.fill(pcm, 0, n, (byte) 0);
            return 0;
        }
        int peak = 0;
        for (int i = 0; i < n; i += 2) {
            float x = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            x = lowPass.step(highPass.step(x)) * gain;
            float y = gateStep(x) * x;
            int out = Math.round(softLimit(y));
            pcm[i] = (byte) out;
            pcm[i + 1] = (byte) (out >> 8);
            peak = Math.max(peak, Math.abs(out));
        }
        return peak >= 32767 ? 100 : peak * 100 / 32767;
    }

    /** Gate gain for this sample (CLOSED..1). */
    private float gateStep(float x) {
        float level = Math.abs(x);
        env += (level > env ? envAttack : envRelease) * (level - env);
        if (floor < 0) {
            floor = env;
        }
        // follows the room down at once, up only slowly (about +6 dB a second)
        floor = env < floor ? env : floor * 1.0000289f;
        boolean voice = env > Math.max(MIN_OPEN, floor * OPEN_RATIO);
        if (voice) {
            hold = holdSamples;
        } else if (hold > 0) {
            hold--;
        }
        float want = voice || hold > 0 ? 1f : CLOSED;
        gate += (want > gate ? gainAttack : gainRelease) * (want - gate);
        return gate;
    }

    /** Straight up to 24 000, then a smooth bend that never passes full scale. */
    static float softLimit(float y) {
        float knee = 24000f;
        float a = Math.abs(y);
        if (a <= knee) {
            return y;
        }
        float room = 32767f - knee;
        float over = a - knee;
        float bent = knee + room * (float) Math.tanh(over / room);
        return Math.copySign(bent, y);
    }

    /** RBJ cookbook biquad, Q 0.707 (no peak at the corner). */
    static final class Biquad {
        private final float b0, b1, b2, a1, a2;
        private float x1, x2, y1, y2;

        private Biquad(double b0, double b1, double b2, double a0, double a1, double a2) {
            this.b0 = (float) (b0 / a0);
            this.b1 = (float) (b1 / a0);
            this.b2 = (float) (b2 / a0);
            this.a1 = (float) (a1 / a0);
            this.a2 = (float) (a2 / a0);
        }

        static Biquad highPass(int rate, double hz) {
            double w = 2 * Math.PI * hz / rate;
            double cos = Math.cos(w);
            double alpha = Math.sin(w) / (2 * 0.7071);
            return new Biquad((1 + cos) / 2, -(1 + cos), (1 + cos) / 2, 1 + alpha, -2 * cos, 1 - alpha);
        }

        static Biquad lowPass(int rate, double hz) {
            double w = 2 * Math.PI * hz / rate;
            double cos = Math.cos(w);
            double alpha = Math.sin(w) / (2 * 0.7071);
            return new Biquad((1 - cos) / 2, 1 - cos, (1 - cos) / 2, 1 + alpha, -2 * cos, 1 - alpha);
        }

        float step(float x) {
            float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = y;
            return y;
        }
    }
}
