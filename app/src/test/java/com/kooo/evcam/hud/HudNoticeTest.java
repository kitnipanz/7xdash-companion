package com.kooo.evcam.hud;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HudNoticeTest {

    @Test
    public void clipsLongTextAndFlattensLines() {
        assertEquals("a b", HudNotice.clip("a\nb", 10));
        assertEquals("abcd…", HudNotice.clip("abcdefgh", 5));
        assertEquals("", HudNotice.clip(null, 5));
    }

    @Test
    public void placesCardTopLeft() {
        // 7X HUD: 1440×480。左上角只留 8 的空，和侧视贴边一样
        assertArrayEquals(new int[]{8, 8, 576, 172}, HudNotice.place(1440, 480));
    }

    @Test
    public void cardStaysInsideTheHud() {
        for (int[] size : new int[][]{{1440, 480}, {800, 120}, {40, 20}, {2, 2}}) {
            int[] box = HudNotice.place(size[0], size[1]);
            assertTrue(box[0] >= 0 && box[1] >= 0 && box[2] > 0 && box[3] > 0);
            assertTrue(box[0] + box[2] <= size[0]);
            assertTrue(box[1] + box[3] <= size[1]);
        }
    }
}
