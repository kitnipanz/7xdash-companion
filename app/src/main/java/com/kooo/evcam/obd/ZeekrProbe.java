package com.kooo.evcam.obd;

import java.util.List;
import java.util.Locale;

/**
 * Asks the battery module directly, the way the Zeekr 001 and Geely EX5 are read: 29-bit CAN and
 * UDS service 22 (read by identifier). A pure electric car answers little or nothing on standard
 * OBD, so this is where its battery data would be.
 *
 * <p>Addresses come from the WiCAN vehicle profiles for those cars (meatpiHQ/wican-fw,
 * vehicle_profiles/zeekr, vehicle_profiles/geely). The identifiers and formulas in {@link #value}
 * are the ones confirmed on a Volvo EX30, another car on the same SEA platform, against its own
 * dashboard (ifmeidan/ex-30-driver-display, docs/pid_map.md). Nobody has confirmed them on a 7X,
 * so the raw bytes stay on screen next to each value until a real 7X matches its own display.</p>
 */
final class ZeekrProbe {

    /** Adapter setup for this path, in the order of the profiles. */
    static final String[] ENTER = {
            "ATSP7", "ATSHD01635", "ATCP1D", "ATCRA1EC6AE80",
            "ATFCSH1DD01635", "ATFCSD300000", "ATFCSM1", "ATST64"
    };

    static final int PACK_VOLTAGE = 0x4801;
    static final int PACK_CURRENT = 0x4802;
    static final int PACK_TEMP_AVG = 0x491B;
    static final int PACK_TEMP_MAX = 0x4945;
    static final int STATE_OF_HEALTH = 0x496D;
    static final int ODOMETER = 0xDD01;

    static final int[] DIDS = {PACK_VOLTAGE, PACK_CURRENT, PACK_TEMP_AVG, PACK_TEMP_MAX,
            STATE_OF_HEALTH, ODOMETER};

    /** The identifiers a battery module scan asks for: the two blocks the known ones are in. */
    static final int SCAN_FIRST = 0x4800;
    static final int SCAN_LAST = 0x49FF;

    /** One identifier's outcome. */
    static final class Result {
        /** The bytes after the identifier as hex; null when there is no answer. */
        final String hex;
        /** The same bytes; null when there is no answer. */
        final byte[] data;
        /** The car's negative response code, such as 0x33 security access denied; -1 if none. */
        final int refusal;

        Result(byte[] data, int refusal) {
            this.data = data;
            this.hex = data == null ? null : hex(data);
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
                byte[] data = new byte[frame.length - 3];
                System.arraycopy(frame, 3, data, 0, data.length);
                return new Result(data, -1);
            }
            // 0x78 only means "wait": the real answer follows in a later frame
            if (frame.length >= 3 && (frame[0] & 0xFF) == 0x7F && (frame[1] & 0xFF) == 0x22
                    && (frame[2] & 0xFF) != 0x78) {
                refusal = frame[2] & 0xFF;
            }
        }
        return new Result(null, refusal);
    }

    /**
     * The value an identifier holds, decoded as on the EX30; null if unknown or too short.
     * Volts, amps (positive = out of the battery), °C, % or km.
     */
    static Double value(int did, byte[] data) {
        if (data == null) {
            return null;
        }
        switch (did) {
            case PACK_VOLTAGE:
                return data.length < 2 ? null : u16(data, 0) / 100.0;
            case PACK_CURRENT:
                return data.length < 2 ? null : (u16(data, 0) - 16384) * 0.1;
            case PACK_TEMP_AVG:
                return data.length < 2 ? null : u16(data, 0) / 100.0 - 50;
            case PACK_TEMP_MAX:
                // The first byte is the number of the hottest sensor
                return data.length < 3 ? null : u16(data, 1) / 100.0 - 50;
            case STATE_OF_HEALTH:
                return data.length < 4 ? null : (((long) u16(data, 0) << 16) | u16(data, 2)) * 0.01;
            case ODOMETER:
                return data.length < 3 ? null
                        : (double) (((data[0] & 0xFF) << 16) | u16(data, 1));
            default:
                return null;
        }
    }

    /** Pack power in kW from the voltage and current results, or null if either is missing. */
    static Double powerKw(Result voltage, Result current) {
        Double volts = voltage == null ? null : value(PACK_VOLTAGE, voltage.data);
        Double amps = current == null ? null : value(PACK_CURRENT, current.data);
        return volts == null || amps == null ? null : volts * amps / 1000.0;
    }

    static String format(int did, double value) {
        switch (did) {
            case PACK_VOLTAGE:
                return String.format(Locale.US, "%.1f V", value);
            case PACK_CURRENT:
                return String.format(Locale.US, "%.1f A", value);
            case PACK_TEMP_AVG:
            case PACK_TEMP_MAX:
                return String.format(Locale.US, "%.1f °C", value);
            case STATE_OF_HEALTH:
                return String.format(Locale.US, "%.2f %%", value);
            case ODOMETER:
                return String.format(Locale.US, "%,.0f km", value);
            default:
                return String.valueOf(value);
        }
    }

    private static int u16(byte[] data, int at) {
        return ((data[at] & 0xFF) << 8) | (data[at + 1] & 0xFF);
    }

    static String hex(byte[] data) {
        StringBuilder hex = new StringBuilder();
        for (byte each : data) {
            hex.append(String.format(Locale.ROOT, "%02X", each & 0xFF));
        }
        return hex.toString();
    }
}
