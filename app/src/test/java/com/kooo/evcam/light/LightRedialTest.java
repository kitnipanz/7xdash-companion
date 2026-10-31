package com.kooo.evcam.light;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LightRedialTest {

    @Test
    public void redialsQuicklyFirstThenEveryFifteenSeconds() {
        assertEquals(2_000L, GearLight.redialDelay(0));
        assertEquals(2_000L, GearLight.redialDelay(1));
        assertEquals(5_000L, GearLight.redialDelay(2));
        assertEquals(10_000L, GearLight.redialDelay(3));
        assertEquals(15_000L, GearLight.redialDelay(4));
        assertEquals(15_000L, GearLight.redialDelay(40));
    }

    @Test
    public void afterAFewMissesEveryOtherDialWaitsForTheLight() {
        assertFalse(LightGroup.waitsForIt(0));
        assertFalse(LightGroup.waitsForIt(1));
        assertFalse(LightGroup.waitsForIt(2));
        assertTrue(LightGroup.waitsForIt(3));
        assertFalse(LightGroup.waitsForIt(4));
        assertTrue(LightGroup.waitsForIt(5));
    }
}
