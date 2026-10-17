package com.kooo.evcam.audio;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The on/off switch, which preset is chosen, and the five "My" curves.
 *
 * <p>The choice is a short key: {@code builtin:ROCK} for a built-in curve, {@code my:0} … {@code my:4}
 * for the user's own. Older builds saved two curves under {@code preset_0} / {@code preset_1} and an
 * int slot; those become My 1 / My 2 unchanged.</p>
 */
public final class CabinEqStore {

    private static final String FILE = "cabin_eq";
    private static final String KEY_ON = "on";
    private static final String KEY_SLOT = "slot";
    private static final String KEY_CHOICE = "choice";
    private static final String KEY_LAST_MY = "last_my";
    private static final String KEY_PRESET = "preset_";

    public static final String BUILTIN = "builtin:";
    public static final String MY = "my:";

    private final SharedPreferences prefs;

    public CabinEqStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public boolean enabled() {
        return prefs.getBoolean(KEY_ON, false);
    }

    public void setEnabled(boolean on) {
        prefs.edit().putBoolean(KEY_ON, on).apply();
    }

    /** What is chosen now. Falls back to the old int slot (My 1 / My 2). */
    public String choice() {
        String saved = prefs.getString(KEY_CHOICE, null);
        if (saved != null && (builtinOf(saved) != null || myIndexOf(saved) >= 0)) {
            return saved;
        }
        return MY + clampMy(prefs.getInt(KEY_SLOT, 0));
    }

    public void choose(String choice) {
        SharedPreferences.Editor edit = prefs.edit().putString(KEY_CHOICE, choice);
        int my = myIndexOf(choice);
        if (my >= 0) {
            edit.putInt(KEY_LAST_MY, my);
        }
        edit.apply();
    }

    /** The "My" preset edits land in when a built-in is chosen: the last one used. */
    public int lastMy() {
        return clampMy(prefs.getInt(KEY_LAST_MY, myIndexOf(choice()) >= 0 ? myIndexOf(choice()) : 0));
    }

    /** The curve of the current choice. */
    public float[] currentGains() {
        return gainsOf(choice());
    }

    public float[] gainsOf(String choice) {
        CabinEq.Builtin builtin = builtinOf(choice);
        if (builtin != null) {
            return builtin.gains();
        }
        return my(Math.max(0, myIndexOf(choice)));
    }

    public float[] my(int index) {
        return CabinEq.read(prefs.getString(KEY_PRESET + clampMy(index), ""));
    }

    public void setMy(int index, float[] gains) {
        prefs.edit().putString(KEY_PRESET + clampMy(index), CabinEq.write(gains)).apply();
    }

    public static CabinEq.Builtin builtinOf(String choice) {
        if (choice == null || !choice.startsWith(BUILTIN)) {
            return null;
        }
        try {
            return CabinEq.Builtin.valueOf(choice.substring(BUILTIN.length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static int myIndexOf(String choice) {
        if (choice == null || !choice.startsWith(MY)) {
            return -1;
        }
        try {
            int index = Integer.parseInt(choice.substring(MY.length()));
            return index >= 0 && index < CabinEq.PRESETS ? index : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int clampMy(int index) {
        return Math.max(0, Math.min(CabinEq.PRESETS - 1, index));
    }
}
