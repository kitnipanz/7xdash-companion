package com.kooo.evcam.light;

import static com.kooo.evcam.telemetry.VehicleState.TURN_LEFT;
import static com.kooo.evcam.telemetry.VehicleState.TURN_NONE;
import static com.kooo.evcam.telemetry.VehicleState.TURN_RIGHT;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BlindSpotBlinkTest {

    private static final int BOTH = TURN_LEFT | TURN_RIGHT;

    @Test
    public void signalTowardsWarnedSideBlinks() {
        assertTrue(BlindSpotBlink.active(true, TURN_LEFT, false, TURN_LEFT));
        assertTrue(BlindSpotBlink.active(true, TURN_RIGHT, false, TURN_RIGHT));
        assertTrue(BlindSpotBlink.active(true, TURN_LEFT, false, BOTH));
        assertTrue(BlindSpotBlink.active(true, TURN_RIGHT, null, BOTH));
    }

    @Test
    public void signalAwayFromWarnedSideDoesNot() {
        assertFalse(BlindSpotBlink.active(true, TURN_LEFT, false, TURN_RIGHT));
        assertFalse(BlindSpotBlink.active(true, TURN_RIGHT, false, TURN_LEFT));
    }

    @Test
    public void noSignalOrNoWarningDoesNot() {
        assertFalse(BlindSpotBlink.active(true, TURN_NONE, false, BOTH));
        assertFalse(BlindSpotBlink.active(true, TURN_LEFT, false, 0));
        assertFalse(BlindSpotBlink.active(true, null, false, BOTH));
        assertFalse(BlindSpotBlink.active(true, TURN_LEFT, false, null));
    }

    @Test
    public void hazardsOrSettingOffDoNot() {
        assertFalse(BlindSpotBlink.active(true, TURN_LEFT, true, BOTH));
        assertFalse(BlindSpotBlink.active(false, TURN_LEFT, false, TURN_LEFT));
    }

    @Test
    public void startsOrangeThenAlternates() {
        assertSame(BlindSpotBlink.ORANGE, BlindSpotBlink.look(0));
        assertSame(BlindSpotBlink.ORANGE, BlindSpotBlink.look(BlindSpotBlink.HALF_MS - 1));
        assertSame(BlindSpotBlink.DARK, BlindSpotBlink.look(BlindSpotBlink.HALF_MS));
        assertSame(BlindSpotBlink.ORANGE, BlindSpotBlink.look(2 * BlindSpotBlink.HALF_MS));
        assertSame(BlindSpotBlink.ORANGE, BlindSpotBlink.look(-5));
    }
}
