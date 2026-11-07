package com.kooo.evcam.obd;

import java.util.List;
import java.util.Locale;

/**
 * Asks the battery module directly, the way the Zeekr 001 and Geely EX5 are read: 29-bit CAN and
 * UDS service 22 (read by identifier). A pure electric car answers little or nothing on standard
 * OBD, so this is where its battery data would be.
 *
 * <p>Addresses and identifiers come from the WiCAN vehicle profiles for those cars
 * (meatpiHQ/wican-fw, vehicle_profiles/zeekr, vehicle_profiles/geely). Nobody has published them
 * for the 7X, and owners report only the VIN and voltage from it. So the bytes are shown raw, with
 * the car's refusal code if it refuses. A decoder belongs here once the bytes are matched to the
 * car's own battery % on a real 7X; the 001 and EX5 formulas differ, so neither is assumed.</p>
 */
final class ZeekrProbe {

    /** Adapter setup for this path, in the order of the profiles. */
    static final String[] ENTER = {
            "ATSP7", "ATSHD01635", "ATCP1D", "ATCRA1EC6AE80",
            "ATFCSH1DD01635", "ATFCSD300000", "ATFCSM1", "ATST64"
    };

    static final int[] DIDS = {0x4801, 0x491B};

    /** One identifier's outcome. */
    static final class Result {
        /** The bytes after the identifier as hex; null when there is no answer. */
        final String hex;
        /** The car's negative response code, such as 0x33 security access denied; -1 if none. */
        final int refusal;

        Result(String hex, int refusal) {
            this.hex = hex;
            this.refusal = refusal;
        }

        boolean reachedModule() {
            return hex != null || refusal >= 0;
        }
    }

    private ZeekrProbe() {
    }

    static String request(int did) {
        return String.format(Locale.ROOT, "22%04X", did);
    }

    static Result read(int did, List<byte[]> frames) {
        int refusal = -1;
        for (byte[] frame : frames) {
            if (frame.length >= 3 && (frame[0] & 0xFF) == 0x62
                    && (((frame[1] & 0xFF) << 8) | (frame[2] & 0xFF)) == did) {
                StringBuilder hex = new StringBuilder();
                for (int i = 3; i < frame.length; i++) {
                    hex.append(String.format(Locale.ROOT, "%02X", frame[i] & 0xFF));
                }
                return new Result(hex.toString(), -1);
            }
            // 0x78 only means "wait": the real answer follows in a later frame
            if (frame.length >= 3 && (frame[0] & 0xFF) == 0x7F && (frame[1] & 0xFF) == 0x22
                    && (frame[2] & 0xFF) != 0x78) {
                refusal = frame[2] & 0xFF;
            }
        }
        return new Result(null, refusal);
    }
}
