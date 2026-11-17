package com.kooo.evcam.overlay;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.kooo.evcam.R;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Signal;

/**
 * What a gauge under the Dash panel can show ({@link DashGaugesView}): a car reading, its scale and
 * where the red starts. G-force is worked out from the speed ({@link GForce}). Plain data, tested off the device.
 */
public enum DashGauge {

    SPEED("speed", R.string.gauge_speed, R.string.gauge_unit_kmh, 0, 200, 160, false, 0),
    THROTTLE("throttle", R.string.gauge_throttle, R.string.gauge_unit_percent, 0, 100, 90, false, 0),
    BRAKE("brake", R.string.gauge_brake, R.string.gauge_unit_percent, 0, 100, 80, false, 0),
    G_FORCE("g", R.string.gauge_g, R.string.gauge_unit_g, -1, 1, 0.6f, true, 2),
    STEERING("steering", R.string.gauge_steering, R.string.gauge_unit_degrees, -540, 540, 450, true, 0),
    BATTERY("battery", R.string.gauge_battery, R.string.gauge_unit_percent, 0, 100, -20, false, 0),
    RANGE("range", R.string.gauge_range, R.string.gauge_unit_km, 0, 600, -50, false, 0),
    BATTERY_TEMP("battery_temp", R.string.gauge_battery_temp, R.string.gauge_unit_celsius, 0, 60, 45, false, 0),
    OUTSIDE_TEMP("outside_temp", R.string.gauge_outside_temp, R.string.gauge_unit_celsius, -10, 50, 40, false, 0),
    CABIN_TEMP("cabin_temp", R.string.gauge_cabin_temp, R.string.gauge_unit_celsius, 0, 50, 40, false, 0);

    /** Three gauges, left to right. */
    public static final int COUNT = 3;
    static final DashGauge[] DEFAULTS = {THROTTLE, G_FORCE, BATTERY};

    public final String key;
    @StringRes
    public final int labelRes;
    @StringRes
    public final int unitRes;
    public final float min;
    public final float max;
    /**
     * Red from here up; negative means red from the bottom up to its size (battery under 20%).
     * Centre-zero gauges go red past it either way.
     */
    public final float red;
    /** The needle starts from the middle (left and right, braking and pulling). */
    public final boolean centreZero;
    /** Digits after the point. */
    public final int decimals;

    DashGauge(String key, int labelRes, int unitRes, float min, float max, float red, boolean centreZero, int decimals) {
        this.key = key;
        this.labelRes = labelRes;
        this.unitRes = unitRes;
        this.min = min;
        this.max = max;
        this.red = red;
        this.centreZero = centreZero;
        this.decimals = decimals;
    }

    @Nullable
    public static DashGauge fromKey(String key) {
        for (DashGauge g : values()) {
            if (g.key.equals(key)) {
                return g;
            }
        }
        return null;
    }

    /** The reading now, or null when the car has not said. */
    @Nullable
    public Float value(Readings readings, @Nullable Float gForce) {
        switch (this) {
            case SPEED:
                return readings.number(Signal.SPEED);
            case THROTTLE:
                return readings.number(Signal.THROTTLE_DEPTH);
            case BRAKE:
                return readings.number(Signal.BRAKE_DEPTH);
            case G_FORCE:
                return gForce;
            case STEERING:
                return readings.number(Signal.STEERING);
            case BATTERY:
                return readings.number(Signal.BATTERY);
            case RANGE:
                return readings.number(Signal.RANGE);
            case BATTERY_TEMP:
                return readings.number(Signal.BATTERY_TEMP);
            case OUTSIDE_TEMP:
                return readings.number(Signal.TEMP_OUTSIDE);
            case CABIN_TEMP:
                return readings.number(Signal.TEMP_INSIDE);
            default:
                return null;
        }
    }

    /** Where {@code v} sits on the dial, 0 (left end) to 1 (right end), kept on the dial. */
    public float fraction(float v) {
        float f = (v - min) / (max - min);
        return Math.max(0f, Math.min(1f, f));
    }

    /** Is {@code v} in the red? */
    public boolean inRed(float v) {
        if (centreZero) {
            return Math.abs(v) >= red;
        }
        return red < 0 ? v <= -red : v >= red;
    }

    /** The number as the dial prints it. */
    public String format(float v) {
        if (decimals == 0) {
            return Integer.toString(Math.round(v));
        }
        return String.format(java.util.Locale.US, "%." + decimals + "f", v);
    }

    /** "a,b,c" → three gauges; unknown or missing ones get the default for that place. */
    public static DashGauge[] parse(@Nullable String stored) {
        DashGauge[] out = DEFAULTS.clone();
        if (stored == null || stored.isEmpty()) {
            return out;
        }
        String[] parts = stored.split(",", -1);
        for (int i = 0; i < COUNT && i < parts.length; i++) {
            DashGauge g = fromKey(parts[i].trim());
            if (g != null) {
                out[i] = g;
            }
        }
        return out;
    }

    public static String write(DashGauge[] gauges) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < gauges.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(gauges[i].key);
        }
        return sb.toString();
    }

    /**
     * Forward pull or braking in g, from how fast the speed changes. Speed comes a few times a
     * second, so it is smoothed; a gap of over two seconds starts again.
     */
    public static final class GForce {
        private static final float G = 9.80665f;
        private static final long STALE_MS = 2_000L;
        /** How much of each new sample to take (the rest is the old value). */
        private static final float SMOOTH = 0.35f;

        private float lastKmh = Float.NaN;
        private long lastMs;
        private Float g;

        /** A speed sample (km/h) at {@code nowMs}; returns the g now, null until there are two samples. */
        @Nullable
        public Float update(@Nullable Float kmh, long nowMs) {
            if (kmh == null) {
                return g;
            }
            if (Float.isNaN(lastKmh) || nowMs - lastMs > STALE_MS) {
                lastKmh = kmh;
                lastMs = nowMs;
                g = null;
                return null;
            }
            long dt = nowMs - lastMs;
            if (dt < 50) {
                // too close together to tell
                return g;
            }
            float accel = (kmh - lastKmh) / 3.6f / (dt / 1000f) / G;
            g = g == null ? accel : g + SMOOTH * (accel - g);
            lastKmh = kmh;
            lastMs = nowMs;
            return g;
        }
    }
}
