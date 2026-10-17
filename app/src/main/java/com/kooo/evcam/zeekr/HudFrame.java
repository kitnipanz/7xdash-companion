package com.kooo.evcam.zeekr;

/**
 * 抬头显示上那一小块侧视的位置。左灯贴左沿、右灯贴右沿。
 *
 * <p>原厂在车标周围已经有一块底，盖不掉。画面靠外沿。宽和高都是这块屏的百分比，设置里调。</p>
 */
public final class HudFrame {

    private static final int EDGE = 8;

    private HudFrame() {
    }

    /** 开着 {@code bothOnLeft} 时右灯也贴左沿。关着时跟转向灯走。 */
    public static boolean pinToLeftEdge(boolean leftSignal, boolean bothOnLeft) {
        return leftSignal || bothOnLeft;
    }

    /** 宽高用默认百分比。{@code [x, y, width, height]}。 */
    public static int[] place(int displayW, int displayH, boolean left) {
        return place(displayW, displayH, left, 27, 45);
    }

    /** {@code widthPercent} 是这块屏宽度的百分比。高度用默认 45%。 */
    public static int[] place(int displayW, int displayH, boolean left, int widthPercent) {
        return place(displayW, displayH, left, widthPercent, 45);
    }

    /** {@code heightPercent} 是这块屏高度的百分比。 */
    public static int[] place(int displayW, int displayH, boolean left, int widthPercent, int heightPercent) {
        if (displayW < 2 || displayH < 2) {
            return new int[]{0, 0, Math.max(displayW, 1), Math.max(displayH, 1)};
        }
        int percent = Math.max(1, Math.min(100, widthPercent));
        int tall = Math.max(1, Math.min(100, heightPercent));
        int height = Math.max(2, displayH * tall / 100);
        if (height > displayH) {
            height = displayH;
        }
        int width = Math.max(2, displayW * percent / 100);
        int limit = Math.max(2, displayW - EDGE * 2);
        if (width > limit) {
            width = limit;
        }
        int x = left ? EDGE : displayW - EDGE - width;
        if (x < 0) {
            x = 0;
        }
        if (x + width > displayW) {
            x = Math.max(0, displayW - width);
        }
        int y = Math.max(0, (displayH - height) / 2);
        return new int[]{x, y, width, height};
    }
}
