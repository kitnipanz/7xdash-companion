package com.kooo.evcam.recording;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

public class SavedClipsTest {

    @Test
    public void roundTripKeepsStampsSorted() {
        Set<String> in = new LinkedHashSet<>(Arrays.asList(
                "20261003_120100", "20261003_120000"));
        String raw = SavedClips.write(in);
        Set<String> out = SavedClips.parse(raw);
        assertEquals(Arrays.asList("20261003_120000", "20261003_120100"),
                new java.util.ArrayList<>(out));
    }

    @Test
    public void protectsByGroupStamp() {
        Set<String> stamps = SavedClips.parse("20261003_120000");
        assertTrue(SavedClips.isProtected(stamps, "20261003_120000_surround.mp4"));
        assertFalse(SavedClips.isProtected(stamps, "20261003_120100_surround.mp4"));
        assertNull(SavedClips.stampOf("holiday.mp4"));
        assertEquals("20261003_120000", SavedClips.stampOf("20261003_120000_front.mp4"));
    }

    @Test
    public void rejectsBrokenLines() {
        assertTrue(SavedClips.parse("nope\n2026_1\n").isEmpty());
        assertNull(SavedClips.normalize("20261003-120000"));
    }
}
