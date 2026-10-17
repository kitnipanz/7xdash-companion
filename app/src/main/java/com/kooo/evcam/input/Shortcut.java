package com.kooo.evcam.input;

/** One external button binding: key + how it is pressed + action. */
public final class Shortcut {

    public final int keyCode;
    public final int scanCode;
    public final String deviceName;
    public final String action;
    public final String press;

    public Shortcut(int keyCode, int scanCode, String deviceName, String action) {
        this(keyCode, scanCode, deviceName, action, PressKind.TAP.key);
    }

    public Shortcut(int keyCode, int scanCode, String deviceName, String action, String press) {
        this.keyCode = keyCode;
        this.scanCode = scanCode;
        this.deviceName = deviceName == null ? "" : deviceName;
        this.action = action == null ? "" : action;
        this.press = PressKind.fromKey(press).key;
    }

    public PressKind pressKind() {
        return PressKind.fromKey(press);
    }
}
