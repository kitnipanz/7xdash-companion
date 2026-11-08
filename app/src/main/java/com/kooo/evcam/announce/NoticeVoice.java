package com.kooo.evcam.announce;

import android.content.Context;

import com.kooo.evcam.AppConfig;

import java.util.Locale;

/** Says notifications aloud on the speaker picked, meant for the driver's headrest. Off by default. */
public final class NoticeVoice {

    private NoticeVoice() {
    }

    public static void say(Context context, String text) {
        AppConfig config = new AppConfig(context);
        if (config.isNoticeSpeakOn() && !config.getNoticeSpeaker().isEmpty()) {
            Announcer.say(context, text, config.getNoticeSpeaker(), config.getNoticeVolume(), null);
        }
    }

    /** The car's name for its headrest speaker is not known yet: any that sounds like one goes first in the list. */
    static boolean soundsLikeHeadrest(String address) {
        String name = address.toUpperCase(Locale.ROOT);
        return name.contains("HEAD") || name.contains("DRIVER");
    }
}
