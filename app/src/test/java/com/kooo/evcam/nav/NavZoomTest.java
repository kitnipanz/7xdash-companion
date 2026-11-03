package com.kooo.evcam.nav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NavZoomTest {

    @Test
    public void slowIsCloseFastIsFar() {
        assertEquals(18.5f, NavMapPainter.zoomFor(0f, 1_500.0), 1e-4);
        assertEquals(18.5f, NavMapPainter.zoomFor(15f, 1_500.0), 1e-4);
        assertEquals(15.5f, NavMapPainter.zoomFor(130f, 5_000.0), 1e-4);
        float mid = NavMapPainter.zoomFor(57.5f, 5_000.0);
        assertEquals(17.0f, mid, 1e-4);
        assertTrue(NavMapPainter.zoomFor(40f, 5_000.0) > NavMapPainter.zoomFor(80f, 5_000.0));
    }

    @Test
    public void closeInBeforeATurnEvenWhenFast() {
        assertEquals(17.5f, NavMapPainter.zoomFor(90f, 200.0), 1e-4);
        // slower than that already: stays closer
        assertEquals(18.5f, NavMapPainter.zoomFor(10f, 200.0), 1e-4);
    }

    @Test
    public void withoutSpeedTheDistanceDecides() {
        assertEquals(17.5f, NavMapPainter.zoomFor(null, 250.0), 1e-4);
        assertEquals(16.5f, NavMapPainter.zoomFor(null, 1_000.0), 1e-4);
        assertEquals(15.5f, NavMapPainter.zoomFor(null, 9_000.0), 1e-4);
        assertEquals(16.5f, NavMapPainter.zoomFor(null, null), 1e-4);
    }
}
