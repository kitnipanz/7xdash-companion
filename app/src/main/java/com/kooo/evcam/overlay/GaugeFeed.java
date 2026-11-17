package com.kooo.evcam.overlay;

import android.os.SystemClock;

import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Signal;

/**
 * The numbers for the three gauges, for whichever window shows them (under the Dash panel, or the
 * gauges on their own): car readings plus G-force worked out from the speed. On the emulator in a
 * debug build, with no car, the needles sweep so the dials can be seen working.
 */
final class GaugeFeed {

    private final DashGauge.GForce gForce = new DashGauge.GForce();
    private Float gNow;

    /** Every new set of readings, so the G-force follows the speed. */
    void onReadings(Readings readings) {
        gNow = gForce.update(readings.number(Signal.SPEED), SystemClock.elapsedRealtime());
    }

    Float[] values(DashGauge[] chosen, Readings now) {
        Float[] values = new Float[DashGauge.COUNT];
        boolean demo = demoOnEmulator();
        for (int i = 0; i < DashGauge.COUNT; i++) {
            values[i] = chosen[i].value(now, gNow);
            if (values[i] != null) {
                demo = false;
            }
        }
        if (demo) {
            double t = SystemClock.elapsedRealtime() / 1000.0;
            for (int i = 0; i < DashGauge.COUNT; i++) {
                double wave = 0.5 + 0.5 * Math.sin(t * 0.8 + i * 1.3);
                values[i] = (float) (chosen[i].min + wave * (chosen[i].max - chosen[i].min));
            }
        }
        return values;
    }

    private static boolean demoOnEmulator() {
        if (!com.kooo.evcam.BuildConfig.DEBUG) {
            return false;
        }
        String hw = android.os.Build.HARDWARE;
        return "ranchu".equals(hw) || "goldfish".equals(hw)
                || android.os.Build.FINGERPRINT.startsWith("generic") || android.os.Build.PRODUCT.startsWith("sdk");
    }
}
