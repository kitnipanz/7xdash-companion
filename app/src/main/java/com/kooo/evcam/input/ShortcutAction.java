package com.kooo.evcam.input;

import com.kooo.evcam.R;

/** What a saved button does. The key is what gets stored. */
public enum ShortcutAction {

    RECORD("toggle_recording", R.string.shortcut_action_record),
    MIRROR("toggle_mirror", R.string.shortcut_action_mirror),
    /** Super mirror on the driver HUD: open there, or close it when it is already there. */
    MIRROR_HUD("toggle_mirror_hud", R.string.shortcut_action_mirror_hud),
    BOTH_MIRRORS("toggle_both_mirrors", R.string.shortcut_action_both_mirrors),
    APP("open_app", R.string.shortcut_action_app),
    DIM("toggle_dim", R.string.shortcut_action_dim),
    HUD("toggle_hud", R.string.shortcut_action_hud),
    SAVE("save_moment", R.string.shortcut_action_save),
    LOCK_SAVE("lock_and_save", R.string.shortcut_action_lock_save),
    /** Same as a high-beam flash: lock the moment and save the surround clip to instant captures. */
    INSTANT_CAPTURE("instant_capture", R.string.shortcut_action_instant_capture),
    /** Flip “upload this moment to Google Drive”. Refuses when Drive is not signed in. */
    DRIVE_UPLOAD("toggle_drive_upload", R.string.shortcut_action_drive_upload),
    HOLD_SPEAK("hold_to_speak", R.string.shortcut_action_hold_speak),
    /** One click opens the mic, the next click closes it. For buttons that cannot be held. */
    TOGGLE_SPEAK("click_to_speak", R.string.shortcut_action_toggle_speak),
    CABIN_PASSENGER("cabin_passenger", R.string.shortcut_action_cabin),
    /** Every camera, same as the home Photo card. Does not change page. */
    PHOTO("take_photo", R.string.shortcut_action_photo),
    /** 只拍环视那一路，不换页。 */
    PHOTO_SURROUND("photo_surround", R.string.shortcut_action_photo_surround),
    /** Start or stop the AirPlay receiver. */
    AIRPLAY("toggle_airplay", R.string.shortcut_action_airplay),
    /** Turn the cabin equalizer on or off. */
    EQ("toggle_eq", R.string.shortcut_action_eq),
    /** 只拍座舱（前、后座舱），不换页。 */
    PHOTO_CABIN("photo_cabin", R.string.shortcut_action_photo_cabin),
    /** Read the floor and bay signs around the car now, same as shifting into P. */
    WHERE_PARKED("where_parked", R.string.shortcut_action_where_parked),
    /** Beta: play what the head unit plays on the outside speaker, or stop it. */
    OUTSIDE_SPEAKER("toggle_outside_speaker", R.string.shortcut_action_outside_speaker),
    /** Quit the app, same as Quit app in the drawer: stops recording and stays closed until opened again. */
    QUIT("quit_app", R.string.shortcut_action_quit),
    /** Floating picker of announcements; tap one to play it on the picked speakers. */
    ANNOUNCE("announce_picker", R.string.shortcut_action_announce),
    /** Plays one announcement straight away. Stored as {@code announce_play:<announcement id>}. */
    ANNOUNCE_PLAY("announce_play", R.string.shortcut_action_announce_play);

    public final String key;
    public final int labelRes;

    ShortcutAction(String key, int labelRes) {
        this.key = key;
        this.labelRes = labelRes;
    }

    public static ShortcutAction fromKey(String key) {
        if (key == null) {
            return null;
        }
        for (ShortcutAction action : values()) {
            if (action.key.equals(key)) {
                return action;
            }
        }
        if (key.startsWith(ANNOUNCE_PLAY.key + ":")) {
            return ANNOUNCE_PLAY;
        }
        return null;
    }

    /** Stored key of an action that carries a choice, e.g. which announcement to play. */
    public static String keyWith(ShortcutAction action, String choice) {
        return action.key + ":" + choice;
    }

    /** The choice in a stored key ({@link #keyWith}), or "" when it has none. */
    public static String choice(String key) {
        int colon = key == null ? -1 : key.indexOf(':');
        return colon < 0 ? "" : key.substring(colon + 1);
    }
}
