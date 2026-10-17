package com.kooo.evcam.input;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The name the user gave each smart sticker ("Steering left", "Door"). Both stickers advertise
 * as ZEEKR_button, so without a name they are told apart only by their address tail.
 */
public final class StickerNames {

    private static final String PREFS = "sticker_names";
    /** Long enough for a short label, short enough for the shortcut list and the card. */
    public static final int MAX_LENGTH = 24;

    private StickerNames() {
    }

    /** The user's name for this sticker, or "" if none. */
    public static String alias(Context context, String address) {
        if (context == null) {
            return "";
        }
        return prefs(context).getString(StickerDevices.normalize(address), "");
    }

    /** Blank clears the name. */
    public static void setAlias(Context context, String address, String alias) {
        if (context == null) {
            return;
        }
        String mac = StickerDevices.normalize(address);
        String clean = clean(alias);
        SharedPreferences.Editor edit = prefs(context).edit();
        if (clean.isEmpty()) {
            edit.remove(mac);
        } else {
            edit.putString(mac, clean);
        }
        edit.apply();
    }

    /** What to call this sticker in lists: its name, else the last two bytes of the address. */
    public static String label(Context context, String address) {
        String alias = alias(context, address);
        if (!alias.isEmpty()) {
            return alias;
        }
        return StickerFrame.tail(StickerFrame.deviceName(StickerDevices.normalize(address)));
    }

    /** Trimmed, single line, at most {@link #MAX_LENGTH} characters. */
    static String clean(String alias) {
        if (alias == null) {
            return "";
        }
        String one = alias.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() > MAX_LENGTH ? one.substring(0, MAX_LENGTH).trim() : one;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
