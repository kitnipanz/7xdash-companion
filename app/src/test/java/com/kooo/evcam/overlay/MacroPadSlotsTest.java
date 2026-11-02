package com.kooo.evcam.overlay;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.kooo.evcam.input.ShortcutAction;

import org.junit.Test;

public class MacroPadSlotsTest {

    @Test
    public void neverSetGivesDefaultsThatAreRealActions() {
        String[] slots = MacroPadSlots.parse(null);
        assertEquals(MacroPadSlots.COUNT, slots.length);
        for (String key : slots) {
            assertEquals(key, key, ShortcutAction.fromKey(key).key);
        }
    }

    @Test
    public void roundTripKeepsEmptiesAndChoices() {
        String[] slots = {"toggle_recording", "", "announce_play:abc", "", "toggle_dim", ""};
        assertArrayEquals(slots, MacroPadSlots.parse(MacroPadSlots.write(slots)));
    }

    @Test
    public void clearedPadStaysEmpty() {
        String[] empty = MacroPadSlots.parse(MacroPadSlots.write(new String[MacroPadSlots.COUNT]));
        for (String key : empty) {
            assertEquals("", key);
        }
    }

    @Test
    public void withSetsOneButton() {
        String[] slots = MacroPadSlots.with(MacroPadSlots.parse(null), 1, "");
        assertEquals("", slots[1]);
        assertEquals("toggle_recording", slots[0]);
        assertEquals("take_photo", MacroPadSlots.parse(null)[1]);
        assertEquals("volume_up", MacroPadSlots.with(slots, 5, "volume_up")[5]);
    }

    @Test
    public void shortOrLongInputIsPadded() {
        assertEquals(MacroPadSlots.COUNT, MacroPadSlots.parse("toggle_dim").length);
        assertEquals("toggle_dim", MacroPadSlots.parse("toggle_dim")[0]);
        assertEquals(MacroPadSlots.COUNT, MacroPadSlots.parse("a\nb\nc\nd\ne\nf\ng\nh").length);
    }
}
