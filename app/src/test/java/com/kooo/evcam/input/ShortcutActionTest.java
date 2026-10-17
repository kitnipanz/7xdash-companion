package com.kooo.evcam.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class ShortcutActionTest {

    @Test
    public void clickToSpeakIsItsOwnStoredAction() {
        assertEquals(ShortcutAction.TOGGLE_SPEAK, ShortcutAction.fromKey("click_to_speak"));
        assertNotEquals(ShortcutAction.HOLD_SPEAK.key, ShortcutAction.TOGGLE_SPEAK.key);
        // Saved hold-to-speak shortcuts keep loading as before.
        assertEquals(ShortcutAction.HOLD_SPEAK, ShortcutAction.fromKey("hold_to_speak"));
    }

    @Test
    public void instantCaptureIsItsOwnStoredAction() {
        assertEquals(ShortcutAction.INSTANT_CAPTURE, ShortcutAction.fromKey("instant_capture"));
        assertEquals(ShortcutAction.LOCK_SAVE, ShortcutAction.fromKey("lock_and_save"));
    }

    @Test
    public void mirrorOnHudIsItsOwnStoredAction() {
        assertEquals(ShortcutAction.MIRROR_HUD, ShortcutAction.fromKey("toggle_mirror_hud"));
        assertEquals(ShortcutAction.MIRROR, ShortcutAction.fromKey("toggle_mirror"));
    }

    @Test
    public void clickToSpeakHasASafetyCap() {
        assertEquals(10L * 60 * 1000, MegaphoneService.TOGGLE_MAX_MS);
    }
}
