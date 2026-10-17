package com.kooo.evcam.input;

import com.kooo.evcam.R;

/** How the external button is pressed. Stored with the shortcut. */
public enum PressKind {

    TAP("tap", R.string.shortcut_press_tap),
    LONG("long", R.string.shortcut_press_long),
    DOUBLE("double", R.string.shortcut_press_double);

    public final String key;
    public final int labelRes;

    PressKind(String key, int labelRes) {
        this.key = key;
        this.labelRes = labelRes;
    }

    public static PressKind fromKey(String key) {
        if (key == null || key.isEmpty()) {
            return TAP;
        }
        for (PressKind kind : values()) {
            if (kind.key.equals(key)) {
                return kind;
            }
        }
        return TAP;
    }
}
