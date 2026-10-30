package com.kooo.evcam.recording;

import java.util.Arrays;

/**
 * Motion from the surround recorder's encoded frame sizes, without decoding a single frame. A
 * parked car sees a still picture, and the encoder writes tiny in-between frames (P frames) for a
 * still picture; someone walking past makes them several times bigger. So: add up the in-between
 * frame bytes per second, keep the median of the last {@link #HISTORY} seconds as the "still" level,
 * and call it motion when a second is {@link #RATIO} times that and over {@link #MIN_BYTES}.
 *
 * <p>Keyframes (one every 3 seconds) are always big and are left out. The first {@link #WARMUP}
 * seconds after {@link #reset} only learn the still level. Pure Java, see {@code MotionMeterTest}.
 * Called on the encoder thread only.</p>
 */
public final class MotionMeter {

    static final int HISTORY = 30;
    static final int WARMUP = 10;
    static final float RATIO = 2.5f;
    /** Below this many in-between bytes per second nothing is moving, whatever the ratio says. */
    static final long MIN_BYTES = 120L * 1024;
    static final long SECOND_MS = 1000L;

    private final long[] seconds = new long[HISTORY];
    private int filled;
    private int next;
    private long bucketStartMs = -1L;
    private long bucketBytes;
    /** The last finished second and its still level, for the black box. */
    private long lastBytes;
    private long lastBaseline;

    public void reset() {
        filled = 0;
        next = 0;
        bucketStartMs = -1L;
        bucketBytes = 0L;
    }

    /** One encoded frame. True once, when the second it closes looks like motion. */
    public boolean feed(int size, boolean keyframe, long nowMs) {
        boolean motion = false;
        if (bucketStartMs < 0) {
            bucketStartMs = nowMs;
        } else if (nowMs - bucketStartMs >= SECOND_MS) {
            motion = close(bucketBytes);
            bucketStartMs = nowMs;
            bucketBytes = 0L;
        }
        if (!keyframe && size > 0) {
            bucketBytes += size;
        }
        return motion;
    }

    private boolean close(long bytes) {
        long baseline = baseline();
        boolean warm = filled >= WARMUP;
        boolean motion = warm && isMotion(bytes, baseline);
        lastBytes = bytes;
        lastBaseline = baseline;
        seconds[next] = bytes;
        next = (next + 1) % HISTORY;
        if (filled < HISTORY) {
            filled++;
        }
        return motion;
    }

    static boolean isMotion(long bytes, long baseline) {
        return bytes >= MIN_BYTES && bytes >= baseline * RATIO;
    }

    /** Median of the seconds seen so far; 0 before the first one. */
    long baseline() {
        if (filled == 0) {
            return 0L;
        }
        long[] copy = Arrays.copyOf(seconds, filled);
        Arrays.sort(copy);
        return copy[filled / 2];
    }

    public String describe() {
        return (lastBytes / 1024) + " KB/s vs still " + (lastBaseline / 1024) + " KB/s";
    }
}
