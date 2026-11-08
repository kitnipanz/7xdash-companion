package com.kooo.evcam.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;

public class AutomationClockTest {

    @Test
    public void aSpanKeepsBothEnds() {
        int span = AutomationClock.span(7 * 60 + 5, 23 * 60 + 59);
        assertEquals(425, AutomationClock.from(span));
        assertEquals(1439, AutomationClock.to(span));
    }

    @Test
    public void midnightStartsASpan() {
        int span = AutomationClock.span(0, 60);
        assertEquals(0, AutomationClock.from(span));
        assertTrue(AutomationClock.within(0, span));
        assertFalse(AutomationClock.within(60, span));
    }

    @Test
    public void aSpanCanRunPastMidnight() {
        int night = AutomationClock.span(22 * 60, 6 * 60);
        assertTrue(AutomationClock.within(23 * 60, night));
        assertTrue(AutomationClock.within(5 * 60, night));
        assertFalse(AutomationClock.within(12 * 60, night));
        assertFalse(AutomationClock.within(6 * 60, night));
    }

    @Test
    public void weekdaysAreMondayToFriday() {
        assertTrue((AutomationClock.WEEKDAYS & AutomationClock.dayBit(Calendar.MONDAY)) != 0);
        assertTrue((AutomationClock.WEEKDAYS & AutomationClock.dayBit(Calendar.FRIDAY)) != 0);
        assertFalse((AutomationClock.WEEKDAYS & AutomationClock.dayBit(Calendar.SATURDAY)) != 0);
        assertFalse((AutomationClock.WEEKDAYS & AutomationClock.dayBit(Calendar.SUNDAY)) != 0);
        assertEquals(7, Integer.bitCount(AutomationClock.EVERY_DAY));
    }

    @Test
    public void timesReadLikeAClock() {
        assertEquals("07:05", AutomationClock.time(425));
    }
}
