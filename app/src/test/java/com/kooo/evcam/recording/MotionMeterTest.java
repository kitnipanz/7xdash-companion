package com.kooo.evcam.recording;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MotionMeterTest {

    private static final int FPS = 25;

    /** Feeds one second of frames of {@code size} bytes; true if any frame reported motion. */
    private static boolean second(MotionMeter m, long[] clock, int size) {
        boolean motion = false;
        for (int i = 0; i < FPS; i++) {
            motion |= m.feed(size, i == 0, clock[0]);
            clock[0] += 1000L / FPS;
        }
        return motion;
    }

    @Test
    public void stillPictureNeverReportsMotion() {
        MotionMeter m = new MotionMeter();
        long[] clock = {0L};
        for (int s = 0; s < 60; s++) {
            assertFalse(second(m, clock, 8 * 1024));
        }
    }

    @Test
    public void bigJumpAfterWarmupIsMotion() {
        MotionMeter m = new MotionMeter();
        long[] clock = {0L};
        for (int s = 0; s < MotionMeter.WARMUP + 2; s++) {
            assertFalse(second(m, clock, 8 * 1024));
        }
        second(m, clock, 40 * 1024);
        // the busy second is judged when the next one starts
        assertTrue(second(m, clock, 8 * 1024));
    }

    @Test
    public void nothingDuringWarmup() {
        MotionMeter m = new MotionMeter();
        long[] clock = {0L};
        second(m, clock, 8 * 1024);
        second(m, clock, 200 * 1024);
        assertFalse(second(m, clock, 8 * 1024));
    }

    @Test
    public void keyframesAreLeftOut() {
        MotionMeter m = new MotionMeter();
        long[] clock = {0L};
        for (int s = 0; s < MotionMeter.WARMUP + 2; s++) {
            second(m, clock, 8 * 1024);
        }
        // one huge keyframe in an otherwise still second
        boolean motion = m.feed(2_000_000, true, clock[0]);
        for (int i = 1; i < FPS; i++) {
            clock[0] += 1000L / FPS;
            motion |= m.feed(8 * 1024, false, clock[0]);
        }
        clock[0] += 1000L / FPS;
        assertFalse(motion || second(m, clock, 8 * 1024));
    }

    @Test
    public void smallChangesInAQuietSceneAreNotMotion() {
        // a clock ticking on a dark picture: big ratio, few bytes
        assertFalse(MotionMeter.isMotion(60 * 1024, 2 * 1024));
        assertTrue(MotionMeter.isMotion(MotionMeter.MIN_BYTES, 10 * 1024));
        assertFalse(MotionMeter.isMotion(300 * 1024, 200 * 1024));
    }

    @Test
    public void baselineIsTheMedian() {
        MotionMeter m = new MotionMeter();
        long[] clock = {0L};
        int[] sizes = {1000, 9000, 2000, 3000, 100_000};
        for (int size : sizes) {
            second(m, clock, size);
        }
        m.feed(1, false, clock[0] + 1000L);
        // per-second totals are 24 non-key frames each
        assertEquals(3000L * (FPS - 1), m.baseline());
    }

    @Test
    public void armsOnlyWhenParkedRecordingAndSettled() {
        long settled = SentryLite.SETTLE_MS;
        assertTrue(SentryLite.shouldArm(true, true, "P", true, settled, null));
        assertFalse(SentryLite.shouldArm(false, true, "P", true, settled, null));
        assertFalse(SentryLite.shouldArm(true, false, "P", true, settled, null));
        assertFalse(SentryLite.shouldArm(true, true, "D", true, settled, 2));
        // the driver is still walking away
        assertFalse(SentryLite.shouldArm(true, true, "P", true, settled - 1, null));
        // screen on in P: only with the car's own Sentry Mode
        assertFalse(SentryLite.shouldArm(true, true, "P", false, 0, 0));
        assertTrue(SentryLite.shouldArm(true, true, "P", false, 0, 1));
        // locked car, gear unreadable
        assertTrue(SentryLite.shouldArm(true, true, null, true, settled, null));
        assertFalse(SentryLite.shouldArm(true, true, null, false, 0, 2));
    }
}
