package com.kooo.evcam.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CabinEqTest {

    @Test
    public void fifteenBandsRiseToTwentyKilohertz() {
        assertEquals(15, CabinEq.CENTRES_HZ.length);
        float[] edges = CabinEq.cutoffs();
        assertEquals(15, edges.length);
        for (int i = 1; i < edges.length; i++) {
            assertTrue(edges[i] > edges[i - 1]);
        }
        assertEquals(20000f, edges[14], 0.1f);
        assertEquals("25", CabinEq.label(25));
        assertEquals("1k", CabinEq.label(1000));
        assertEquals("1.6k", CabinEq.label(1600));
        assertEquals("6.3k", CabinEq.label(6300));
        assertEquals("16k", CabinEq.label(16000));
    }

    @Test
    public void curveRoundTripsAndABrokenLineIsFlat() {
        float[] curve = CabinEq.flat();
        curve[0] = -12f;
        curve[7] = 4f;
        curve[14] = 12f;
        float[] back = CabinEq.read(CabinEq.write(curve));
        assertEquals(-12f, back[0], 0.1f);
        assertEquals(4f, back[7], 0.1f);
        assertEquals(12f, back[14], 0.1f);
        assertEquals(0f, CabinEq.read("1,2,3")[0], 0.1f);
        assertEquals(0f, CabinEq.read("nope,0,0,0,0,0,0,0,0,0,0,0,0,0,0")[3], 0.1f);
    }

    @Test
    public void gainStaysInsideTheSlider() {
        assertEquals(-12f, CabinEq.clamp(-40f), 0.1f);
        assertEquals(12f, CabinEq.clamp(40f), 0.1f);
        assertEquals("+3", CabinEq.formatDb(3.2f));
        assertEquals("-2", CabinEq.formatDb(-2.2f));
        assertEquals("0", CabinEq.formatDb(0f));
    }
}
