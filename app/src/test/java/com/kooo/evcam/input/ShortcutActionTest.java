package com.kooo.evcam.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class ShortcutActionTest {

    @Test
    public void clickToSpeakIsItsOwnStoredAction() {
        assertEquals(ShortcutAction.TOGGLE_SPEAK, ShortcutAction.fromKey("click_to_speak"));
        assertNotEquals(ShortcutAction.HOLD_SPEAK.key, ShortcutAction.TOGGLE_SPEAK.key);
        // Saved hold-to-speak shortcuts keep loading as before.
        assertEquals(ShortcutAction.HOLD_SPEAK, ShortcutAction.fromKey("hold_to_speak"));
    }

    @Test
    public void photoAirPlayAndEqAreTheirOwnStoredActions() {
        assertEquals(ShortcutAction.PHOTO, ShortcutAction.fromKey("take_photo"));
        assertEquals(ShortcutAction.AIRPLAY, ShortcutAction.fromKey("toggle_airplay"));
        assertEquals(ShortcutAction.EQ, ShortcutAction.fromKey("toggle_eq"));
        assertNotEquals(ShortcutAction.PHOTO.key, ShortcutAction.PHOTO_SURROUND.key);
        assertNotEquals(ShortcutAction.PHOTO.key, ShortcutAction.PHOTO_CABIN.key);
    }

    @Test
    public void driveUploadIsItsOwnStoredAction() {
        assertEquals(ShortcutAction.DRIVE_UPLOAD, ShortcutAction.fromKey("toggle_drive_upload"));
        assertEquals(ShortcutAction.RECORD, ShortcutAction.fromKey("toggle_recording"));
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
    public void outsideSpeakerIsItsOwnStoredAction() {
        assertEquals(ShortcutAction.OUTSIDE_SPEAKER, ShortcutAction.fromKey("toggle_outside_speaker"));
        assertNotEquals(ShortcutAction.OUTSIDE_SPEAKER.key, ShortcutAction.TOGGLE_SPEAK.key);
    }

    @Test
    public void quitIsItsOwnStoredAction() {
        assertEquals(ShortcutAction.QUIT, ShortcutAction.fromKey("quit_app"));
    }

    @Test
    public void announcePickerIsItsOwnStoredAction() {
        assertEquals(ShortcutAction.ANNOUNCE, ShortcutAction.fromKey("announce_picker"));
    }

    @Test
    public void announcePlayCarriesWhichAnnouncement() {
        String key = ShortcutAction.keyWith(ShortcutAction.ANNOUNCE_PLAY, "sound-siren");
        assertEquals("announce_play:sound-siren", key);
        assertEquals(ShortcutAction.ANNOUNCE_PLAY, ShortcutAction.fromKey(key));
        assertEquals("sound-siren", ShortcutAction.choice(key));
        assertEquals("", ShortcutAction.choice("quit_app"));
        // other actions never take a choice
        assertEquals(null, ShortcutAction.fromKey("quit_app:x"));
    }

    @Test
    public void clickToSpeakHasASafetyCap() {
        assertEquals(10L * 60 * 1000, MegaphoneService.TOGGLE_MAX_MS);
    }

    @Test
    public void moodLightSwitchHasItsOwnKey() {
        assertEquals(ShortcutAction.MOOD_LIGHT, ShortcutAction.fromKey("toggle_mood_light"));
    }

    @Test
    public void everyActionIsOnExactlyOneTab() {
        Set<ShortcutAction> seen = new HashSet<>();
        for (ShortcutAction.Group group : ShortcutAction.Group.values()) {
            for (ShortcutAction action : ShortcutAction.in(group)) {
                assertTrue(action + " listed twice", seen.add(action));
            }
            assertTrue(group + " is empty", !ShortcutAction.in(group).isEmpty());
        }
        assertEquals(ShortcutAction.values().length, seen.size());
        assertEquals(ShortcutAction.Group.RECORD, ShortcutAction.INSTANT_CAPTURE.group());
        assertEquals(ShortcutAction.Group.SOUND, ShortcutAction.OUTSIDE_SPEAKER.group());
        assertEquals(ShortcutAction.Group.LIGHTS, ShortcutAction.MOOD_LIGHT.group());
        assertEquals(ShortcutAction.Group.LIGHTS, ShortcutAction.DIM.group());
        assertEquals(ShortcutAction.Group.LIGHTS, ShortcutAction.FOG_LAMP.group());
        assertEquals(ShortcutAction.Group.SOUND, ShortcutAction.VOLUME_UP.group());
        assertEquals(ShortcutAction.Group.SOUND, ShortcutAction.VOLUME_DOWN.group());
        assertEquals(ShortcutAction.FOG_LAMP, ShortcutAction.fromKey("toggle_fog_lamp"));
    }
}
