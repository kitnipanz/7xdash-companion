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
            case Tones.HORN:
                return context.getString(R.string.announce_sound_horn);
            case Tones.DING_DONG:
                return context.getString(R.string.announce_sound_ding_dong);
            case Tones.ALARM:
                return context.getString(R.string.announce_sound_alarm);
            case Tones.REVERSING:
                return context.getString(R.string.announce_sound_reversing);
            case Tones.ATTENTION:
                return context.getString(R.string.announce_sound_attention);
            default:
                return a.text;
        }
    }
}
