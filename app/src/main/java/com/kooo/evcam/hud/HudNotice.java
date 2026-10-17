package com.kooo.evcam.hud;

/**
 * 抬头显示上的通知卡片：哪些通知上屏、文字截多长、放在哪。纯函数，单测钉着。
 *
 * <p>只上「人发来的、一眼能看完的」：自己的通知不上（录像、喊话这些常驻条会刷屏），
 * 常驻（ongoing）的不上（导航、音乐），分组摘要不上（同一组的单条已经上过），
 * 没有标题也没有正文的不上。</p>
 */
public final class HudNotice {

    /** 一张卡片停多久。 */
    public static final long SHOW_MS = 6_000L;
    /** 正文最多几个字，多了用省略号收尾。抬头显示上第二行看不清。 */
    public static final int MAX_TEXT = 60;
    public static final int MAX_TITLE = 30;

    private HudNotice() {
    }

    public static boolean accept(String pkg, String ownPkg, boolean ongoing, boolean groupSummary,
                                 CharSequence title, CharSequence text) {
        if (pkg == null || pkg.equals(ownPkg) || ongoing || groupSummary) {
            return false;
        }
        return !isBlank(title) || !isBlank(text);
    }

    public static String clip(CharSequence value, int max) {
        if (value == null) {
            return "";
        }
        String s = value.toString().replace('\n', ' ').trim();
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, Math.max(0, max - 1)).trim() + "…";
    }

    /**
     * 卡片放在抬头显示正中偏上：左右两沿留给盲区画面（{@code HudFrame} 贴着外沿放），
     * 中间这一块不和它抢。{@code [x, y, width, height]}。
     */
    public static int[] place(int displayW, int displayH) {
        if (displayW < 2 || displayH < 2) {
            return new int[]{0, 0, Math.max(displayW, 1), Math.max(displayH, 1)};
        }
        int width = displayW * 40 / 100;
        int height = displayH * 36 / 100;
        int x = (displayW - width) / 2;
        int y = displayH * 8 / 100;
        return new int[]{x, y, width, height};
    }

    private static boolean isBlank(CharSequence value) {
        return value == null || value.toString().trim().isEmpty();
    }
}
