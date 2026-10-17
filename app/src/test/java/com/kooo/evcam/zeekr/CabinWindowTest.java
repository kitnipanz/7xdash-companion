package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CabinWindowTest {

    @Test
    public void sizeStaysBetweenTheMinimumAndTheScreen() {
        assertEquals(CabinWindow.MIN_PX, CabinWindow.clamp(10, 1920));
        assertEquals(720, CabinWindow.clamp(720, 1920));
        assertEquals(1920, CabinWindow.clamp(4000, 1920));
        assertEquals(CabinWindow.MIN_PX, CabinWindow.clamp(50, 80));
    }

    @Test
    public void originStaysOnScreen() {
        assertEquals(0, CabinWindow.clampOrigin(-20, 720, 1920));
        assertEquals(100, CabinWindow.clampOrigin(100, 720, 1920));
        assertEquals(1200, CabinWindow.clampOrigin(5000, 720, 1920));
        assertEquals(0, CabinWindow.clampOrigin(40, 1920, 1920));
    }
}
