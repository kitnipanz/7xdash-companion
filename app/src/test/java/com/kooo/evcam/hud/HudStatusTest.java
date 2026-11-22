package com.kooo.evcam.hud;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HudStatusTest {

    @Test
    public void defaultsSitOnTheRightInsideTheVisiblePart() {
        float[] f = HudStatus.defaults(20);
        assertEquals(0.8f, f[0] + f[2], 1e-4f);
        assertTrue(f[0] > 0.5f);
    }

    @Test
    public void defaultsWithNoHiddenEdgeTouchTheRight() {
        float[] f = HudStatus.defaults(0);
        assertEquals(1f, f[0] + f[2], 1e-4f);
    }

    @Test
    public void clampKeepsTheBoxInside() {
        float[] f = HudStatus.clamp(0.95f, -0.2f, 0.3f, 0.01f);
        assertEquals(0.7f, f[0], 1e-4f);
        assertEquals(0f, f[1], 1e-4f);
        assertEquals(HudStatus.MIN_H, f[3], 1e-4f);
    }

    @Test
    public void storeAndRestoreRoundTrip() {
        float[] f = {0.6f, 0.1f, 0.2f, 0.5f};
        assertArrayEquals(f, HudStatus.restore(HudStatus.store(f)), 1e-4f);
    }

    @Test
    public void restoreRejectsJunk() {
        assertNull(HudStatus.restore(""));
        assertNull(HudStatus.restore(null));
        assertNull(HudStatus.restore("1,2,3"));
        assertNull(HudStatus.restore("a,b,c,d"));
    }

    @Test
    public void placeOrFallsBackToDefaults() {
        assertArrayEquals(HudStatus.defaults(20), HudStatus.placeOr("junk", 20), 1e-4f);
    }

    @Test
    public void toPixelsOnThe7xHud() {
        int[] box = HudStatus.toPixels(1440, 480, new float[] {0.6f, 0.08f, 0.2f, 0.6f});
        assertArrayEquals(new int[] {864, 38, 288, 288}, box);
    }

    @Test
    public void durationFormats() {
        assertEquals("0:00", HudStatus.duration(-5));
        assertEquals("4:05", HudStatus.duration(245_000));
        assertEquals("1:04:05", HudStatus.duration(3_845_000));
    }

    @Test
    public void sizeFormats() {
        assertEquals("512 MB", HudStatus.size(512_000_000L));
        assertEquals("23.4 GB", HudStatus.size(23_400_000_000L));
        assertEquals("128 GB", HudStatus.size(128_000_000_000L));
    }

    @Test
    public void recordingIsOnByDefault() {
        assertTrue(HudStatus.defaultOn(HudStatus.Item.RECORDING));
        assertTrue(!HudStatus.defaultOn(HudStatus.Item.CLOCK));
    }

    @Test
    public void announcementIsOnByDefault() {
        assertTrue(HudStatus.defaultOn(HudStatus.Item.ANNOUNCE));
    }

    @Test
    public void clipKeepsShortPhrasesAndCutsLongOnes() {
        assertEquals("Thank you", HudStatus.clip("Thank you"));
        assertEquals("one two", HudStatus.clip("one\ntwo"));
        String cut = HudStatus.clip("Please move your car, you are blocking the exit");
        assertEquals(HudStatus.ANNOUNCE_MAX_CHARS, cut.length());
        assertTrue(cut.endsWith("…"));
        assertEquals("", HudStatus.clip(null));
    }
}
