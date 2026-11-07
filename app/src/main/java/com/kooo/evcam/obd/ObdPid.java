package com.kooo.evcam.obd;

import com.kooo.evcam.R;

import java.util.Locale;

/**
 * The standard OBD2 values (mode 01, formulas from SAE J1979) that mean something on a pure
 * electric car: no engine, fuel or coolant. The car may answer few or none; the screen shows only
 * what it says it has. Battery data proper comes from {@link ZeekrProbe}.
 */
enum ObdPid {
    BATTERY_REMAINING(0x5B, 1, R.string.obd_pid_ev_battery, "%", 1, f -> percent(f[0])),
    MODULE_VOLTAGE(0x42, 2, R.string.obd_pid_module_voltage, "V", 2, f -> word(f) / 1000d),
    SPEED(0x0D, 1, R.string.obd_pid_speed, "km/h", 0, f -> (double) f[0]),
    ACCELERATOR(0x49, 1, R.string.obd_pid_accelerator, "%", 0, f -> percent(f[0])),
    AMBIENT(0x46, 1, R.string.obd_pid_ambient, "°C", 0, f -> f[0] - 40d),
    BAROMETER(0x33, 1, R.string.obd_pid_barometer, "kPa", 0, f -> (double) f[0]),
    RUN_TIME(0x1F, 2, R.string.obd_pid_run_time, "s", 0, f -> (double) word(f)),
    DISTANCE_WITH_MIL(0x21, 2, R.string.obd_pid_distance_mil, "km", 0, f -> (double) word(f)),
    DISTANCE_SINCE_CLEARED(0x31, 2, R.string.obd_pid_distance_cleared, "km", 0, f -> (double) word(f)),
    ODOMETER(0xA6, 4, R.string.obd_pid_odometer, "km", 1, f -> {
        long raw = ((long) f[0] << 24) | ((long) f[1] << 16) | ((long) f[2] << 8) | f[3];
        return raw / 10d;
    });

    private interface Formula {
        double apply(int[] bytes);
    }

    final int pid;
    final int labelRes;
    private final int size;
    private final String unit;
    private final int decimals;
    private final Formula formula;

    ObdPid(int pid, int size, int labelRes, String unit, int decimals, Formula formula) {
        this.pid = pid;
        this.size = size;
        this.labelRes = labelRes;
        this.unit = unit;
        this.decimals = decimals;
        this.formula = formula;
    }

    String request() {
        return String.format(Locale.ROOT, "01%02X", pid);
    }

    /** The value in this answer, or null when no frame answers this PID. */
    Double decode(Iterable<byte[]> frames) {
        for (byte[] frame : frames) {
            if (frame.length >= 2 + size && (frame[0] & 0xFF) == 0x41 && (frame[1] & 0xFF) == pid) {
                int[] bytes = new int[size];
                for (int i = 0; i < size; i++) {
                    bytes[i] = frame[2 + i] & 0xFF;
                }
                return formula.apply(bytes);
            }
        }
        return null;
    }

    String format(double value) {
        return String.format(Locale.US, "%." + decimals + "f %s", value, unit);
    }

    private static double percent(int value) {
        return value * 100d / 255d;
    }

    private static int word(int[] bytes) {
        return bytes[0] << 8 | bytes[1];
    }
}
