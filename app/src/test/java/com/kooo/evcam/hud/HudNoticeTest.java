package com.kooo.evcam.hud;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

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
        // 7X HUD: 1440×480
        assertArrayEquals(new int[]{43, 38, 576, 172}, HudNotice.place(1440, 480));
    }
}
