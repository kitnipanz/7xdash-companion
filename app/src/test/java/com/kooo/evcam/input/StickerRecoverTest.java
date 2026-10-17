package com.kooo.evcam.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StickerRecoverTest {

    private static final String A = "AA:AA:AA:AA:AA:01";
    private static final String B = "AA:AA:AA:AA:AA:02";

    @Test
    public void redialStaysPendingSoAPressIsCaught() {
        assertEquals(300L, StickerRecover.backoffMs(0));
        assertEquals(300L, StickerRecover.backoffMs(50));
        assertTrue(StickerRecover.backoffMs(Integer.MAX_VALUE) < 1000L);
    }

    @Test
    public void nobodyDialsWhileTheRadioIsBusy() {
        assertEquals("", StickerRecover.next(Arrays.asList(A, B), null, null, "", true, 0));
    }

    @Test
    public void tappedStickerGoesFirst() {
        assertEquals(B, StickerRecover.next(Arrays.asList(A, B), null, null, B, false, 0));
    }

    @Test
    public void longestWaitingStickerGoesNextSoOneFailureCannotStarveTheOther() {
        Map<String, Long> last = new HashMap<>();
        last.put(A, 900L);
        last.put(B, 100L);
        assertEquals(B, StickerRecover.next(Arrays.asList(A, B), null, last, "", false, 1000));
        last.put(A, 50L);
        assertEquals(A, StickerRecover.next(Arrays.asList(A, B), null, last, "", false, 1000));
    }

    @Test
    public void stickerInBackoffWaitsItsTurn() {
        Map<String, Long> dueAt = new HashMap<>();
        dueAt.put(A, 5000L);
        List<String> idle = Arrays.asList(A, B);
        assertEquals(B, StickerRecover.next(idle, dueAt, null, A, false, 1000));
        assertEquals("", StickerRecover.next(Collections.singletonList(A), dueAt, null, "", false, 1000));
        assertEquals(4000L, StickerRecover.untilNextDue(Collections.singletonList(A), dueAt, 1000));
        assertEquals(-1L, StickerRecover.untilNextDue(Collections.emptyList(), dueAt, 1000));
    }

    @Test
    public void failureIsReportedOnceThenRarely() {
        assertFalse(StickerRecover.reportFailure(0));
        assertFalse(StickerRecover.reportFailure(2));
        assertTrue(StickerRecover.reportFailure(3));
        assertFalse(StickerRecover.reportFailure(6));
        assertTrue(StickerRecover.reportFailure(30));
    }


    @Test
    public void staleCacheStatusesClearTheCache() {
        assertTrue(StickerRecover.refreshCache(133));
        assertFalse(StickerRecover.refreshCache(8));
    }

}
