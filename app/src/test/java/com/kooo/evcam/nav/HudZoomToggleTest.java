package com.kooo.evcam.nav;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HudZoomToggleTest {

    @Test
    public void normalGoesFar_farGoesBackToNormal() {
        assertEquals(Navigator.HUD_ZOOM_FAR, Navigator.nextHudZoom(0f), 0f);
        assertEquals(0f, Navigator.nextHudZoom(Navigator.HUD_ZOOM_FAR), 0f);
    }

    @Test
    public void anyOtherZoomGoesFarFirst() {
        assertEquals(Navigator.HUD_ZOOM_FAR, Navigator.nextHudZoom(1.5f), 0f);
        assertEquals(Navigator.HUD_ZOOM_FAR, Navigator.nextHudZoom(-1f), 0f);
        assertEquals(0f, Navigator.nextHudZoom(-3f), 0f);
    }
}
