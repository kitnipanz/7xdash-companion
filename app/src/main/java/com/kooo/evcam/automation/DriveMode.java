package com.kooo.evcam.automation;

import android.content.Context;

import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.telemetry.CarAmbientLab;

/**
 * The car's drive mode, through the ECARX drive mode function. Found with Watch in the Car lights
 * lab: IDriveMode.DM_FUNC_DRIVE_MODE_SELECT, whose value is its own number plus 1, 2 or 3.
 * Blocking: run it off the main thread.
 */
final class DriveMode {

    static final String NAME = "DM_FUNC_DRIVE_MODE_SELECT";
    /** The number seen on a 7X, for when the library cannot be searched by name. */
    static final int KNOWN_ID = 0x22010100;
    static final int COMFORT = 570491137;
    static final int NORMAL = 570491138;
    static final int SPORT = 570491139;

    private DriveMode() {
    }

    /** Stored choice to the car's value; null when it is not one. */
    static Integer value(String choice) {
        if ("comfort".equals(choice)) {
            return COMFORT;
        }
        if ("normal".equals(choice)) {
            return NORMAL;
        }
        return "sport".equals(choice) ? SPORT : null;
    }

    /** Sends the mode unless the car already reads it. Returns null when fine, else why not. */
    static String set(Context context, String choice) {
        Integer want = value(choice);
        if (want == null) {
            return "unknown drive mode " + choice;
        }
        Integer found = CarAmbientLab.functionId(NAME);
        int id = found == null ? KNOWN_ID : found;
        Integer before = CarAmbientLab.value(context, id);
        if (want.equals(before)) {
            return null;
        }
        String failed = CarAmbientLab.write(context, id, want);
        BlackBox.note("Drive mode " + before + " -> " + want + (failed == null ? " sent" : ": " + failed));
        return failed;
    }
}
