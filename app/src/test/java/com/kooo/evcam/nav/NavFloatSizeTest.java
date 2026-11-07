package com.kooo.evcam.nav;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NavFloatSizeTest {

    @Test
    public void draggingTheTopLeftInwardShrinks_outwardGrows() {
        assertEquals(400, NavFloatWindow.sizeFromStart(100, 500, 100, 300));
        assertEquals(550, NavFloatWindow.sizeFromStart(100, 500, -50, 300));
    }

    @Test
    public void neverSmallerThanTheLeast() {
        assertEquals(300, NavFloatWindow.sizeFromStart(100, 500, 400, 300));
    }

    @Test
    public void stopsAtTheScreenEdge() {
        assertEquals(600, NavFloatWindow.sizeFromStart(100, 500, -400, 300));
    }

    @Test
    public void aWindowPastTheEdgeDoesNotGrowFurther() {
        assertEquals(500, NavFloatWindow.sizeFromStart(-40, 500, -100, 300));
    }
}
