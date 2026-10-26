package com.kooo.evcam.light;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CarAmbientLightTest {

    @Test
    public void brightnessGoesOnTheCarsOneToTenScale() {
        assertEquals(10, CarAmbientLight.level(100));
        assertEquals(8, CarAmbientLight.level(80));
        assertEquals(5, CarAmbientLight.level(50));
        assertEquals(1, CarAmbientLight.level(1));
        assertEquals(1, CarAmbientLight.level(4));
        assertEquals(10, CarAmbientLight.level(250));
    }

    @Test
    public void sendsBrightnessToEachFunctionNotYetThere() {
        assertEquals(0, CarAmbientLight.stale(new Integer[]{null, 5}, 5));
        assertEquals(1, CarAmbientLight.stale(new Integer[]{5, 3}, 5));
        assertEquals(-1, CarAmbientLight.stale(new Integer[]{5, 5}, 5));
        assertEquals(-1, CarAmbientLight.stale(new Integer[0], 5));
    }
}
