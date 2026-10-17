package com.kooo.evcam.hud;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HudNoticeTest {

    private static final String OWN = "lab.sevenx.dash";

    @Test
    public void acceptsAMessageFromAnotherApp() {
        assertTrue(HudNotice.accept("com.whatsapp", OWN, false, false, "Ali", "On my way"));
    }

    @Test
    public void skipsOwnOngoingSummaryAndEmpty() {
        assertFalse(HudNotice.accept(OWN, OWN, false, false, "Recording", "1 min"));
        assertFalse(HudNotice.accept("com.music", OWN, true, false, "Song", "Artist"));
        assertFalse(HudNotice.accept("com.whatsapp", OWN, false, true, "2 messages", null));
        assertFalse(HudNotice.accept("com.whatsapp", OWN, false, false, " ", null));
        assertFalse(HudNotice.accept(null, OWN, false, false, "x", "y"));
    }

    @Test
    public void clipsLongTextAndFlattensLines() {
        assertEquals("a b", HudNotice.clip("a\nb", 10));
        assertEquals("abcd…", HudNotice.clip("abcdefgh", 5));
        assertEquals("", HudNotice.clip(null, 5));
    }

    @Test
    public void placesCardInTheMiddleAwayFromTheSideViews() {
        // 7X HUD: 1440×480
        assertArrayEquals(new int[]{432, 38, 576, 172}, HudNotice.place(1440, 480));
    }
}
