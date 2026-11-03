package com.kooo.evcam.nav;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class HudTurnPlaceTest {

    @Test
    public void staysInsideTheHudAndAboveTheMinimum() {
        assertArrayEquals(new float[] {0.8f, 0.6f, 0.2f, 0.4f}, HudTurnPlace.clamp(0.95f, 0.9f, 0.1f, 0.4f), 1e-6f);
        assertArrayEquals(new float[] {0f, 0f, 1f, 1f}, HudTurnPlace.clamp(-0.5f, -1f, 3f, 2f), 1e-6f);
        assertEquals(HudTurnPlace.MIN_H, HudTurnPlace.clamp(0f, 0f, 0.5f, 0.01f)[3], 1e-6f);
    }

    @Test
    public void placesInPixels() {
        int[] box = HudTurnPlace.place(1920, 640, new float[] {0.25f, 0.5f, 0.5f, 0.25f}, 0, 0);
        assertArrayEquals(new int[] {480, 320, 960, 160}, box);
    }

    @Test
    public void untouchedItSitsWhereTheNoticeCardsDo() {
        int[] notice = com.kooo.evcam.hud.HudNotice.place(1920, 640, 20, 10);
        assertArrayEquals(notice, HudTurnPlace.place(1920, 640, null, 20, 10));
    }

    @Test
    public void storedAndReadBack() {
        float[] f = {0.1f, 0.2f, 0.5f, 0.3f};
        assertArrayEquals(f, HudTurnPlace.restore(HudTurnPlace.store(f)), 1e-4f);
        assertNull(HudTurnPlace.restore(""));
        assertNull(HudTurnPlace.restore("1,2"));
        assertNull(HudTurnPlace.restore("a,b,c,d"));
    }
}
