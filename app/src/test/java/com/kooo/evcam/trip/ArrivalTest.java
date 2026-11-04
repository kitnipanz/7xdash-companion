package com.kooo.evcam.trip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ArrivalTest {

    @Test
    public void shareOfRangeLeft() {
        Arrival.Estimate e = Arrival.estimate(80f, 400f, 100);
        assertEquals(60f, e.percent, 0.01f);
        assertEquals(300, e.spareKm, 0.01);
        assertEquals(Arrival.Level.OK, e.level);
    }

    @Test
    public void lowAndShort() {
        assertEquals(Arrival.Level.LOW, Arrival.estimate(40f, 200f, 170).level);
        Arrival.Estimate e = Arrival.estimate(20f, 100f, 140);
        assertEquals(Arrival.Level.SHORT, e.level);
        assertEquals(-40, e.spareKm, 0.01);
    }

    @Test
    public void needsBatteryAndRange() {
        assertNull(Arrival.estimate(null, 300f, 10));
        assertNull(Arrival.estimate(50f, null, 10));
        assertNull(Arrival.estimate(50f, 0f, 10));
    }

    @Test
    public void straightLine() {
        // Victory Monument to Pattaya: 0.84° north-south (93.6 km) and 0.34° east-west (37.3 km)
        assertEquals(100.7, Arrival.straightKm(13.7650, 100.5383, 12.9236, 100.8825), 0.5);
    }

    @Test
    public void bearing() {
        assertEquals(0, Arrival.bearing(13.0, 100.0, 13.1, 100.0), 0.1);
        assertEquals(90, Arrival.bearing(13.0, 100.0, 13.0, 100.1), 0.1);
        assertEquals(180, Arrival.bearing(13.1, 100.0, 13.0, 100.0), 0.1);
        assertEquals(270, Arrival.bearing(13.0, 100.1, 13.0, 100.0), 0.1);
    }
}
