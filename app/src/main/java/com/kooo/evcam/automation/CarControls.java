package com.kooo.evcam.automation;

import android.content.Context;

import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.telemetry.CarAmbientLab;
import com.kooo.evcam.telemetry.Telemetry;

/**
 * Climate and glovebox, through the ECARX functions found with the button lab on the car. Each
 * is looked up by name, with the number seen on a 7X for when the library cannot be searched.
 * Blocking: run it off the main thread. Returns null when fine, else why not.
 */
final class CarControls {

    private static final String AC_NAME = "HVAC_FUNC_AC";
    private static final int AC_ID = 0x10010300;
    private static final String TEMP_DUAL_NAME = "HVAC_FUNC_TEMP_DUAL";
    private static final int TEMP_DUAL_ID = 0x10060500;
    private static final String GLOVEBOX_NAME = "SETTING_FUNC_DRVSET_GLVBOXHMI_REQ";
    private static final int GLOVEBOX_ID = 0x202F1900;
    static final int GLOVEBOX_OPEN = 1;

    private CarControls() {
    }

    static String ac(Context context, boolean on) {
        return set(context, AC_NAME, AC_ID, on ? 1 : 0);
    }

    /** 0 is both sides on one temperature, 1 is a temperature for each side. */
    static String tempZones(Context context, boolean dual) {
        return set(context, TEMP_DUAL_NAME, TEMP_DUAL_ID, dual ? 1 : 0);
    }

    /** Sends the value unless the car already reads it. */
    private static String set(Context context, String name, int known, int want) {
        int id = id(name, known);
        Integer before = CarAmbientLab.value(context, id);
        if (Integer.valueOf(want).equals(before)) {
            return null;
        }
        String failed = CarAmbientLab.write(context, id, want);
        BlackBox.note(name + " " + before + " -> " + want + (failed == null ? " sent" : ": " + failed));
        return failed;
    }

    /**
     * Parked only: an open glovebox lid in front of a passenger is no thing to cause while moving.
     * 1 is the only value to send: it opens the lid, and the lid is closed by hand. Always sent,
     * whatever the car reads, because the value can stay at 1 after the lid was shut again.
     */
    static String openGlovebox(Context context) {
        String gear = Telemetry.get().latest().gear;
        if (!"P".equals(gear)) {
            BlackBox.note("Glovebox: not sent, gear " + gear);
            return context.getString(com.kooo.evcam.R.string.automation_glovebox_parked);
        }
        String failed = CarAmbientLab.write(context, id(GLOVEBOX_NAME, GLOVEBOX_ID), GLOVEBOX_OPEN);
        BlackBox.note("Glovebox open" + (failed == null ? " sent" : ": " + failed));
        return failed;
    }

    private static int id(String name, int known) {
        Integer found = CarAmbientLab.functionId(name);
        return found == null ? known : found;
    }
}
