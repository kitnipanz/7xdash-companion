package com.kooo.evcam.nav;

/**
 * When to speak, Google Maps style: once well ahead of a turn ("In 1 kilometre, turn left onto…"),
 * once close to it ("In 200 metres, turn left") and once at it ("Turn left"). How far ahead
 * depends on the speed, so the car has the same few seconds to get ready on a highway as in town.
 * Each prompt is said once per maneuver. Pure Java, see the test.
 */
public final class VoicePlan {

    public enum Stage { FAR, NEAR, NOW }

    /** What to say: which stage, for which maneuver (step index, -1 = the destination), and how far. */
    public static final class Prompt {
        public final Stage stage;
        public final int next;
        /** Rounded for speaking, see {@link #spokenMeters}. */
        public final int meters;

        Prompt(Stage stage, int next, int meters) {
            this.stage = stage;
            this.next = next;
            this.meters = meters;
        }
    }

    private int forNext = Integer.MIN_VALUE;
    private int said;

    /** Starts over: a new route, so every maneuver may be said again. */
    public void reset() {
        forNext = Integer.MIN_VALUE;
        said = 0;
    }

    /**
     * The prompt due now, or null. {@code legMeters} is how long the drive from the last maneuver to
     * this one is, so a short gap between two turns does not get a "far" prompt it never had room for.
     */
    public Prompt due(int next, double toNext, double legMeters, double speedKmh) {
        if (next != forNext) {
            forNext = next;
            said = 0;
        }
        double ms = Math.max(0, speedKmh) / 3.6;
        double now = nowMeters(ms);
        double near = nearMeters(ms);
        double far = farMeters(ms);
        Stage stage = null;
        if (toNext <= now) {
            stage = Stage.NOW;
        } else if (toNext <= near) {
            stage = Stage.NEAR;
        } else if (toNext <= far && legMeters > near + 300) {
            stage = Stage.FAR;
        }
        if (stage == null) {
            return null;
        }
        // a later stage covers the earlier ones: never say "in 1 km" after "in 200 m"
        int bit = 1 << stage.ordinal();
        if (said >= bit) {
            return null;
        }
        said |= bit;
        for (int b = 0; b < stage.ordinal(); b++) {
            said |= 1 << b;
        }
        return new Prompt(stage, next, stage == Stage.NOW ? 0 : spokenMeters(toNext));
    }

    /** About 5 s out, 30 to 120 m. */
    static double nowMeters(double ms) {
        return clamp(ms * 5, 30, 120);
    }

    /** About 15 s out, 150 to 500 m. */
    static double nearMeters(double ms) {
        return clamp(ms * 15, 150, 500);
    }

    /** 2 km on the highway, 1 km on fast roads, 500 m in town. */
    static double farMeters(double ms) {
        return ms > 80 / 3.6 ? 2_000 : ms > 50 / 3.6 ? 1_000 : 500;
    }

    /** Distances as people say them: 50 m steps under 1 km, then half kilometres. */
    public static int spokenMeters(double meters) {
        if (meters < 100) {
            return (int) Math.max(10, Math.round(meters / 10) * 10);
        }
        if (meters < 1_000) {
            return (int) (Math.round(meters / 50) * 50);
        }
        return (int) (Math.round(meters / 500) * 500);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
