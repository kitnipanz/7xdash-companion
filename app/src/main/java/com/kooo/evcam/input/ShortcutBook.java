package com.kooo.evcam.input;

import java.util.ArrayList;
import java.util.List;

/**
 * The saved button list, as lines of {@code key,scan,action,press,device}.
 * Older lines with four fields are treated as a tap.
 * The device name is the rest of the line, so a comma in the name stays put.
 */
public final class ShortcutBook {

    private ShortcutBook() {
    }

    public static List<Shortcut> parse(String raw) {
        List<Shortcut> items = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return items;
        }
        String[] lines = raw.split("\n");
        for (String line : lines) {
            Shortcut item = parseLine(line);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    public static String write(List<Shortcut> items) {
        StringBuilder out = new StringBuilder();
        if (items == null) {
            return "";
        }
        for (Shortcut item : items) {
            if (item == null || ShortcutAction.fromKey(item.action) == null) {
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(item.keyCode).append(',').append(item.scanCode).append(',')
                    .append(item.action).append(',').append(item.press).append(',')
                    .append(item.deviceName.replace('\n', ' '));
        }
        return out.toString();
    }

    /** Drops every saved row with the same action, then adds {@code next}. */
    public static List<Shortcut> replaceAction(List<Shortcut> items, Shortcut next) {
        List<Shortcut> out = new ArrayList<>();
        if (items != null && next != null) {
            for (Shortcut item : items) {
                if (item != null && !next.action.equals(item.action)) {
                    out.add(item);
                }
            }
        }
        if (next != null && ShortcutAction.fromKey(next.action) != null) {
            out.add(next);
        }
        return out;
    }

    /** Same button + same press kind replaces the old binding. */
    public static List<Shortcut> put(List<Shortcut> items, Shortcut next) {
        List<Shortcut> out = new ArrayList<>();
        if (items != null) {
            for (Shortcut item : items) {
                if (item != null && !sameButton(item, next)) {
                    out.add(item);
                }
            }
        }
        if (next != null && ShortcutAction.fromKey(next.action) != null) {
            out.add(next);
        }
        return out;
    }

    public static List<Shortcut> remove(List<Shortcut> items, int index) {
        List<Shortcut> out = new ArrayList<>();
        if (items == null) {
            return out;
        }
        for (int i = 0; i < items.size(); i++) {
            if (i != index) {
                out.add(items.get(i));
            }
        }
        return out;
    }

    /** True when this physical button is saved as hold-to-speak, whatever press kind was stored. */
    public static boolean holdToSpeak(List<Shortcut> items, int keyCode, int scanCode, String deviceName) {
        if (items == null) {
            return false;
        }
        for (Shortcut item : items) {
            if (item != null && ShortcutAction.HOLD_SPEAK.key.equals(item.action)
                    && samePress(item, keyCode, scanCode, deviceName)) {
                return true;
            }
        }
        return false;
    }

    public static Shortcut match(List<Shortcut> items, int keyCode, int scanCode,
                                 String deviceName, PressKind kind) {
        if (items == null) {
            return null;
        }
        PressKind want = kind == null ? PressKind.TAP : kind;
        for (Shortcut item : items) {
            if (item != null && item.pressKind() == want
                    && samePress(item, keyCode, scanCode, deviceName)) {
                return item;
            }
        }
        return null;
    }

    /** @deprecated use {@link #match(List, int, int, String, PressKind)} */
    public static Shortcut match(List<Shortcut> items, int keyCode, int scanCode, String deviceName) {
        return match(items, keyCode, scanCode, deviceName, PressKind.TAP);
    }

    static boolean sameButton(Shortcut saved, Shortcut next) {
        if (saved == null || next == null) {
            return false;
        }
        if (!saved.press.equals(next.press)) {
            return false;
        }
        if (!sameDevice(saved.deviceName, next.deviceName)) {
            return false;
        }
        if (saved.keyCode != 0 || next.keyCode != 0) {
            return saved.keyCode == next.keyCode;
        }
        return saved.scanCode != 0 && saved.scanCode == next.scanCode;
    }

    private static boolean samePress(Shortcut saved, int keyCode, int scanCode, String deviceName) {
        if (!sameDevice(saved.deviceName, deviceName)) {
            return false;
        }
        if (saved.keyCode != 0) {
            return saved.keyCode == keyCode;
        }
        return saved.scanCode != 0 && saved.scanCode == scanCode;
    }

    /** An empty name on either side matches any device. */
    private static boolean sameDevice(String saved, String now) {
        if (saved == null || saved.isEmpty() || now == null || now.isEmpty()) {
            return true;
        }
        return saved.equals(now);
    }

    private static Shortcut parseLine(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        String[] parts = line.split(",", 5);
        if (parts.length < 4) {
            return null;
        }
        try {
            int key = Integer.parseInt(parts[0].trim());
            int scan = Integer.parseInt(parts[1].trim());
            String action = parts[2].trim();
            if (ShortcutAction.fromKey(action) == null) {
                return null;
            }
            if (parts.length == 4) {
                // Old format: key,scan,action,device
                return new Shortcut(key, scan, parts[3].trim(), action, PressKind.TAP.key);
            }
            return new Shortcut(key, scan, parts[4].trim(), action, parts[3].trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
