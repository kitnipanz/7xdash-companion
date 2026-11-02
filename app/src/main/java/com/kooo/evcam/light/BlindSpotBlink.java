package com.kooo.evcam.light;

import com.kooo.evcam.telemetry.VehicleState;

/**
 * The mood lights (and the car's own ambient light, when it is in a group) go red for a car in the
 * blind spot, like Tesla ({@link GearLight}): steady red while the car warns of a car on either
 * side, and while the turn signal is on towards a warned side they blink between red and their
 * usual colour. Hazards on, or the warning
 * gone: the lights go back to what their gear rules show.
 *
 * <p>Pure, tested in {@code BlindSpotBlinkTest}.</p>
 */
final class BlindSpotBlink {

    /** Half a blink: red this long, the usual colour this long. */
    static final long HALF_MS = 400L;

    static final int RED_RGB = 0xFF0000;

    private BlindSpotBlink() {
    }

    /**
     * @param enabled     the setting
     * @param turnSignal  {@link VehicleState#TURN_NONE} / {@code TURN_LEFT} / {@code TURN_RIGHT}; null = unknown
     * @param hazard      hazards on; null = unknown
     * @param warnedSides {@link VehicleState#blindSpotWarning}: bit {@code TURN_LEFT} and / or {@code TURN_RIGHT}; null = unknown
     */
    static boolean active(boolean enabled, Integer turnSignal, Boolean hazard, Integer warnedSides) {
        if (!enabled || turnSignal == null || warnedSides == null || Boolean.TRUE.equals(hazard)) {
            return false;
        }
        if (turnSignal != VehicleState.TURN_LEFT && turnSignal != VehicleState.TURN_RIGHT) {
            return false;
        }
        return (warnedSides & turnSignal) != 0;
    }

    static boolean active(boolean enabled, VehicleState s) {
        return active(enabled, s.turnSignal, s.hazard, s.blindSpotWarning);
    }

    /** A car on either side, hazards off: the lights stay red (they blink instead when {@link #active}). */
    static boolean steady(boolean enabled, Boolean hazard, Integer warnedSides) {
        return enabled && warnedSides != null && warnedSides != 0 && !Boolean.TRUE.equals(hazard);
    }

    static boolean steady(boolean enabled, VehicleState s) {
        return steady(enabled, s.hazard, s.blindSpotWarning);
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
