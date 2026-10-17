package com.kooo.evcam.input;

/** True while the add-shortcut screen is waiting, so a saved button is not fired mid-capture. */
public final class ShortcutCapture {

    private static volatile boolean active;

    private ShortcutCapture() {
    }

    public static void setActive(boolean value) {
        active = value;
    }

    public static boolean isActive() {
        return active;
    }
}
