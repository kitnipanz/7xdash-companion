package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BlindSpotClockTest {

    private static final long H = BlindSpotWatch.BLINK_HALF_MS;

    @Test
    public void edgeAndLightsReadOneClock_soAnyTwoCallersAgreeAtTheSameMoment() {
        // the red edge starting at 1000 ms and the lights starting at 1200 ms are red together
        for (long t = 5_000; t < 8_000; t += 50) {
            assertEquals(BlindSpotWatch.redHalfAt(t), BlindSpotWatch.redHalfAt(t));
        }
        assertTrue(BlindSpotWatch.redHalfAt(0));
        assertFalse(BlindSpotWatch.redHalfAt(H));
        assertTrue(BlindSpotWatch.redHalfAt(2 * H + 1));
    }

    @Test
    public void untilFlip_landsOnTheNextHalf() {
        assertEquals(H, BlindSpotWatch.untilFlip(0));
        assertEquals(1, BlindSpotWatch.untilFlip(H - 1));
        long t = 12_345;
        assertEquals(0, (t + BlindSpotWatch.untilFlip(t)) % H);
    }
}
