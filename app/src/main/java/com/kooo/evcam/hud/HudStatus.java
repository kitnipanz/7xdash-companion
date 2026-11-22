package com.kooo.evcam.hud;

import java.util.Locale;

/**
 * The always-on status card on the HUD ({@link HudStatusWindow}): what it can show, and where it
 * sits. The place is shares of the HUD {@code {x, y, width, height}}, so it survives a different
 * HUD resolution. Pure Java, see the test.
 */
public final class HudStatus {

    /** What the card can show, one line each, in this order. */
    public enum Item {
        /** REC and how long, starting, waiting for cameras, or not recording (and why). */
        RECORDING,
        /** Cameras recording out of the ones planned, while recording. */
        CAMERAS,
        /** USB stick missing, or its free space. */
        USB,
        /** The last instant capture: saved or failed, for {@link #CAPTURE_SHOW_MS}. */
        CAPTURE,
        /** What an announcement is saying while it plays, or that it failed. */
        ANNOUNCE,
        /** The time. */
        CLOCK
    }

    /** How long a failed announcement stays on the card. */
    public static final long ANNOUNCE_FAIL_SHOW_MS = 10_000L;

    /** Long phrases cut to this many characters on the card. */
    public static final int ANNOUNCE_MAX_CHARS = 28;

    /** The phrase as the card shows it: on one line, long ones cut with an ellipsis. */
    public static String clip(String text) {
        if (text == null) {
            return "";
        }
        String one = text.replace('\n', ' ').trim();
        return one.length() <= ANNOUNCE_MAX_CHARS ? one : one.substring(0, ANNOUNCE_MAX_CHARS - 1).trim() + "…";
    }

    /** How long a capture result stays on the card. */
    public static final long CAPTURE_SHOW_MS = 30_000L;

    /** The card never gets smaller than this share of the HUD. */
    static final float MIN_W = 0.12f;
    static final float MIN_H = 0.12f;

    private HudStatus() {
    }

    /** On out of the box: recording and announcements. */
    public static boolean defaultOn(Item item) {
        return item == Item.RECORDING || item == Item.ANNOUNCE;
    }

    /** Kept inside the HUD, at least the minimum size. */
    public static float[] clamp(float x, float y, float w, float h) {
        w = Math.max(MIN_W, Math.min(1f, w));
        h = Math.max(MIN_H, Math.min(1f, h));
        x = Math.max(0f, Math.min(1f - w, x));
        y = Math.max(0f, Math.min(1f - h, y));
        return new float[] {x, y, w, h};
    }

    /**
     * Until it is moved: on the right, inside the part of the HUD the driver can see
     * ({@code rightPercent} of the width on the right is out of sight on the car).
     */
    public static float[] defaults(int rightPercent) {
        float w = 0.26f;
        float right = Math.max(0, Math.min(60, rightPercent)) / 100f;
        return clamp(1f - right - w, 0.08f, w, 0.6f);
    }

    /** In pixels {@code [x, y, width, height]}. */
    public static int[] toPixels(int displayW, int displayH, float[] f) {
        f = clamp(f[0], f[1], f[2], f[3]);
        int w = Math.max(1, Math.round(f[2] * displayW));
        int h = Math.max(1, Math.round(f[3] * displayH));
        int x = Math.max(0, Math.min(displayW - w, Math.round(f[0] * displayW)));
        int y = Math.max(0, Math.min(displayH - h, Math.round(f[1] * displayH)));
        return new int[] {x, y, w, h};
    }

    /** The stored place, or {@link #defaults} when nothing (readable) is stored. */
    public static float[] placeOr(String stored, int rightPercent) {
        float[] f = restore(stored);
        return f != null ? f : defaults(rightPercent);
    }

    /** "x,y,w,h" for the settings. */
    public static String store(float[] f) {
        return f == null ? "" : String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", f[0], f[1], f[2], f[3]);
    }

    /** Null for nothing stored or something unreadable. */
    public static float[] restore(String s) {
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

    /** "4:05" under an hour, "1:04:05" from an hour on. Negative counts as 0. */
    public static String duration(long ms) {
        long s = Math.max(0L, ms) / 1000L;
        long h = s / 3600L;
        long m = (s % 3600L) / 60L;
        long sec = s % 60L;
        return h > 0
                ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.US, "%d:%02d", m, sec);
    }

    /** "23.4 GB" from 10 GB down to 1 GB, "512 MB" under that, whole GB from 100 up. */
    public static String size(long bytes) {
        double gb = Math.max(0L, bytes) / 1_000_000_000d;
        if (gb >= 100d) {
            return String.format(Locale.US, "%d GB", Math.round(gb));
        }
        if (gb >= 1d) {
            return String.format(Locale.US, "%.1f GB", gb);
        }
        return String.format(Locale.US, "%d MB", Math.round(gb * 1000d));
    }
}
