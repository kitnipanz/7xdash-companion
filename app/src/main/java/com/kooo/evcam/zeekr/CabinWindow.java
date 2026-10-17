package com.kooo.evcam.zeekr;

/**
 * Size and position of the cabin-passenger window. No Android types, so the clamps can be tested.
 *
 * <p>The picture keeps its shape. The window's width and height only decide how much of the
 * front cabin is visible, the same way Super mirror's window does.</p>
 */
public final class CabinWindow {

    /** Front cabin. The internal key is {@code back}; that name is not "the rear camera". */
    public static final String CAMERA_KEY = "back";

    public static final int MIN_PX = 120;
    public static final int DEFAULT_WIDTH = 720;
    public static final int DEFAULT_HEIGHT = 480;

    private CabinWindow() {
    }

    /** Keep a side between {@link #MIN_PX} and the screen. A window smaller than the minimum cannot be grabbed. */
    public static int clamp(int px, int screenLimit) {
        int max = Math.max(MIN_PX, screenLimit);
        return Math.max(MIN_PX, Math.min(max, px));
    }

    /** Keep the window on screen. A window larger than the screen sits at 0. */
    public static int clampOrigin(int origin, int window, int screen) {
        if (window >= screen) {
            return 0;
        }
        if (origin < 0) {
            return 0;
        }
        int max = screen - window;
        return Math.min(origin, max);
    }
}
