package com.kooo.evcam.input;

import com.kooo.evcam.R;

/** What a saved button does. The key is what gets stored. */
public enum ShortcutAction {

    RECORD("toggle_recording", R.string.shortcut_action_record, R.drawable.ic_record),
    MIRROR("toggle_mirror", R.string.shortcut_action_mirror, R.drawable.ic_rearview),
    /** Super mirror on the driver HUD: open there, or close it when it is already there. */
    MIRROR_HUD("toggle_mirror_hud", R.string.shortcut_action_mirror_hud, R.drawable.ic_mirror_hud),
    BOTH_MIRRORS("toggle_both_mirrors", R.string.shortcut_action_both_mirrors, R.drawable.ic_mirrors),
    APP("open_app", R.string.shortcut_action_app, R.drawable.ic_home),
    DIM("toggle_dim", R.string.shortcut_action_dim, R.drawable.ic_dim),
    HUD("toggle_hud", R.string.shortcut_action_hud, R.drawable.ic_hud),
    SAVE("save_moment", R.string.shortcut_action_save, R.drawable.ic_bookmark),
    LOCK_SAVE("lock_and_save", R.string.shortcut_action_lock_save, R.drawable.ic_lock),
    /** Same as a high-beam flash: lock the moment and save the surround clip to instant captures. */
    INSTANT_CAPTURE("instant_capture", R.string.shortcut_action_instant_capture, R.drawable.ic_flash),
    /** Flip “upload this moment to Google Drive”. Refuses when Drive is not signed in. */
    DRIVE_UPLOAD("toggle_drive_upload", R.string.shortcut_action_drive_upload, R.drawable.ic_drive),
    HOLD_SPEAK("hold_to_speak", R.string.shortcut_action_hold_speak, R.drawable.ic_mic),
    /** One click opens the mic, the next click closes it. For buttons that cannot be held. */
    TOGGLE_SPEAK("click_to_speak", R.string.shortcut_action_toggle_speak, R.drawable.ic_megaphone),
    CABIN_PASSENGER("cabin_passenger", R.string.shortcut_action_cabin, R.drawable.ic_cabin),
    /** Every camera, same as the home Photo card. Does not change page. */
    PHOTO("take_photo", R.string.shortcut_action_photo, R.drawable.ic_photo),
    /** 只拍环视那一路，不换页。 */
    PHOTO_SURROUND("photo_surround", R.string.shortcut_action_photo_surround, R.drawable.ic_surround),
    /** Start or stop the AirPlay receiver. */
    AIRPLAY("toggle_airplay", R.string.shortcut_action_airplay, R.drawable.ic_airplay),
    /** Turn the cabin equalizer on or off. */
    EQ("toggle_eq", R.string.shortcut_action_eq, R.drawable.ic_eq),
    /** 只拍座舱（前、后座舱），不换页。 */
    PHOTO_CABIN("photo_cabin", R.string.shortcut_action_photo_cabin, R.drawable.ic_camera),
    /** Read the floor and bay signs around the car now, same as shifting into P. */
    WHERE_PARKED("where_parked", R.string.shortcut_action_where_parked, R.drawable.ic_parking),
    /** Beta: play what the head unit plays on the outside speaker, or stop it. */
    OUTSIDE_SPEAKER("toggle_outside_speaker", R.string.shortcut_action_outside_speaker, R.drawable.ic_speaker),
    /** Quit the app, same as Quit app in the drawer: stops recording and stays closed until opened again. */
    QUIT("quit_app", R.string.shortcut_action_quit, R.drawable.ic_power),
    /** Floating picker of announcements; tap one to play it on the picked speakers. */
    ANNOUNCE("announce_picker", R.string.shortcut_action_announce, R.drawable.ic_announce),
    /** Plays one announcement straight away. Stored as {@code announce_play:<announcement id>}. */
    ANNOUNCE_PLAY("announce_play", R.string.shortcut_action_announce_play, R.drawable.ic_play),
    /** Beta: the mood light master switch. Off turns the lights off and stops them following the gear. */
    MOOD_LIGHT("toggle_mood_light", R.string.shortcut_action_mood_light, R.drawable.ic_light);

    /** The tabs actions are picked from, in tab order. */
    public enum Group {
        RECORD(R.string.shortcut_group_record, R.drawable.ic_record),
        CAMERA(R.string.shortcut_group_camera, R.drawable.ic_camera),
        SOUND(R.string.shortcut_group_sound, R.drawable.ic_speaker),
        CAR(R.string.shortcut_group_car, R.drawable.ic_home);

        public final int labelRes;
        public final int iconRes;

        Group(int labelRes, int iconRes) {
            this.labelRes = labelRes;
            this.iconRes = iconRes;
        }
    }

    public final String key;
    public final int labelRes;
    public final int iconRes;

    ShortcutAction(String key, int labelRes, int iconRes) {
        this.key = key;
        this.labelRes = labelRes;
        this.iconRes = iconRes;
    }

    public Group group() {
        switch (this) {
            case RECORD:
            case SAVE:
            case LOCK_SAVE:
            case INSTANT_CAPTURE:
            case DRIVE_UPLOAD:
                return Group.RECORD;
            case MIRROR:
            case MIRROR_HUD:
            case BOTH_MIRRORS:
            case CABIN_PASSENGER:
            case PHOTO:
            case PHOTO_SURROUND:
            case PHOTO_CABIN:
                return Group.CAMERA;
            case HOLD_SPEAK:
            case TOGGLE_SPEAK:
            case ANNOUNCE:
            case ANNOUNCE_PLAY:
            case AIRPLAY:
            case EQ:
            case OUTSIDE_SPEAKER:
                return Group.SOUND;
            default:
                return Group.CAR;
        }
    }

    /** The actions on one tab. The picker lists them by name. */
    public static java.util.List<ShortcutAction> in(Group group) {
        java.util.List<ShortcutAction> out = new java.util.ArrayList<>();
        for (ShortcutAction action : values()) {
            if (action.group() == group) {
                out.add(action);
            }
        }
        return out;
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
