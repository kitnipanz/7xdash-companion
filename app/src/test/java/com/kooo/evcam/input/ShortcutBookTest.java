package com.kooo.evcam.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class ShortcutBookTest {

    @Test
    public void roundTripKeepsTheDeviceNameAndPress() {
        Shortcut item = new Shortcut(96, 12, "AB Shutter, 3", "toggle_recording", "long");
        String raw = ShortcutBook.write(Collections.singletonList(item));
        Shortcut back = ShortcutBook.parse(raw).get(0);
        assertEquals(96, back.keyCode);
        assertEquals(12, back.scanCode);
        assertEquals("AB Shutter, 3", back.deviceName);
        assertEquals("toggle_recording", back.action);
        assertEquals("long", back.press);
    }

    @Test
    public void oldFourFieldLinesAreTaps() {
        Shortcut back = ShortcutBook.parse("96,0,toggle_dim,Pad").get(0);
        assertEquals("toggle_dim", back.action);
        assertEquals("Pad", back.deviceName);
        assertEquals("tap", back.press);
    }

    @Test
    public void sameButtonDifferentPressKeepsBoth() {
        Shortcut tap = new Shortcut(96, 0, "Pad", "toggle_recording", "tap");
        Shortcut hold = new Shortcut(96, 0, "Pad", "toggle_dim", "long");
        java.util.List<Shortcut> items = ShortcutBook.put(Collections.singletonList(tap), hold);
        assertEquals(2, items.size());
    }

    @Test
    public void sameButtonSamePressReplacesTheAction() {
        Shortcut first = new Shortcut(96, 0, "Pad", "toggle_recording", "tap");
        Shortcut second = new Shortcut(96, 0, "Pad", "toggle_dim", "tap");
        java.util.List<Shortcut> items = ShortcutBook.put(Collections.singletonList(first), second);
        assertEquals(1, items.size());
        assertEquals("toggle_dim", items.get(0).action);
    }

    @Test
    public void matchUsesKeyCodeDeviceAndPress() {
        Shortcut pad = new Shortcut(96, 0, "Pad", "open_app", "double");
        assertEquals("open_app",
                ShortcutBook.match(Collections.singletonList(pad), 96, 0, "Pad", PressKind.DOUBLE).action);
        assertNull(ShortcutBook.match(Collections.singletonList(pad), 96, 0, "Pad", PressKind.TAP));
        assertNull(ShortcutBook.match(Collections.singletonList(pad), 96, 0, "Other", PressKind.DOUBLE));
    }

    @Test
    public void unknownKeyMatchesTheScanCode() {
        Shortcut item = new Shortcut(0, 88, "Button", "toggle_dim");
        assertEquals("toggle_dim", ShortcutBook.match(Arrays.asList(item), 0, 88, "Button").action);
        assertNull(ShortcutBook.match(Arrays.asList(item), 0, 1, "Button"));
    }

    @Test
    public void hudActionRoundTrips() {
        Shortcut item = new Shortcut(96, 0, "Pad", "toggle_hud", "tap");
        Shortcut back = ShortcutBook.parse(ShortcutBook.write(Collections.singletonList(item))).get(0);
        assertEquals("toggle_hud", back.action);
        assertEquals(ShortcutAction.HUD, ShortcutAction.fromKey(back.action));
    }

    @Test
    public void lockAndSaveActionRoundTrips() {
        Shortcut item = new Shortcut(48, 0, "qwerty2", "lock_and_save", "tap");
        Shortcut back = ShortcutBook.parse(ShortcutBook.write(Collections.singletonList(item))).get(0);
        assertEquals("lock_and_save", back.action);
        assertEquals(ShortcutAction.LOCK_SAVE, ShortcutAction.fromKey(back.action));
    }

    public void bothMirrorsActionRoundTrips() {
        Shortcut item = new Shortcut(96, 0, "Pad", "toggle_both_mirrors", "tap");
        Shortcut back = ShortcutBook.parse(ShortcutBook.write(Collections.singletonList(item))).get(0);
        assertEquals("toggle_both_mirrors", back.action);
    }

    @Test
    public void holdToSpeakMatchesThatButtonAtAnyPressKind() {
        Shortcut item = new Shortcut(24, 0, "Wheel", "hold_to_speak", "long");
        assertTrue(ShortcutBook.holdToSpeak(Collections.singletonList(item), 24, 0, "Wheel"));
        assertFalse(ShortcutBook.holdToSpeak(Collections.singletonList(item), 25, 0, "Wheel"));
        Shortcut back = ShortcutBook.parse(ShortcutBook.write(Collections.singletonList(item))).get(0);
        assertEquals(ShortcutAction.HOLD_SPEAK, ShortcutAction.fromKey(back.action));
    }

    @Test
    public void brokenLinesAreSkipped() {
        assertEquals(0, ShortcutBook.parse("nope\n1,2,not_an_action,Pad").size());
    }
}
