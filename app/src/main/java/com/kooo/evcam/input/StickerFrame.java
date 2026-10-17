package com.kooo.evcam.input;

import com.kooo.evcam.R;

import java.util.Locale;

/**
 * One press report from a Zeekr smart sticker.
 *
 * <p>The sticker is a BLE peripheral. Service {@code 0xFFD0}, notify {@code 0xFFD2}.
 * One report is header {@code 0x02} plus a key byte. The ZEEKR button on the car
 * sends a single byte instead: {@code 0x31} for one click, {@code 0x32} for a
 * double click. The device already classifies the press, so this does not go
 * through {@link PressClassifier}.</p>
 */
public final class StickerFrame {

    public static final int CONTROL_BUTTON = 0xFFD0;
    public static final int CONTROL_KNOB_RIGHT = 0xFFD2;
    public static final int CONTROL_KNOB_LEFT = 0xFFD3;

    private static final String PREFIX = "sticker:";

    public final int control;
    public final PressKind press;

    public StickerFrame(int control, PressKind press) {
        this.control = control;
        this.press = press == null ? PressKind.TAP : press;
    }

    /** {@code null} when the bytes are not a button or knob press. */
    public static StickerFrame parse(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return null;
        }
        int head = raw[0] & 0xFF;
        if (head == 0x31) {
            return new StickerFrame(CONTROL_BUTTON, PressKind.TAP);
        }
        if (head == 0x32) {
            return new StickerFrame(CONTROL_BUTTON, PressKind.DOUBLE);
        }
        if (raw.length < 2 || head != 0x02) {
            return null;
        }
        switch (raw[1] & 0xFF) {
            case 0x01:
                return new StickerFrame(CONTROL_BUTTON, PressKind.TAP);
            case 0x41:
                return new StickerFrame(CONTROL_BUTTON, PressKind.LONG);
            case 0x81:
                return new StickerFrame(CONTROL_BUTTON, PressKind.DOUBLE);
            case 0x02:
                return new StickerFrame(CONTROL_KNOB_RIGHT, PressKind.TAP);
            case 0x03:
                return new StickerFrame(CONTROL_KNOB_LEFT, PressKind.TAP);
            default:
                return null;
        }
    }

    public static String deviceName(String address) {
        String mac = address == null ? "" : address.trim().toUpperCase(Locale.US);
        return PREFIX + mac;
    }

    public static boolean isSticker(String deviceName) {
        return deviceName != null && deviceName.startsWith(PREFIX);
    }

    /** MAC stored after {@code sticker:}, or empty when this is not a sticker binding. */
    public static String address(String deviceName) {
        if (!isSticker(deviceName)) {
            return "";
        }
        return deviceName.substring(PREFIX.length());
    }

    /** Last two bytes of the MAC, so two stickers can be told apart in the shortcut list. */
    public static String tail(String deviceName) {
        if (!isSticker(deviceName)) {
            return "";
        }
        String address = deviceName.substring(PREFIX.length());
        if (address.length() >= 5) {
            return address.substring(address.length() - 5);
        }
        return address;
    }

    /** String resource for this control, or 0 when it is not a sticker control. */
    public static int controlLabel(int control) {
        if (control == CONTROL_BUTTON) {
            return R.string.sticker_button;
        }
        if (control == CONTROL_KNOB_RIGHT) {
            return R.string.sticker_knob_right;
        }
        if (control == CONTROL_KNOB_LEFT) {
            return R.string.sticker_knob_left;
        }
        return 0;
    }

    public static String hex(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (byte value : raw) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(String.format(Locale.US, "%02X", value & 0xFF));
        }
        return out.toString();
    }
}
