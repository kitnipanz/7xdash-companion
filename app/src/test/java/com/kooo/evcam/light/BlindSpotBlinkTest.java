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
    public void signalTowardsWarnedSideBlinksAtAnySpeed() {
        assertTrue(BlindSpotBlink.active(true, TURN_LEFT, false, TURN_LEFT, 10f, 60));
        assertTrue(BlindSpotBlink.active(true, TURN_RIGHT, null, BOTH, 10f, 60));
    }

    @Test
    public void warningAloneBlinksFromTheWarningSpeed() {
        assertFalse(BlindSpotBlink.active(true, TURN_NONE, false, TURN_LEFT, 40f, 60));
        assertTrue(BlindSpotBlink.active(true, TURN_NONE, false, TURN_LEFT, 60f, 60));
        assertTrue(BlindSpotBlink.active(true, TURN_NONE, false, TURN_LEFT, null, 60));
        assertTrue(BlindSpotBlink.active(true, TURN_NONE, false, TURN_LEFT, 5f, 0));
        // slow, signalling away from the warned side
        assertFalse(BlindSpotBlink.active(true, TURN_LEFT, false, TURN_RIGHT, 30f, 60));
    }

    @Test
    public void noWarningHazardsOrSettingOffDoNot() {
        assertFalse(BlindSpotBlink.active(true, TURN_LEFT, false, 0, 80f, 60));
        assertFalse(BlindSpotBlink.active(true, TURN_LEFT, false, null, 80f, 60));
        assertFalse(BlindSpotBlink.active(true, TURN_LEFT, true, BOTH, 80f, 60));
        assertFalse(BlindSpotBlink.active(false, TURN_LEFT, false, TURN_LEFT, 80f, 60));
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
}
