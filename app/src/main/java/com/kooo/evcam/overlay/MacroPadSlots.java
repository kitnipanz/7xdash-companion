package com.kooo.evcam.overlay;

import java.util.Arrays;

/**
 * The Macro pad's buttons: {@link #COUNT} action keys ({@code ShortcutAction.key}, with a choice for
 * the ones that carry one), "" for an empty button. Stored in AppConfig one per line. Pure Java, see
 * the test.
 */
public final class MacroPadSlots {

    /** Three across, two down. */
    public static final int COLUMNS = 3;
    public static final int ROWS = 2;
    public static final int COUNT = COLUMNS * ROWS;

    /** What a new pad starts with: the things people reach for most while driving. */
    static final String[] DEFAULTS = {"toggle_recording", "take_photo", "instant_capture",
            "toggle_mirror", "toggle_dim", "announce_picker"};

    private MacroPadSlots() {
    }

    /** Always {@link #COUNT} entries. Null (never set) gives {@link #DEFAULTS}. */
    public static String[] parse(String stored) {
        if (stored == null) {
            return DEFAULTS.clone();
        }
        String[] out = new String[COUNT];
        Arrays.fill(out, "");
        String[] lines = stored.split("\n", -1);
        for (int i = 0; i < COUNT && i < lines.length; i++) {
            out[i] = lines[i].trim();
        }
        return out;
    }

    public static String write(String[] slots) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < COUNT; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(slots != null && i < slots.length && slots[i] != null ? slots[i].replace('\n', ' ') : "");
        }
        return sb.toString();
    }

    /** {@code slots} with button {@code index} set to {@code key} ("" empties it). */
    public static String[] with(String[] slots, int index, String key) {
        String[] out = parse(write(slots));
        if (index >= 0 && index < COUNT) {
            out[index] = key == null ? "" : key;
        }
        return out;
    }
}
