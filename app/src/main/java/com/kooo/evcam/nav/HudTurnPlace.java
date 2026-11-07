package com.kooo.evcam.nav;

import com.kooo.evcam.hud.HudNotice;

/**
 * Where the turn card sits on the HUD and how big it is, as shares of the HUD screen
 * {@code {x, y, width, height}}, so it survives a different HUD resolution. Set by hand in the
 * Navigation screen ({@link HudPlacementView}); until then it is where the notice cards go
 * ({@link HudNotice#place}, with Settings' hidden left and right edges). Pure Java, see the test.
 */
final class HudTurnPlace {

    /** The card never gets smaller than this share of the HUD. */
    static final float MIN_W = 0.2f;
    static final float MIN_H = 0.12f;

    private HudTurnPlace() {
    }

    /** Kept inside the HUD, at least the minimum size. */
    static float[] clamp(float x, float y, float w, float h) {
        w = Math.max(MIN_W, Math.min(1f, w));
        h = Math.max(MIN_H, Math.min(1f, h));
        x = Math.max(0f, Math.min(1f - w, x));
        y = Math.max(0f, Math.min(1f - h, y));
        return new float[] {x, y, w, h};
    }

    /** The notice cards' place, as shares: what the card uses until it is moved. */
    static float[] defaults(int displayW, int displayH, int leftPercent, int rightPercent) {
        if (displayW < 2 || displayH < 2) {
            return clamp(0f, 0f, 1f, 0.36f);
        }
        int[] box = HudNotice.place(displayW, displayH, leftPercent, rightPercent);
        return clamp((float) box[0] / displayW, (float) box[1] / displayH,
                (float) box[2] / displayW, (float) box[3] / displayH);
    }

    /** The HUD map's place until it is moved: the right part of the HUD, clear of the card. */
    static float[] mapDefaults() {
        return clamp(0.56f, 0.06f, 0.4f, 0.88f);
    }

    /** In pixels {@code [x, y, width, height]}. */
    static int[] toPixels(int displayW, int displayH, float[] f) {
        f = clamp(f[0], f[1], f[2], f[3]);
        int w = Math.max(1, Math.round(f[2] * displayW));
        int h = Math.max(1, Math.round(f[3] * displayH));
        int x = Math.max(0, Math.min(displayW - w, Math.round(f[0] * displayW)));
        int y = Math.max(0, Math.min(displayH - h, Math.round(f[1] * displayH)));
        return new int[] {x, y, w, h};
    }

    /** In pixels {@code [x, y, width, height]}; {@code shares} null means {@link #defaults}. */
    static int[] place(int displayW, int displayH, float[] shares, int leftPercent, int rightPercent) {
        if (shares == null) {
            // exactly the notice cards' box, not rounded through shares
            return HudNotice.place(displayW, displayH, leftPercent, rightPercent);
        }
        return toPixels(displayW, displayH, shares);
    }

    /** "x,y,w,h" for the settings. */
    static String store(float[] f) {
        return f == null ? "" : String.format(java.util.Locale.US, "%.4f,%.4f,%.4f,%.4f", f[0], f[1], f[2], f[3]);
    }

    /** Null for nothing stored or something unreadable. */
    static float[] restore(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        String[] p = s.split(",");
        if (p.length != 4) {
            return null;
        }
        try {
            return clamp(Float.parseFloat(p[0]), Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
