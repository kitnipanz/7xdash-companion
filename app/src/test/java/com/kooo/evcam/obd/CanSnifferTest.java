package com.kooo.evcam.obd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class CanSnifferTest {

    private final CanSniffer sniffer = new CanSniffer();

    @Test
    public void aSteadyByteThatMovesIsAnEvent() {
        sniffer.frame("2B10011223344556677", "6", 0);
        sniffer.frame("2B10011223344556677", "6", 1_000);
        sniffer.frame("2B10011FF3344556677", "6", 5_000);

        List<CanSniffer.Row> rows = sniffer.recent(5_000, 1_000, 8);
        assertEquals(1, rows.size());
        assertEquals("2B1", rows.get(0).id);
        assertEquals("FF", rows.get(0).bytes[2]);
        assertTrue(rows.get(0).moved[2]);
        assertFalse(rows.get(0).moved[1]);
    }

    @Test
    public void aCounterIsNotAnEvent() {
        for (int i = 0; i < 50; i++) {
            sniffer.frame(String.format("3C2%02X00", i), "6", i * 100L);
        }
        assertTrue(sniffer.recent(5_000, 60_000, 8).isEmpty());
    }

    @Test
    public void firstSightingIsNotAnEvent() {
        sniffer.frame("1A00102", "6", 10_000);
        assertTrue(sniffer.recent(10_000, 60_000, 8).isEmpty());
        assertEquals(1, sniffer.ids());
    }

    @Test
    public void oldEventsDropOffAndNewestComesFirst() {
        sniffer.frame("1A000", "6", 0);
        sniffer.frame("1B000", "6", 0);
        sniffer.frame("1A001", "6", 3_000);
        sniffer.frame("1B001", "6", 4_000);

        assertEquals("1B0", sniffer.recent(4_000, 60_000, 8).get(0).id);
        assertEquals(1, sniffer.recent(4_000, 500, 8).size());
        assertEquals(1, sniffer.recent(4_000, 60_000, 1).size());
    }

    @Test
    public void readsBothIdSizesAndSpaces() {
        sniffer.frame("1DD01635AA", "7", 0);
        sniffer.frame("1DD01635BB", "7", 3_000);
        assertEquals("1DD01635", sniffer.recent(3_000, 1_000, 8).get(0).id);

        sniffer.frame("1A0 01 02", "6", 0);
        sniffer.frame("1A0 01 03", "6", 3_000);
        assertEquals("03", sniffer.recent(3_000, 1_000, 8).get(1).bytes[1]);
    }

    @Test
    public void notAFrame() {
        sniffer.frame("BUFFER FULL", "6", 0);
        sniffer.frame("STOPPED", "6", 0);
        sniffer.frame("CAN ERROR", "7", 0);
        sniffer.frame("1A", "6", 0);
        assertEquals(0, sniffer.ids());
    }

    @Test
    public void movedTextIsForTheLog() {
        sniffer.frame("2B10000", "6", 0);
        sniffer.frame("2B10040", "6", 3_000);
        assertEquals("2B1[1] 00>40", sniffer.movedText(3_000, 1_000));
        assertEquals("nothing", sniffer.movedText(30_000, 1_000));
    }

    /** The adapter listened on another setup meanwhile: nobody saw this change happen. */
    @Test
    public void aChangeAcrossAListeningGapIsNotAnEvent() {
        sniffer.frame("2B100", "6", 0);
        sniffer.frame("2B140", "6", CanSniffer.GAP_MS);
        assertTrue(sniffer.recent(CanSniffer.GAP_MS, 60_000, 8).isEmpty());
        assertEquals(1, sniffer.ids());

        sniffer.frame("2B100", "6", CanSniffer.GAP_MS + 3_000);
        assertEquals(1, sniffer.recent(CanSniffer.GAP_MS + 3_000, 1_000, 8).size());
    }
}
