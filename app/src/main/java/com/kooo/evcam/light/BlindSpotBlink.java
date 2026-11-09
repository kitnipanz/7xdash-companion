package com.kooo.evcam.light;

import com.kooo.evcam.telemetry.BlindSpotWatch;
import com.kooo.evcam.telemetry.VehicleState;

/**
 * The mood lights (and the car's own ambient light, when it is in a group) blink red for a car in
 * the blind spot, like Tesla ({@link GearLight}), in the same rhythm as the side view's red edge
 * ({@link BlindSpotWatch#BLINK_HALF_MS}): red, then their usual colour, and so on. They blink while a
 * warned side alerts ({@link BlindSpotWatch#alertSides}): the turn signal points at it, at any speed,
 * or the car goes at least the warning speed (Settings, default 60 km/h). Hazards on, or the warning
 * gone: the lights go back to what their gear rules show.
 *
 * <p>Pure, tested in {@code BlindSpotBlinkTest}.</p>
 */
final class BlindSpotBlink {

    /** Half a blink: red this long, the usual colour this long. Same as the red edge. */
    static final long HALF_MS = BlindSpotWatch.BLINK_HALF_MS;

    static final int RED_RGB = 0xFF0000;

    private BlindSpotBlink() {
    }

    /**
     * @param enabled     the setting
     * @param turnSignal  {@link VehicleState#TURN_NONE} / {@code TURN_LEFT} / {@code TURN_RIGHT}; null = unknown
     * @param hazard      hazards on; null = unknown
     * @param warnedSides {@link VehicleState#blindSpotWarning}: bit {@code TURN_LEFT} and / or {@code TURN_RIGHT}; null = unknown
     * @param speedKmh    null = unknown (does not block)
     * @param minSpeedKmh the warning alone blinks only from this speed; 0 = any speed
     */
    static boolean active(boolean enabled, Integer turnSignal, Boolean hazard, Integer warnedSides,
                          Float speedKmh, int minSpeedKmh) {
        return enabled && BlindSpotWatch.alertSides(warnedSides, turnSignal, hazard, speedKmh, minSpeedKmh) != 0;
    }

    static boolean active(boolean enabled, VehicleState s, int minSpeedKmh) {
        return active(enabled, s.turnSignal, s.hazard, s.blindSpotWarning, s.speedKmh, minSpeedKmh);
    }

    /** Red in the brightness the lights have now (full when they are off), so the warning matches the cabin. */
    static LightLook red(LightLook usual) {
        int brightness = usual == null || usual.brightness <= 0 ? 100 : usual.brightness;
        return new LightLook(RED_RGB, brightness, LightProtocol.Effect.STATIC, 50);
    }

    /** Red half or usual half, {@code sinceMs} after the blink started: red first. */
    static boolean redHalf(long sinceMs) {
        return (Math.max(0L, sinceMs) / HALF_MS) % 2 == 0;
    }

    /**
     * What to show {@code sinceMs} after the blink started: red, then the colour the gear rules
     * show now ({@code usual}), and so on. The lights never go dark in between.
     */
    static LightLook look(long sinceMs, LightLook usual) {
        return redHalf(sinceMs) ? red(usual) : usual;
    }
}
