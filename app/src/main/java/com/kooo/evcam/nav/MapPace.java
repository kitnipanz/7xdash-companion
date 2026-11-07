package com.kooo.evcam.nav;

import com.kooo.evcam.blackbox.BlackBox;

/**
 * How often the centre-screen maps move, so a side view's camera picture and the map can both stay
 * smooth. The HUD map is never slowed: the driver reads it while looking ahead. The maps run at
 * full pace until the picture actually falls behind, then slow one step at a time, and go back up
 * once it has kept time for a while. Each slowdown goes to the black box: that is the only way to
 * see what the car can really do. Main thread only.
 */
public final class MapPace {

    /** Seconds between camera moves, fastest first: about 33, 20, 12 and 8 a second. */
    private static final float[] GAPS_S = {0.03f, 0.05f, 0.08f, 0.125f};
    /** The full pace, in seconds between camera moves. */
    static final float FULL_S = GAPS_S[0];
    /** A camera picture this long after the last one is late. Side cameras run at 25 to 30 a second. */
    static final long LATE_MS = 80L;
    /** Longer than this is the picture starting, not lag. */
    private static final long START_MS = 1_000L;
    /** One step at a time: the last one needs this long to show. */
    static final long STEP_MS = 500L;
    /** The picture kept time this long: try one step faster. */
    static final long RECOVER_MS = 2_000L;

    private static final MapPace INSTANCE = new MapPace();

    public static MapPace get() {
        return INSTANCE;
    }

    private int level;
    private long lastFrameAt;
    private long lastChangeAt;
    private long lastLateAt;

    MapPace() {
    }

    /** A camera picture of a side view that is on screen arrived. */
    public void videoFrame(long nowMs) {
        long since = nowMs - lastFrameAt;
        lastFrameAt = nowMs;
        if (since > START_MS || since < LATE_MS) {
            return;
        }
        lastLateAt = nowMs;
        if (level < GAPS_S.length - 1 && nowMs - lastChangeAt >= STEP_MS) {
            level++;
            lastChangeAt = nowMs;
            BlackBox.note("Map pace: side view picture " + since + " ms late, map every "
                    + Math.round(GAPS_S[level] * 1000) + " ms");
        }
    }

    /** Seconds between camera moves now. */
    float gap(boolean sideViewShowing, long nowMs) {
        if (!sideViewShowing) {
            level = 0;
            lastFrameAt = 0;
            return GAPS_S[0];
        }
        if (level > 0 && nowMs - Math.max(lastLateAt, lastChangeAt) >= RECOVER_MS) {
            level--;
            lastChangeAt = nowMs;
        }
        return GAPS_S[level];
    }
}
