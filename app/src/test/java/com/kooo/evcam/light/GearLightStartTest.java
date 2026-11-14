package com.kooo.evcam.light;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GearLightStartTest {

    @Test
    public void lightsAreDialledFromAppStartBeforeTheGearIsKnown() {
        assertTrue(GearLight.awake(null, 0L));
        assertTrue(GearLight.awake(null, GearLight.START_HOLD_MS - 1));
    }

    @Test
    public void noGearAfterTheStartHoldLetsTheLinksGo() {
        assertFalse(GearLight.awake(null, GearLight.START_HOLD_MS));
    }

    @Test
    public void aKnownGearHoldsTheLinksAtAnyTime() {
        assertTrue(GearLight.awake("P", 0L));
        assertTrue(GearLight.awake("D", GearLight.START_HOLD_MS * 10));
    }
}
