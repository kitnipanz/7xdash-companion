package com.kooo.evcam.nav;

/**
 * How often a following map moves and redraws. While the side view (blind-spot camera) is on
 * screen the map gives way: both draw through the same main thread and graphics chip, and a map
 * moving 30 times a second made the camera picture stutter. The camera is the one that must stay
 * smooth, so for the seconds it shows the map all but stands still: {@link #BUSY_FPS} frames a
 * second (12 was tried first and the camera still stuttered with a warning and a map together).
 */
public final class MapPace {

    /** About 30 camera moves a second: smooth, and light on the head unit (and the HUD). */
    static final float FRAME_MIN_S = 0.03f;
    static final int FPS = 60;
    /** While the side view shows. */
    static final int BUSY_FPS = 5;
    /** The longest time one frame of the follow loop counts, seconds: over the slow gap, or it is never reached. */
    static final float FRAME_MAX_S = 0.3f;

    /** While the side view shows, the route line's start is moved under the car only this often. */
    static final long BUSY_LINE_MS = 1000L;

    private static volatile boolean yielding;
    /** Every live map, told at once when the pace changes. Weak: a map that was never released just drops out. */
    private static final java.util.List<java.lang.ref.WeakReference<Runnable>> listeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private MapPace() {
    }

    /** Main thread: the side view came on screen or left it. Every map changes pace now, following the car or not. */
    public static void yieldToCamera(boolean on) {
        if (on == yielding) {
            return;
        }
        yielding = on;
        for (java.lang.ref.WeakReference<Runnable> ref : listeners) {
            Runnable listener = ref.get();
            if (listener == null) {
                listeners.remove(ref);
            } else {
                listener.run();
            }
        }
    }

    /** Main thread: {@code listener} runs when the pace changes. The caller keeps it alive. */
    static void addListener(Runnable listener) {
        listeners.add(new java.lang.ref.WeakReference<>(listener));
    }

    static void removeListener(Runnable listener) {
        for (java.lang.ref.WeakReference<Runnable> ref : listeners) {
            Runnable held = ref.get();
            if (held == null || held == listener) {
                listeners.remove(ref);
            }
        }
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
