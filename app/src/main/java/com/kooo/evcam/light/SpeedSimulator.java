package com.kooo.evcam.light;

/**
 * A pretend car for trying the gear and speed colours on the mood light screen: a gear, and a
 * speed that the throttle and brake push up and down. Pure, so it is tested
 * ({@code SpeedSimulatorTest}); the screen calls {@link #step} on a timer.
 */
public final class SpeedSimulator {

    public static final float MAX_KMH = 180f;
    /** Holding the throttle: 0 to 100 in about 6 s. */
    static final float THROTTLE_KMH_PER_S = 16f;
    /** Holding the brake. */
    static final float BRAKE_KMH_PER_S = 40f;
    /** Neither pedal: rolls slowly down. */
    static final float COAST_KMH_PER_S = 6f;
    /** Reverse is slow. */
    static final float MAX_REVERSE_KMH = 15f;

    private String gear = "P";
    private float speed;
    private boolean throttle;
    private boolean brake;

    public String gear() {
        return gear;
    }

    public float speedKmh() {
        return speed;
    }

    /** P and N stop the car at once: this is for colours, not physics. */
    public void setGear(String gear) {
        this.gear = gear;
        if (!"D".equals(gear) && !"R".equals(gear)) {
            speed = 0f;
        }
    }

    public void setThrottle(boolean on) {
        throttle = on;
    }

    public void setBrake(boolean on) {
        brake = on;
    }

    /** Whether {@link #step} would still change the speed. */
    public boolean moving() {
        return throttle && canDrive() || speed > 0f;
    }

    private boolean canDrive() {
        return "D".equals(gear) || "R".equals(gear);
    }

    /** Advance {@code dtMs}. */
    public void step(long dtMs) {
        float dt = dtMs / 1000f;
        float top = "R".equals(gear) ? MAX_REVERSE_KMH : MAX_KMH;
        if (brake) {
            speed -= BRAKE_KMH_PER_S * dt;
        } else if (throttle && canDrive()) {
            speed += THROTTLE_KMH_PER_S * dt;
        } else {
            speed -= COAST_KMH_PER_S * dt;
        }
        speed = Math.max(0f, Math.min(top, speed));
    }
}
