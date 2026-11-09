package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BlindSpotWatchTest {

    @Test
    public void mirrorLampWarns() {
        // nothing taught: the mirror lamp is the built-in signal; 0 quiet, 1 warning, 255 no reading
        assertFalse(BlindSpotWatch.side(false, 0, null, 0, 255));
        assertTrue(BlindSpotWatch.side(false, 0, null, 1, 255));
        assertTrue(BlindSpotWatch.side(false, 0, null, 2, null));
        assertFalse(BlindSpotWatch.side(false, 0, null, 255, 255));
        assertFalse(BlindSpotWatch.side(false, 0, null, null, null));
    }

    @Test
    public void rctaWarnsWhenTheMirrorLampDoesNot() {
        assertTrue(BlindSpotWatch.side(false, 0, null, 0, 1));
        assertTrue(BlindSpotWatch.side(false, 0, null, null, 2));
        assertFalse(BlindSpotWatch.side(false, 0, null, 0, 0));
    }

    @Test
    public void aTaughtSideWarnsOnItsValueOnly() {
        assertTrue(BlindSpotWatch.side(true, 3, 3, null, null));
        assertFalse(BlindSpotWatch.side(true, 3, 1, null, null));
        assertFalse(BlindSpotWatch.side(true, 3, null, null, null));
        // the built-in signals are not read for a taught side
        assertFalse(BlindSpotWatch.side(true, 3, 0, 1, 1));
    }

    @Test
    public void taughtNoReadingDoesNotCount() {
        assertTrue(BlindSpotWatch.taught(0x2808_1700, 1));
        assertFalse(BlindSpotWatch.taught(0x2808_1700, 255));
        assertFalse(BlindSpotWatch.taught(0, 1));
    }

    @Test
    public void builtInWarningRange() {
        assertFalse(BlindSpotWatch.builtInWarning(null));
        assertFalse(BlindSpotWatch.builtInWarning(0));
        assertTrue(BlindSpotWatch.builtInWarning(1));
        assertTrue(BlindSpotWatch.builtInWarning(254));
        assertFalse(BlindSpotWatch.builtInWarning(255));
    }

    @Test
    public void lcmaLampCountsPerSide() {
        assertFalse(BlindSpotWatch.lcmaWarns(null, VehicleState.TURN_LEFT));
        assertFalse(BlindSpotWatch.lcmaWarns(0, VehicleState.TURN_LEFT));
        assertTrue(BlindSpotWatch.lcmaWarns(VehicleState.TURN_LEFT, VehicleState.TURN_LEFT));
        assertFalse(BlindSpotWatch.lcmaWarns(VehicleState.TURN_LEFT, VehicleState.TURN_RIGHT));
        assertTrue(BlindSpotWatch.lcmaWarns(VehicleState.TURN_LEFT | VehicleState.TURN_RIGHT, VehicleState.TURN_RIGHT));
    }

    @Test
    public void alertSidesNeedSpeedOrSignal() {
        int L = VehicleState.TURN_LEFT, R = VehicleState.TURN_RIGHT;
        // fast enough: every warned side
        assertEquals(L | R, BlindSpotWatch.alertSides(L | R, 0, false, 70f, 60));
        // slow, no signal: none
        assertEquals(0, BlindSpotWatch.alertSides(L, 0, false, 30f, 60));
        // slow, signalling toward it: that side only
        assertEquals(R, BlindSpotWatch.alertSides(L | R, R, false, 30f, 60));
        assertEquals(0, BlindSpotWatch.alertSides(L, R, false, 30f, 60));
        // unknown speed or min 0 does not block
        assertEquals(L, BlindSpotWatch.alertSides(L, 0, false, null, 60));
        assertEquals(L, BlindSpotWatch.alertSides(L, 0, false, 5f, 0));
        // hazards or nothing warned
        assertEquals(0, BlindSpotWatch.alertSides(L, L, true, 70f, 60));
        assertEquals(0, BlindSpotWatch.alertSides(null, L, false, 70f, 60));
        assertEquals(0, BlindSpotWatch.alertSides(0, L, false, 70f, 60));
    }
}
