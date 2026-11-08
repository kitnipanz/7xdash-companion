package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class SignalBoardTest {

    private static SignalBoard board() {
        SignalBoard b = new SignalBoard();
        b.add(new SignalBoard.Entry(0x21010100, "SETTING_FUNC_SEAT_HEAT", "ISeat", CarAmbientLab.Reader.FUNCTION, "0"));
        b.add(new SignalBoard.Entry(0x21020200, "SETTING_FUNC_DOOR_LOCK", "IVehicle", CarAmbientLab.Reader.FUNCTION, "1"));
        b.add(new SignalBoard.Entry(0x00100300, "SENSOR_TYPE_SPEED", "ISensor", CarAmbientLab.Reader.SENSOR_VALUE, "0.0"));
        return b;
    }

    @Test
    public void updateCountsOnlyRealChanges() {
        SignalBoard b = board();
        assertFalse(b.update(0, "0", 100, 0, ""));
        assertTrue(b.update(0, "2", 200, 0, ""));
        SignalBoard.Entry e = b.get(0);
        assertEquals("2", e.value);
        assertEquals("0", e.before);
        assertEquals(200, e.changedAt);
        assertEquals(1, e.changes);
        assertEquals(1, b.totalChanges());
    }

    @Test
    public void rowsPutLastChangedFirstThenName() {
        SignalBoard b = board();
        b.update(2, "12.5", 100, 0, "");
        b.update(0, "1", 300, 0, "");
        List<SignalBoard.Entry> rows = b.rows("", false);
        assertEquals("SETTING_FUNC_SEAT_HEAT", rows.get(0).name);
        assertEquals("SENSOR_TYPE_SPEED", rows.get(1).name);
        assertEquals("SETTING_FUNC_DOOR_LOCK", rows.get(2).name);
    }

    @Test
    public void changedOnlyHidesQuietOnes() {
        SignalBoard b = board();
        b.update(1, "0", 100, 0, "");
        List<SignalBoard.Entry> rows = b.rows(null, true);
        assertEquals(1, rows.size());
        assertEquals("SETTING_FUNC_DOOR_LOCK", rows.get(0).name);
    }

    @Test
    public void searchMatchesEveryWordInNameOrId() {
        SignalBoard b = board();
        assertEquals(1, b.rows("door", false).size());
        assertEquals(1, b.rows("func seat", false).size());
        assertEquals(0, b.rows("seat door", false).size());
        assertEquals(1, b.rows("0x21020200", false).size());
        assertEquals(1, b.rows("2102", false).size());
    }

    @Test
    public void pushedFindsEntryById() {
        SignalBoard b = board();
        assertTrue(b.pushed(0x21020200, 0, "0", 50, 0, ""));
        assertFalse(b.pushed(0x21020200, 0, "0", 60, 0, ""));
        assertFalse(b.pushed(0x12345678, 0, "5", 60, 0, ""));
        assertEquals(1, b.totalChanges());
    }

    @Test
    public void onlyFunctionReadingsCanBeSent() {
        SignalBoard b = board();
        assertTrue(b.get(0).sendable());
        assertFalse(b.get(2).sendable());
    }

    @Test
    public void exportHasChangesMarksSendsAndAllValues() {
        SignalBoard b = board();
        b.update(0, "3", 100, 0, "P 0");
        b.mark(0, "Mark 1");
        b.sent(0, b.get(1), 0, "sent · reads 0");
        String text = b.export("head");
        assertTrue(text.startsWith("head"));
        assertTrue(text.contains("SETTING_FUNC_SEAT_HEAT\t0x21010100\t0\t3\t| P 0"));
        assertTrue(text.contains("---- Mark 1 ----"));
        assertTrue(text.contains("SEND SETTING_FUNC_DOOR_LOCK"));
        assertTrue(text.contains("== All signals now (3) =="));
    }

    @Test
    public void logKeepsOnlyTheNewest() {
        SignalBoard b = board();
        for (int i = 0; i < SignalBoard.LOG_KEEP + 10; i++) {
            b.mark(0, "m" + i);
        }
        String text = b.export("");
        assertFalse(text.contains("---- m0 ----"));
        assertTrue(text.contains("---- m" + (SignalBoard.LOG_KEEP + 9) + " ----"));
    }

    @Test
    public void sendOnlyParkedAndStill() {
        assertNull(SignalBoard.sendBlock("P", 0f));
        assertNotNull(SignalBoard.sendBlock("D", 0f));
        assertNotNull(SignalBoard.sendBlock("P", 12f));
        assertNotNull(SignalBoard.sendBlock("P", null));
        assertNotNull(SignalBoard.sendBlock(null, 0f));
    }

    @Test
    public void parseValueTakesDecimalNegativeAndHex() {
        assertEquals(Integer.valueOf(5), SignalBoard.parseValue(" 5 "));
        assertEquals(Integer.valueOf(-1), SignalBoard.parseValue("-1"));
        assertEquals(Integer.valueOf(255), SignalBoard.parseValue("0xFF"));
        assertEquals(Integer.valueOf(0x80000000), SignalBoard.parseValue("0x80000000"));
        assertNull(SignalBoard.parseValue("on"));
        assertNull(SignalBoard.parseValue(""));
        assertNull(SignalBoard.parseValue(null));
    }
}
