package com.kooo.evcam.automation;

import java.util.Calendar;
import java.util.Locale;

/**
 * How times and days fit in a condition's one number. A time is minutes from midnight. A span is
 * from * {@link #SPAN} + to, and may run past midnight. Days are one bit each, Sunday lowest.
 */
final class AutomationClock {

    static final int DAY_MINUTES = 24 * 60;
    private static final int SPAN = 10_000;
    static final int WEEKDAYS = 0b0111110;
    static final int WEEKEND = 0b1000001;
    static final int EVERY_DAY = WEEKDAYS | WEEKEND;
    /** Offered first: Monday to Sunday, as {@link Calendar} numbers them. */
    static final int[] WEEK = {Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY};

    private AutomationClock() {
    }

    static int span(int from, int to) {
        return from * SPAN + to;
    }

    static int from(int span) {
        return span / SPAN;
    }

    static int to(int span) {
        return span % SPAN;
    }

    /** The end minute itself is outside, so 09:00 to 10:00 ends as 10:00 starts. */
    static boolean within(int minute, int span) {
        int from = from(span);
        int to = to(span);
        return from <= to ? minute >= from && minute < to : minute >= from || minute < to;
    }

    static int dayBit(int calendarDay) {
        return 1 << (calendarDay - Calendar.SUNDAY);
    }

    static String time(int minutes) {
        return String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60);
    }
}
