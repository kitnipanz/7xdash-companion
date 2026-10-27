package com.kooo.evcam.light;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.kooo.evcam.R;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.telemetry.CarAmbientLab;

/**
 * The car's rear fog lamp, through the ECARX body control function. Found with Watch in the Car
 * lights lab: 0 is off, 1 is on.
 */
public final class FogLamp {

    static final String NAME = "BCM_FUNC_LIGHT_REAR_FOG_LAMPS";
    /** The number seen on a 7X, for when the library cannot be searched by name. */
    static final int KNOWN_ID = 0x21050500;

    private FogLamp() {
    }

    /** What to send to flip it: on unless it reads on. */
    static int next(Integer current) {
        return current != null && current == 1 ? 0 : 1;
    }

    /** Turns the rear fog lamp on or off, the other way from what the car reads, and says which. */
    public static void toggle(Context context) {
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Integer found = CarAmbientLab.functionId(NAME);
            int id = found == null ? KNOWN_ID : found;
            Integer before = CarAmbientLab.value(app, id);
            int want = next(before);
            String failed = CarAmbientLab.write(app, id, want);
            BlackBox.note("Fog lamp " + before + " -> " + want + (failed == null ? " sent" : ": " + failed));
            main.post(() -> {
                String text = failed == null
                        ? app.getString(want == 1 ? R.string.fog_lamp_turned_on : R.string.fog_lamp_turned_off)
                        : app.getString(R.string.fog_lamp_failed, failed);
                com.kooo.evcam.ui.Notice.makeText(app, text, Toast.LENGTH_SHORT).show();
            });
        }, "fog-lamp").start();
    }
}
