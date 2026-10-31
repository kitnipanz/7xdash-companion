package com.kooo.evcam.light;

import com.kooo.evcam.telemetry.VehicleState;

/**
 * Blink the mood lights orange while the turn signal is on towards a side where the blind-spot
 * assist warns of a car ({@link GearLight}). Signal off, hazards on, or the warning gone: the
 * lights go back to what their gear rules show.
 *
 * <p>Pure, tested in {@code BlindSpotBlinkTest}.</p>
 */
final class BlindSpotBlink {

    /** Half a blink: orange this long, dark this long. */
    static final long HALF_MS = 400L;

    static final LightLook ORANGE = new LightLook(0xFF6A00, 100, LightProtocol.Effect.STATIC, 50);
    /**
     * The dark half is black, not power off: the lights stay on, so no power packet each blink
     * (some clones answer power-on by bringing back their last colour). The car's own light reads
     * brightness 0 as off.
     */
    static final LightLook DARK = new LightLook(0x000000, 0, LightProtocol.Effect.STATIC, 50);

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

    /** What to show {@code sinceMs} after the blink started: orange first, then dark, and so on. */
    static LightLook look(long sinceMs) {
        return (Math.max(0L, sinceMs) / HALF_MS) % 2 == 0 ? ORANGE : DARK;
    }
}
