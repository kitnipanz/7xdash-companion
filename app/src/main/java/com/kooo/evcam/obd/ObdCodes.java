package com.kooo.evcam.obd;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The two bit-level answers: which PIDs a car has, and trouble codes. */
final class ObdCodes {

    private static final String SYSTEMS = "PCBU";

    private ObdCodes() {
    }

    /** Adds the PIDs in one "41 00 aa bb cc dd" frame; several ECUs may each answer. */
    static void addSupported(byte[] frame, int base, Collection<Integer> into) {
        if (frame.length < 6 || (frame[0] & 0xFF) != 0x41 || (frame[1] & 0xFF) != base) {
            return;
        }
        for (int bit = 0; bit < 32; bit++) {
            if ((frame[2 + bit / 8] & (0x80 >> (bit % 8))) != 0) {
                into.add(base + bit + 1);
            }
        }
    }

    /**
     * Trouble codes in a mode 03 or 07 answer. On CAN the byte after the mode counts the codes;
     * older buses have no count and pad with zeros.
     */
    static Set<String> troubleCodes(List<byte[]> frames, int answerMode, boolean can) {
        Set<String> codes = new LinkedHashSet<>();
        for (byte[] frame : frames) {
            if (frame.length == 0 || (frame[0] & 0xFF) != answerMode) {
                continue;
            }
            int at = can ? 2 : 1;
            int end = frame.length;
            if (can && frame.length >= 2) {
                end = Math.min(end, at + (frame[1] & 0xFF) * 2);
            }
            for (; at + 1 < end; at += 2) {
                int a = frame[at] & 0xFF;
                int b = frame[at + 1] & 0xFF;
                if (a != 0 || b != 0) {
                    codes.add(String.format(Locale.ROOT, "%c%d%X%02X",
                            SYSTEMS.charAt(a >> 6), (a >> 4) & 3, a & 0xF, b));
                }
            }
        }
        return codes;
    }
}
