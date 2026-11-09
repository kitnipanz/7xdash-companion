package com.kooo.evcam.automation;

import com.kooo.evcam.R;

/** What starts an automation. The key is what gets stored. */
public enum AutomationTrigger {

    CAR_ON("car_on", R.string.automation_when_car_on, R.string.automation_chip_turns_on, Group.CAR, null, false),
    CAR_OFF("car_off", R.string.automation_when_car_off, R.string.automation_chip_turns_off, Group.CAR, null, false),
    GEAR_P("gear_p", R.string.automation_when_gear_p, R.string.automation_chip_gear_p, Group.GEAR, null, false),
    GEAR_D("gear_d", R.string.automation_when_gear_d, R.string.automation_chip_gear_d, Group.GEAR, null, false),
    GEAR_R("gear_r", R.string.automation_when_gear_r, R.string.automation_chip_gear_r, Group.GEAR, null, false),
    SPEED_ABOVE("speed_above", R.string.automation_when_speed_above, R.string.automation_chip_reaches, Group.SPEED, Measure.SPEED, true),
    SPEED_BELOW("speed_below", R.string.automation_when_speed_below, R.string.automation_chip_drops, Group.SPEED, Measure.SPEED, false),
    DARK("dark", R.string.automation_when_dark, R.string.automation_chip_dark, Group.DAYLIGHT, null, false),
    LIGHT("light", R.string.automation_when_light, R.string.automation_chip_light, Group.DAYLIGHT, null, false),
    RAIN_START("rain_start", R.string.automation_when_rain_start, R.string.automation_chip_rain_start, Group.RAIN, null, false),
    RAIN_STOP("rain_stop", R.string.automation_when_rain_stop, R.string.automation_chip_rain_stop, Group.RAIN, null, false),
    BATTERY_BELOW("battery_below", R.string.automation_when_battery_below, R.string.automation_chip_drops, Group.BATTERY, Measure.BATTERY, false),
    OUTSIDE_ABOVE("outside_above", R.string.automation_when_outside_above, R.string.automation_chip_reaches, Group.OUTSIDE, Measure.OUTSIDE, true),
    OUTSIDE_BELOW("outside_below", R.string.automation_when_outside_below, R.string.automation_chip_drops, Group.OUTSIDE, Measure.OUTSIDE, false),
    /** Value: a saved place's id ({@link com.kooo.evcam.nav.SavedPlaces}). */
    PLACE_ARRIVE("place_arrive", R.string.automation_when_place_arrive, R.string.automation_chip_place_arrive, Group.PLACE, null, false),
    PLACE_LEAVE("place_leave", R.string.automation_when_place_leave, R.string.automation_chip_place_leave, Group.PLACE, null, false);

    /** Triggers about the same thing sit together in the picker. */
    public enum Group {
        CAR(R.string.automation_group_car, R.drawable.ic_power, 0),
        GEAR(R.string.automation_group_gear, R.drawable.ic_automation_gear, 0),
        SPEED(R.string.automation_group_speed, R.drawable.ic_automation_speed, R.string.automation_unit_speed),
        DAYLIGHT(R.string.automation_group_daylight, R.drawable.ic_automation_daylight, 0),
        RAIN(R.string.automation_group_rain, R.drawable.ic_automation_rain, 0),
        BATTERY(R.string.automation_group_battery, R.drawable.ic_automation_battery, R.string.automation_percent),
        OUTSIDE(R.string.automation_group_outside, R.drawable.ic_automation_temp, R.string.automation_unit_temp),
        PLACE(R.string.automation_group_place, R.drawable.ic_automation_place, 0);

        public final int labelRes;
        public final int iconRes;
        /** Formats the number for a group that has one. */
        public final int unitRes;

        Group(int labelRes, int iconRes, int unitRes) {
            this.labelRes = labelRes;
            this.iconRes = iconRes;
            this.unitRes = unitRes;
        }
    }

    /** A number the trigger compares against, with the values offered in the picker. */
    public enum Measure {
        SPEED(3f, 10, 160, 10, 80),
        BATTERY(1f, 5, 50, 5, 20),
        OUTSIDE(1f, -10, 40, 5, 30);

        /** How far back the reading must come before the trigger can fire again. */
        final float hysteresis;
        public final int from;
        public final int to;
        public final int step;
        public final int fallback;

        Measure(float hysteresis, int from, int to, int step, int fallback) {
            this.hysteresis = hysteresis;
            this.from = from;
            this.to = to;
            this.step = step;
            this.fallback = fallback;
        }
    }

    public final String key;
    public final int labelRes;
    /** Short name beside the others in its group. */
    public final int chipRes;
    public final Group group;
    /** Null when the trigger is an event with no number to choose. */
    public final Measure measure;
    /** Fires going up past the number; otherwise going down past it. */
    public final boolean rising;

    AutomationTrigger(String key, int labelRes, int chipRes, Group group, Measure measure, boolean rising) {
        this.key = key;
        this.labelRes = labelRes;
        this.chipRes = chipRes;
        this.group = group;
        this.measure = measure;
        this.rising = rising;
    }

    /** Arriving and leaving: nothing on the car says the place changed, so the GPS has to be looked at. */
    public boolean isPlace() {
        return group == Group.PLACE;
    }

    public static AutomationTrigger fromKey(String key) {
        for (AutomationTrigger trigger : values()) {
            if (trigger.key.equals(key)) {
                return trigger;
            }
        }
        return null;
    }
}
