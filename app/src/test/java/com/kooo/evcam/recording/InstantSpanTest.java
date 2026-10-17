package com.kooo.evcam.recording;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class InstantSpanTest {

    @Test
    public void middleOfFileKeepsTenSecondsEachSide() {
        long fileStart = 1_000_000L;
        InstantSpan span = InstantSpan.inFile(fileStart, fileStart + 20_000L, fileStart + 40_000L);
        assertEquals(20_000_000L, span.startUs);
        assertEquals(40_000_000L, span.endUs);
    }

    @Test
    public void fileThatStartsInsideTheWindowStartsAtZero() {
        long windowStart = 50_000L;
        long windowEnd = 70_000L;
        InstantSpan span = InstantSpan.inFile(60_000L, windowStart, windowEnd);
        assertEquals(0L, span.startUs);
        assertEquals(10_000_000L, span.endUs);
    }

    @Test
    public void fileThatStartsAtTheWindowEndIsSkipped() {
        assertNull(InstantSpan.inFile(70_000L, 50_000L, 70_000L));
    }

    @Test
    public void sampleBudgetStopsAtTheFlashExcerpt() {
        assertEquals(440, InstantSpan.videoSampleBudget(20));
        assertEquals(440, InstantSpan.videoSampleBudget(0));
    }

    @Test
    public void oneFlashKeepsTheSegmentThatContainsIt() {
        long[] starts = {0L, 60_000L};
        assertEquals(1, InstantSpan.containing(starts, 65_000L));
        assertEquals(0, InstantSpan.containing(starts, 5_000L));
        assertEquals(0, InstantSpan.containing(starts, -1L));
    }
}
