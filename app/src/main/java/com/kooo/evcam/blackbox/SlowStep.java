package com.kooo.evcam.blackbox;

import com.kooo.evcam.AppLog;

/**
 * A step on the main thread that took long enough to make the screen stutter gets one black box
 * line with its name and time, to find what freezes navigation.
 */
public final class SlowStep {

    /** Longer than this is written down. */
    static final long SLOW_MS = 150L;

    private SlowStep() {
    }

    /** {@code fromMs} and {@code toMs} from {@code SystemClock.uptimeMillis()}. */
    public static void check(String step, long fromMs, long toMs) {
        long took = toMs - fromMs;
        if (took >= SLOW_MS) {
            String line = "Slow on main thread: " + step + " took " + took + " ms";
            AppLog.w("SlowStep", line);
            BlackBox.noteImportant(line);
        }
    }
}
