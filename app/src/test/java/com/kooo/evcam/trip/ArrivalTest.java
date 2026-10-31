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
    public void kmLeft() {
        // no route yet: straight x 1.3
        assertEquals(130, Arrival.kmLeft(100, null, 0, null), 0.01);
        // route 150 km, 20 km driven since
        assertEquals(130, Arrival.kmLeft(95, 150.0, 110, 20.0), 0.01);
        // never less than the straight line
        assertEquals(90, Arrival.kmLeft(90, 100.0, 95, 50.0), 0.01);
        // no odometer: keep the route's road / straight ratio
        assertEquals(75, Arrival.kmLeft(50, 150.0, 100, null), 0.01);
    }
}
