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

    @Test
    public void pacedLikeABluetoothLight_withAWaitAfterPowerOn() {
        assertEquals(40L, CarAmbientLight.gapAfter(false));
        assertEquals(200L, CarAmbientLight.gapAfter(true));
    }

    @Test
    public void turningOnInANewColour_theColourGoesBeforeThePower() {
        // off, showing blue; wanted on in red
        assertEquals(true, CarAmbientLight.colorFirst(0, 1, 0x0000FF, 0xFF0000, null));
        // unknown power counts as not on yet
        assertEquals(true, CarAmbientLight.colorFirst(null, 1, null, 0xFF0000, null));
    }

    @Test
    public void colourGoesAheadOnlyOnce_andOnlyWhenItIsNeeded() {
        // already sent ahead: the power is next
        assertEquals(false, CarAmbientLight.colorFirst(0, 1, 0x0000FF, 0xFF0000, 0xFF0000));
        // the car has this colour (every blink after the first)
        assertEquals(false, CarAmbientLight.colorFirst(0, 1, 0xFF0000, 0xFF0000, null));
        // already on, staying off, or no colour wanted
        assertEquals(false, CarAmbientLight.colorFirst(1, 1, 0x0000FF, 0xFF0000, null));
        assertEquals(false, CarAmbientLight.colorFirst(0, 0, 0x0000FF, 0xFF0000, null));
        assertEquals(false, CarAmbientLight.colorFirst(0, null, 0x0000FF, 0xFF0000, null));
        assertEquals(false, CarAmbientLight.colorFirst(0, 1, 0x0000FF, null, null));
    }
}
