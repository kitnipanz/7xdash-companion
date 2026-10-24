package com.kooo.evcam.announce;

/** Built-in sounds, made in code: mono 16-bit at {@link #RATE}, peaks at 70% of full scale. */
public final class Tones {

    public static final String CHIME = "chime";
    public static final String BEEP = "beep";
    public static final String SIREN = "siren";
    public static final int RATE = 48_000;
    private static final double PEAK = 0.7 * 32767;

    private Tones() {
    }

    /** The sound for {@code key}, or null when there is no such sound. */
    public static short[] render(String key) {
        if (CHIME.equals(key)) {
            // Two falling bell notes (E6, C6), each fading out
            return join(bell(1318.5, 0.45), bell(1046.5, 0.7));
        }
        if (BEEP.equals(key)) {
            return join(tone(1000, 0.15), silence(0.1), tone(1000, 0.15));
        }
        if (SIREN.equals(key)) {
            return siren(3, 0.8);
        }
        return null;
    }

    private static short[] bell(double hz, double seconds) {
        int n = (int) (RATE * seconds);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / RATE;
            double env = Math.exp(-4.0 * t / seconds) * attack(i);
            double v = Math.sin(2 * Math.PI * hz * t) * 0.8 + Math.sin(4 * Math.PI * hz * t) * 0.2;
            out[i] = (short) (v * env * PEAK);
        }
        return out;
    }

    private static short[] tone(double hz, double seconds) {
        int n = (int) (RATE * seconds);
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            double env = Math.min(attack(i), attack(n - 1 - i));
            out[i] = (short) (Math.sin(2 * Math.PI * hz * i / RATE) * env * PEAK);
        }
        return out;
    }

    /** Rises 600 → 1200 Hz and back, {@code cycles} times. */
    private static short[] siren(int cycles, double cycleSeconds) {
        int per = (int) (RATE * cycleSeconds);
        int n = per * cycles;
        short[] out = new short[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double x = (double) (i % per) / per;
            double hz = 600 + 600 * (x < 0.5 ? x * 2 : (1 - x) * 2);
            phase += 2 * Math.PI * hz / RATE;
            double env = Math.min(attack(i), attack(n - 1 - i));
            out[i] = (short) (Math.sin(phase) * env * PEAK);
        }
        return out;
    }

    private static short[] silence(double seconds) {
        return new short[(int) (RATE * seconds)];
    }

    /** 5 ms ramp so a sound never starts or stops with a click. */
    private static double attack(int i) {
        return Math.min(1.0, i / (RATE * 0.005));
    }

    private static short[] join(short[]... parts) {
        int n = 0;
        for (short[] p : parts) {
            n += p.length;
        }
        short[] out = new short[n];
        int at = 0;
        for (short[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }
}
