package com.kooo.evcam.nav;

/**
 * How often a following map moves and redraws. While the side view (blind-spot camera) is on
 * screen the map gives way: both draw through the same main thread and graphics chip, and a map
 * moving 30 times a second made the camera picture stutter. The camera is the one that must stay
 * smooth; a map at {@link #BUSY_FPS} for the seconds a turn signal is on still reads fine.
 */
public final class MapPace {

    /** About 30 camera moves a second: smooth, and light on the head unit (and the HUD). */
    static final float FRAME_MIN_S = 0.03f;
    static final int FPS = 60;
    /** While the side view shows. */
    static final int BUSY_FPS = 12;

    private static volatile boolean yielding;

    private MapPace() {
    }

    /** Main thread: the side view came on screen or left it. */
    public static void yieldToCamera(boolean on) {
        yielding = on;
    }

    static boolean yielding() {
        return yielding;
    }

    /** The shortest time between two camera moves of a following map, seconds. */
    static float frameMinSeconds(boolean yielding) {
        return yielding ? 1f / BUSY_FPS : FRAME_MIN_S;
    }

    /** The most frames a second the map engine draws. */
    static int maxFps(boolean yielding) {
        return yielding ? BUSY_FPS : FPS;
    }
}
