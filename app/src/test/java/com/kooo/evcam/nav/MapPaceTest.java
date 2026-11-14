package com.kooo.evcam.nav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MapPaceTest {

    @Test
    public void fullPaceWhenTheSideViewIsAway() {
        assertEquals(MapPace.FRAME_MIN_S, MapPace.frameMinSeconds(false), 0f);
        assertEquals(MapPace.FPS, MapPace.maxFps(false));
    }

    @Test
    public void slowerWhileTheSideViewShows() {
        assertEquals(1f / MapPace.BUSY_FPS, MapPace.frameMinSeconds(true), 1e-6f);
        assertEquals(MapPace.BUSY_FPS, MapPace.maxFps(true));
        assertTrue(MapPace.frameMinSeconds(true) > MapPace.frameMinSeconds(false));
    }

    @Test
    public void slowGapStaysUnderTheLongestFrameTheMapCounts() {
        // the follow loop caps a frame's time: a longer gap would never be reached
        assertTrue(MapPace.frameMinSeconds(true) < MapPace.FRAME_MAX_S);
    }
}
