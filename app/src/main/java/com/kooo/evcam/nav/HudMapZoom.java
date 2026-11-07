package com.kooo.evcam.nav;

import com.kooo.evcam.R;

/**
 * How close the HUD map sits, set with the HUD map zoom action: following the speed, or fixed at
 * one of three distances. The stored number is {@code AppConfig}'s; pure, so it is unit tested.
 */
public final class HudMapZoom {

    public static final int ADAPTIVE = 0;
    public static final int FURTHEST = 1;
    public static final int MEDIUM = 2;
    public static final int CLOSE = 3;

    private static final float ZOOM_FURTHEST = 18f;
    private static final float ZOOM_MEDIUM = 19.25f;
    private static final float ZOOM_CLOSE = 20.5f;

    private HudMapZoom() {
    }

    /** The mode after {@code mode}: furthest, medium, close, adaptive, and round again. */
    public static int next(int mode) {
        switch (mode) {
            case FURTHEST: return MEDIUM;
            case MEDIUM: return CLOSE;
            case CLOSE: return ADAPTIVE;
            default: return FURTHEST;
        }
    }

    public static int labelRes(int mode) {
        switch (mode) {
            case FURTHEST: return R.string.nav_hud_zoom_furthest;
            case MEDIUM: return R.string.nav_hud_zoom_medium;
            case CLOSE: return R.string.nav_hud_zoom_close;
            default: return R.string.nav_hud_zoom_adaptive;
        }
    }

    /** The zoom to use, {@code adaptive} being what the speed asks for. */
    static float zoom(int mode, float adaptive) {
        switch (mode) {
            case FURTHEST: return ZOOM_FURTHEST;
            case MEDIUM: return ZOOM_MEDIUM;
            case CLOSE: return ZOOM_CLOSE;
            default: return adaptive;
        }
    }
}
