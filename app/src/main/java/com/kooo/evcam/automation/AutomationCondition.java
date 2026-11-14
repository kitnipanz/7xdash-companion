package com.kooo.evcam.automation;

import android.content.Context;

import com.kooo.evcam.R;
import com.kooo.evcam.nav.SavedPlaces;

import java.text.DateFormatSymbols;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Something that has to be true for an automation to run when its trigger happens. A trigger is a
 * change ("leave home"); a condition is a state ("on a workday"). A rule runs only when all its
 * conditions hold, and a state the car has not given yet counts as not holding. The key is what
 * gets stored.
 */
public enum AutomationCondition {

    /** Value: the days it may run, a bit per day, {@link #MONDAY} first. */
    DAYS("days", Group.DAYS, R.string.automation_if_days),
    /** Value: minutes after midnight. From that time until midnight. */
    AFTER_TIME("after_time", Group.TIME, R.string.automation_if_after),
    /** Value: minutes after midnight. From midnight until that time. */
    BEFORE_TIME("before_time", Group.TIME, R.string.automation_if_before),
    DARK("is_dark", Group.DAYLIGHT, R.string.automation_if_dark),
    LIGHT("is_light", Group.DAYLIGHT, R.string.automation_if_light),
    RAINING("is_raining", Group.RAIN, R.string.automation_if_raining),
    DRY("is_dry", Group.RAIN, R.string.automation_if_dry),
    /** Value: a saved place's id. */
    AT_PLACE("at_place", Group.PLACE, R.string.automation_if_at_place),
    AWAY_FROM_PLACE("away_from_place", Group.PLACE, R.string.automation_if_away_from_place);

    /** A rule has at most one condition from each group. */
    public enum Group {
        DAYS(R.string.automation_group_days, R.drawable.ic_automation_days),
        TIME(R.string.automation_group_time, R.drawable.ic_automation_time),
        DAYLIGHT(R.string.automation_group_daylight, R.drawable.ic_automation_daylight),
        RAIN(R.string.automation_group_rain, R.drawable.ic_automation_rain),
        PLACE(R.string.automation_group_place, R.drawable.ic_automation_place);

        public final int labelRes;
        public final int iconRes;

        Group(int labelRes, int iconRes) {
            this.labelRes = labelRes;
            this.iconRes = iconRes;
        }
    }

    /** Bit for Monday; Tuesday is the next bit up, and so on to Sunday. */
    public static final int MONDAY = 1;
    public static final int WORKDAYS = 0x1F;
    public static final int WEEKEND = 0x60;
    public static final int EVERY_DAY = 0x7F;

    public final String key;
    public final Group group;
    /** Reads like "on %1$s" or "while at %1$s". */
    public final int textRes;

    AutomationCondition(String key, Group group, int textRes) {
        this.key = key;
        this.group = group;
        this.textRes = textRes;
    }

    public static AutomationCondition fromKey(String key) {
        for (AutomationCondition condition : values()) {
            if (condition.key.equals(key)) {
                return condition;
            }
        }
        return null;
    }

    /** Picked when a time condition is first chosen: early evening, the common case. */
    public static final int DEFAULT_MINUTE = 18 * 60;

    /** Is this a time one, where the value is minutes after midnight. */
    public boolean isTime() {
        return group == Group.TIME;
    }

    /** Does a time condition hold at {@code minute} (minutes after midnight; below 0 when not known)? */
    static boolean timeHolds(AutomationCondition kind, int value, int minute) {
        if (minute < 0) {
            return false;
        }
        return kind == AFTER_TIME ? minute >= value : minute < value;
    }

    /** Always the 24-hour clock: "18:00", "07:30". */
    static String clock(Context context, int minute) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d", (minute / 60) % 24, minute % 60);
    }

    /** Is this a place one, where the value is a saved place. */
    public boolean isPlace() {
        return group == Group.PLACE;
    }

    /** The day bit for a {@link Calendar#DAY_OF_WEEK}: Monday is bit 0, Sunday bit 6. */
    static int dayBit(int calendarDay) {
        int fromMonday = (calendarDay + 5) % 7;
        return MONDAY << fromMonday;
    }

    /** The conditions, one per line of the editor: "on workdays", "while at Home". */
    static String describe(Context context, AutomationRule.Condition condition) {
        AutomationCondition kind = condition.kind();
        if (kind == null) {
            return "";
        }
        switch (kind) {
            case DAYS:
                return context.getString(kind.textRes, days(context, condition.value));
            case AFTER_TIME:
            case BEFORE_TIME:
                return context.getString(kind.textRes, clock(context, condition.value));
            case AT_PLACE:
            case AWAY_FROM_PLACE:
                SavedPlaces.Place place = SavedPlaces.find(SavedPlaces.load(context), condition.value);
                return context.getString(kind.textRes,
                        place == null ? context.getString(R.string.place_deleted) : place.name);
            default:
                return context.getString(kind.textRes);
        }
    }

    /** "workdays", "weekends", "every day", or the days by short name: "Mon, Wed". */
    static String days(Context context, int mask) {
        if (mask == WORKDAYS) {
            return context.getString(R.string.automation_days_workdays);
        }
        if (mask == WEEKEND) {
            return context.getString(R.string.automation_days_weekend);
        }
        if ((mask & EVERY_DAY) == EVERY_DAY) {
            return context.getString(R.string.automation_days_every);
        }
        List<String> names = new ArrayList<>();
        String[] shortNames = shortDayNames();
        for (int i = 0; i < 7; i++) {
            if ((mask & (MONDAY << i)) != 0) {
                names.add(shortNames[i]);
            }
        }
        return names.isEmpty() ? context.getString(R.string.automation_days_none) : String.join(", ", names);
    }

    /** The short day names in this language, Monday first. */
    static String[] shortDayNames() {
        String[] sundayFirst = DateFormatSymbols.getInstance().getShortWeekdays();
        String[] out = new String[7];
        int[] calendarDays = {Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
                Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY};
        for (int i = 0; i < 7; i++) {
            out[i] = sundayFirst[calendarDays[i]];
        }
        return out;
    }
}
