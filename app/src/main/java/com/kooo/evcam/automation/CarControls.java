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
    private static final String TAILGATE_NAME = "BCM_FUN_TAILGATE_PERC";
    private static final int TAILGATE_ID = 0x21110200;
    private static final String REAR_FOG_NAME = "BCM_FUNC_LIGHT_REAR_FOG_LAMPS";
    private static final int REAR_FOG_ID = 0x21050500;
    /** Seen with the button lab; its number is looked up by name only. */
    private static final String LOCK_FEEDBACK_NAME = "SETTING_FUNC_LOCK_AND_UNLOCK_FBSET";
    /** Sentry Mode (SETTING_FUNC_VSTD_MODE_STS): 0 off, 1 on, 2 armed after locking. */
    private static final String SENTRY_NAME = "SETTING_FUNC_VSTD_MODE_STS";
    private static final int SENTRY_ID = 0x20240100;
    static final int SENTRY_OFF = 0;
    static final int SENTRY_ON = 1;
    static final int SENTRY_ARMED = 2;
    private static final long SENTRY_READ_BACK_MS = 1_500L;
    static final int TAILGATE_MIN = 50;
    static final int TAILGATE_MAX = 100;

    private CarControls() {
    }

    static String ac(Context context, boolean on) {
        return set(context, AC_NAME, AC_ID, on ? 1 : 0);
    }

    /** Rear fog lamp: 1 on, 0 off. */
    static String rearFog(Context context, boolean on) {
        return set(context, REAR_FOG_NAME, REAR_FOG_ID, on ? 1 : 0);
    }

    /** 0 is both sides on one temperature, 1 is a temperature for each side. */
    static String tempZones(Context context, boolean dual) {
        return set(context, TEMP_DUAL_NAME, TEMP_DUAL_ID, dual ? 1 : 0);
    }

    /**
     * How far the power tailgate opens, in percent. The car takes 50 to 100 (seen with the button
     * lab: a write of 50 reads back 50). Anything else, or a choice that is not a number, is refused.
     */
    static String tailgateHeight(Context context, String choice) {
        Integer percent = tailgatePercent(choice);
        if (percent == null) {
            BlackBox.note("Tailgate height: not sent, choice " + choice);
            return context.getString(com.kooo.evcam.R.string.automation_tailgate_bad);
        }
        return set(context, TAILGATE_NAME, TAILGATE_ID, percent);
    }

    /** The percent in a stored choice, or null when it is not a number from 50 to 100. */
    static Integer tailgatePercent(String choice) {
        try {
            int percent = Integer.parseInt(choice);
            return percent >= TAILGATE_MIN && percent <= TAILGATE_MAX ? percent : null;
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }

    /**
     * What the car does outside on lock and unlock: 0 hazard lights only, 4 with a sound, 2 with
     * the headlights, 3 with the light signal. Only the values its settings screen offers are sent.
     */
    static String lockFeedback(Context context, String choice) {
        Integer value = lockFeedbackValue(choice);
        Integer id = CarAmbientLab.functionId(LOCK_FEEDBACK_NAME);
        if (value == null || id == null) {
            BlackBox.note("Lock feedback: not sent, choice " + choice + ", id " + id);
            return context.getString(com.kooo.evcam.R.string.automation_lock_feedback_missing);
        }
        return set(context, LOCK_FEEDBACK_NAME, id, value);
    }

    /** The car's value in a stored choice, or null when it is not one the car offers. */
    static Integer lockFeedbackValue(String choice) {
        try {
            int value = Integer.parseInt(choice);
            for (int offered : AutomationAction.LOCK_FEEDBACK_VALUES) {
                if (offered == value) {
                    return value;
                }
            }
        } catch (NumberFormatException | NullPointerException e) {
            // not a number
        }
        return null;
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

    /**
     * Sentry Mode on or off. On counts as done when the car already reads on (1) or armed (2), so
     * an armed car is not knocked back to 1. On is not sent while a driving gear is in: the car
     * turns Sentry Mode off itself when it goes into D. The value is read back after a moment and
     * both go to the black box, because writing this function has not been seen working on a car.
     */
    static String sentry(Context context, boolean on) {
        int id = id(SENTRY_NAME, SENTRY_ID);
        Integer before = CarAmbientLab.value(context, id);
        if (!sentryNeedsSend(before, on)) {
            return null;
        }
        String gear = Telemetry.get().latest().gear;
        if (on && !sentryGearAllows(gear)) {
            BlackBox.note("Sentry Mode: not sent, gear " + gear);
            return context.getString(com.kooo.evcam.R.string.automation_sentry_parked);
        }
        String failed = CarAmbientLab.write(context, id, on ? SENTRY_ON : SENTRY_OFF);
        if (failed != null) {
            BlackBox.note("Sentry Mode " + before + " -> " + (on ? SENTRY_ON : SENTRY_OFF) + ": " + failed);
            return failed;
        }
        android.os.SystemClock.sleep(SENTRY_READ_BACK_MS);
        Integer after = CarAmbientLab.value(context, id);
        BlackBox.note("Sentry Mode " + before + " -> " + (on ? SENTRY_ON : SENTRY_OFF) + " sent, reads " + after);
        if (sentryNeedsSend(after, on)) {
            return context.getString(com.kooo.evcam.R.string.automation_sentry_ignored);
        }
        return null;
    }

    /** Whether the car's reading is not yet what was asked; armed counts as on. */
    static boolean sentryNeedsSend(Integer reading, boolean on) {
        if (on) {
            return reading == null || (reading != SENTRY_ON && reading != SENTRY_ARMED);
        }
        return reading == null || reading != SENTRY_OFF;
    }

    /** P, or no gear read (the car is asleep or off). */
    static boolean sentryGearAllows(String gear) {
        return gear == null || "P".equals(gear);
    }

    private static int id(String name, int known) {
        Integer found = CarAmbientLab.functionId(name);
        return found == null ? known : found;
    }
}
