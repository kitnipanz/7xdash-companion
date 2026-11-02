package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SignalRecorderTest {

    @Test
    public void onlyAnsweredReadingsAreFollowed() {
        assertTrue(SignalRecorder.usable("0"));
        assertTrue(SignalRecorder.usable("255"));
        assertTrue(SignalRecorder.usable("12.5"));
        assertFalse(SignalRecorder.usable(null));
        assertFalse(SignalRecorder.usable("null"));
        assertFalse(SignalRecorder.usable("error IllegalArgumentException"));
    }
}
