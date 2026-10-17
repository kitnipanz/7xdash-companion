package com.kooo.evcam.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class SpeakPlanTest {

    @Test
    public void defaultsStayHoldFullVolumeAndTheOutsideBus() {
        assertFalse(SpeakPlan.isOnce(null));
        assertFalse(SpeakPlan.isOnce(SpeakPlan.MODE_HOLD));
        assertTrue(SpeakPlan.isOnce(SpeakPlan.MODE_ONCE));
        assertEquals(Megaphone.GAIN, SpeakPlan.gain(100));
        assertEquals(Megaphone.GAIN / 2, SpeakPlan.gain(50));
        assertEquals(0, SpeakPlan.gain(0));
        assertEquals(10, SpeakPlan.seconds(7));
        assertEquals(20, SpeakPlan.seconds(20));
        assertEquals(Megaphone.BUS_ADDRESS, SpeakPlan.speaker(""));
        assertEquals("BUS11_OUTER_SPEAKER_PLAYBACK", SpeakPlan.speaker("BUS11_OUTER_SPEAKER_PLAYBACK"));
    }

    @Test
    public void settingTheSpeakButtonReplacesOnlyThatAction() {
        Shortcut record = new Shortcut(96, 0, "Pad", "toggle_recording", "tap");
        Shortcut oldSpeak = new Shortcut(24, 0, "Wheel", "hold_to_speak", "long");
        Shortcut next = new Shortcut(25, 0, "Wheel", "hold_to_speak", "tap");
        java.util.List<Shortcut> saved = ShortcutBook.replaceAction(Arrays.asList(record, oldSpeak), next);
        assertEquals(2, saved.size());
        assertEquals("toggle_recording", saved.get(0).action);
        assertEquals(25, saved.get(1).keyCode);
        assertEquals("tap", saved.get(1).press);
        assertEquals(1, ShortcutBook.replaceAction(Collections.emptyList(), next).size());
    }
}
