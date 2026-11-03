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

    @Test
    public void blindSpotCandidatesAreWatchedByCallback() {
        assertTrue(SignalRecorder.pushedCandidate("SETTING_FUNC_BLIND_SPOT_DETECTION_WARNING"));
        assertTrue(SignalRecorder.pushedCandidate("PAS_FUNC_RCTA_LEFT_WARNING"));
        assertTrue(SignalRecorder.pushedCandidate("BCM_DOOR_OPEN_WARN_LEFT_INDCN"));
        assertTrue(SignalRecorder.pushedCandidate("SETTING_FUNC_DOW_WARNING_STATUS"));
        assertTrue(SignalRecorder.pushedCandidate("DOW_WARNING_STATUS_LEVEL_1"));
        assertTrue(SignalRecorder.pushedCandidate("SETTING_FUNC_LANE_CHANGE_ASSIST_WARNING"));
        assertTrue(SignalRecorder.pushedCandidate("SETTING_FUNC_LIGHT_INDCR_STS"));
        // DOW is a word of its own, not part of WINDOW
        assertFalse(SignalRecorder.pushedCandidate("BCM_FUNC_WINDOW_POS"));
        assertFalse(SignalRecorder.pushedCandidate("SETTING_FUNC_AMBIENCE_LIGHT_MAINZONES_COLOR_SET"));
        assertFalse(SignalRecorder.pushedCandidate(null));
    }
}
