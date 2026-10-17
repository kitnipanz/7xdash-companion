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
        assertArrayEquals(new int[]{8, 8, 1424, 172}, HudNotice.place(1440, 480));
    }

    @Test
    public void leftOffsetMovesTheCardIn() {
        // 7X 默认往里挪 20%：1440 的 20% 是 288
        assertArrayEquals(new int[]{288, 8, 1144, 172}, HudNotice.place(1440, 480, 20));
        assertArrayEquals(new int[]{8, 8, 1424, 172}, HudNotice.place(1440, 480, 0));
        // default 20% in from both sides: 288 .. 1152
        assertArrayEquals(new int[]{288, 8, 864, 172}, HudNotice.place(1440, 480, 20, 20));
    }

    @Test
    public void cardStaysInsideTheHud() {
        for (int[] size : new int[][]{{1440, 480}, {800, 120}, {40, 20}, {2, 2}}) {
            for (int left : new int[]{0, 20, 60, 90}) {
                int[] box = HudNotice.place(size[0], size[1], left, 90 - left);
                assertTrue(box[0] >= 0 && box[1] >= 0 && box[2] > 0 && box[3] > 0);
                assertTrue(box[0] + box[2] <= size[0]);
                assertTrue(box[1] + box[3] <= size[1]);
            }
        }
    }
}
