package com.kooo.evcam.audio;

import android.content.Context;
import android.content.SharedPreferences;

/** The on/off switch and the two preset curves. */
public final class CabinEqStore {

    private static final String FILE = "cabin_eq";
    private static final String KEY_ON = "on";
    private static final String KEY_SLOT = "slot";
    private static final String KEY_PRESET = "preset_";

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

    /** Which preset the sliders are editing. Always 0 or 1. */
    public int slot() {
        int slot = prefs.getInt(KEY_SLOT, 0);
        return slot == 1 ? 1 : 0;
    }

    public void setSlot(int slot) {
        prefs.edit().putInt(KEY_SLOT, slot == 1 ? 1 : 0).apply();
    }

    public float[] preset(int slot) {
        return CabinEq.read(prefs.getString(KEY_PRESET + (slot == 1 ? 1 : 0), ""));
    }

    public void setPreset(int slot, float[] gains) {
        prefs.edit().putString(KEY_PRESET + (slot == 1 ? 1 : 0), CabinEq.write(gains)).apply();
    }
}
