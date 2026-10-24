package com.kooo.evcam.announce;

import android.content.Context;

import com.kooo.evcam.R;

/** What a button or row says for an announcement: the sound's name in the app language, or the phrase. */
final class Labels {

    private Labels() {
    }

    static String of(Context context, Announcement a) {
        if (!a.isSound()) {
            return a.text;
        }
        switch (a.sound) {
            case Tones.CHIME:
                return context.getString(R.string.announce_sound_chime);
            case Tones.BEEP:
                return context.getString(R.string.announce_sound_beep);
            case Tones.SIREN:
                return context.getString(R.string.announce_sound_siren);
            default:
                return a.text;
        }
    }
}
