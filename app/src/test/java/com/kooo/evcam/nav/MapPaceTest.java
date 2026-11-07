package com.kooo.evcam.nav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MapPaceTest {

    private static final float FULL = 0.03f;
    private final MapPace pace = new MapPace();
    private long now = 10_000L;

    private void frames(int count, long every) {
        for (int i = 0; i < count; i++) {
            now += every;
            pace.videoFrame(now);
        }
    }

    @Test
    public void mapStaysAtFullPaceWhileThePictureKeepsTime() {
        frames(100, 33);
        assertEquals(FULL, pace.gap(true, now), 0f);
    }

    @Test
    public void latePictureSlowsTheMap_oneStepAtATime() {
        frames(5, 33);
        frames(1, 150);
        float one = pace.gap(true, now);
        assertTrue(one > FULL);
        frames(1, 150);
        assertEquals("waits to see what the last step did", one, pace.gap(true, now), 0f);
        now += MapPace.STEP_MS;
        frames(1, 150);
        assertTrue(pace.gap(true, now) > one);
    }

    @Test
    public void goesBackUpOnceThePictureKeepsTimeAgain() {
        frames(5, 33);
        frames(1, 150);
        assertTrue(pace.gap(true, now) > FULL);
        frames((int) (MapPace.RECOVER_MS / 33) + 2, 33);
        assertEquals(FULL, pace.gap(true, now), 0f);
    }

    @Test
    public void thePictureStartingIsNotLag() {
        frames(1, 5_000);
        frames(1, 5_000);
        assertEquals(FULL, pace.gap(true, now), 0f);
    }

    @Test
    public void closingTheSideViewPutsTheMapBackAtFullPace() {
        frames(5, 33);
        frames(1, 150);
        assertEquals(FULL, pace.gap(false, now), 0f);
        frames(1, 33);
        assertEquals(FULL, pace.gap(true, now), 0f);
    }
}
