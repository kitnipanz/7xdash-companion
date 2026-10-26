package com.kooo.evcam.light;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SpeedSimulatorTest {

    @Test
    public void throttleOnlyMovesInDOrR() {
        SpeedSimulator car = new SpeedSimulator();
        car.setThrottle(true);
        car.step(1000);
        assertEquals(0f, car.speedKmh(), 0.01f);
        car.setGear("D");
        car.step(1000);
        assertEquals(SpeedSimulator.THROTTLE_KMH_PER_S, car.speedKmh(), 0.01f);
    }

    @Test
    public void coastAndBrakeSlowDownToZero() {
        SpeedSimulator car = new SpeedSimulator();
        car.setGear("D");
        car.setThrottle(true);
        car.step(5000);
        car.setThrottle(false);
        car.step(1000);
        assertEquals(5 * SpeedSimulator.THROTTLE_KMH_PER_S - SpeedSimulator.COAST_KMH_PER_S, car.speedKmh(), 0.01f);
        car.setBrake(true);
        car.step(10_000);
        assertEquals(0f, car.speedKmh(), 0.01f);
        assertFalse(car.moving());
    }

    @Test
    public void capsAndParkStops() {
        SpeedSimulator car = new SpeedSimulator();
        car.setGear("R");
        car.setThrottle(true);
        car.step(60_000);
        assertEquals(SpeedSimulator.MAX_REVERSE_KMH, car.speedKmh(), 0.01f);
        car.setGear("D");
        car.step(60_000);
        assertEquals(SpeedSimulator.MAX_KMH, car.speedKmh(), 0.01f);
        assertTrue(car.moving());
        car.setGear("P");
        assertEquals(0f, car.speedKmh(), 0.01f);
    }
}
