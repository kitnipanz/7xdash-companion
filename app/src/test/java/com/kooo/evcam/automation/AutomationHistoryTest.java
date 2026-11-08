package com.kooo.evcam.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class AutomationHistoryTest {

    private static AutomationHistory.Entry entry(long at) {
        return new AutomationHistory.Entry(at, "Shift to D", "Drive mode: Sport", "", false);
    }

    @Test
    public void newestComesFirst() {
        String json = AutomationHistory.add(AutomationHistory.add(null, entry(1)), entry(2));
        List<AutomationHistory.Entry> read = AutomationHistory.parse(json);
        assertEquals(2, read.get(0).at);
        assertEquals(1, read.get(1).at);
        assertEquals("Drive mode: Sport", read.get(0).what);
    }

    @Test
    public void onlyTheLatestAreKept() {
        String json = null;
        for (int i = 0; i < AutomationHistory.KEEP + 5; i++) {
            json = AutomationHistory.add(json, entry(i));
        }
        List<AutomationHistory.Entry> read = AutomationHistory.parse(json);
        assertEquals(AutomationHistory.KEEP, read.size());
        assertEquals(AutomationHistory.KEEP + 4, read.get(0).at);
    }

    @Test
    public void damagedHistoryIsEmpty() {
        assertTrue(AutomationHistory.parse("not json").isEmpty());
        assertTrue(AutomationHistory.parse(null).isEmpty());
    }
}
