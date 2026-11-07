package com.kooo.evcam.light;

import static com.kooo.evcam.telemetry.VehicleState.TURN_LEFT;
import static com.kooo.evcam.telemetry.VehicleState.TURN_NONE;
import static com.kooo.evcam.telemetry.VehicleState.TURN_RIGHT;
import static org.junit.Assert.assertEquals;
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
    public void blinksBetweenRedAndTheUsualColour() {
        LightLook usual = new LightLook(0x00A0FF, 60, LightProtocol.Effect.STATIC, 50);
        LightLook first = BlindSpotBlink.look(0, usual);
        assertEquals(0xFF0000, first.color);
        assertEquals(60, first.brightness);
        assertSame(usual, BlindSpotBlink.look(BlindSpotBlink.HALF_MS, usual));
        assertEquals(0xFF0000, BlindSpotBlink.look(2 * BlindSpotBlink.HALF_MS, usual).color);
        assertEquals(0xFF0000, BlindSpotBlink.look(-5, usual).color);
    }

    @Test
    public void redIsFullBrightnessWhenTheLightsAreOff() {
        LightLook off = new LightLook(0x00A0FF, 0, LightProtocol.Effect.STATIC, 50);
        assertEquals(100, BlindSpotBlink.red(off).brightness);
        assertEquals(100, BlindSpotBlink.red(null).brightness);
    }

    @Test
    public void aCarOnEitherSideIsSteadyRed() {
        assertTrue(BlindSpotBlink.steady(true, false, TURN_LEFT));
        assertTrue(BlindSpotBlink.steady(true, null, BOTH));
        assertFalse(BlindSpotBlink.steady(true, false, 0));
        assertFalse(BlindSpotBlink.steady(true, false, null));
        assertFalse(BlindSpotBlink.steady(true, true, TURN_RIGHT));
        assertFalse(BlindSpotBlink.steady(false, false, TURN_RIGHT));
    }
}
