package com.kooo.evcam.hud;

import android.content.Context;

import com.kooo.evcam.AppConfig;

/**
 * 抬头显示上的提示卡片：7xDash 自己的事（开始 / 停止录像、抓拍存好了、拍照、调暗、喊话……）
 * 在中控弹提示的同时，也在抬头显示上用大字写一遍。开车时眼睛不用离开前方。
 *
 * <p>不读别的应用的通知：那要「通知使用权」，车机上没有那个设置页（2026-10-17 实车）。
 * 设置在主页「抬头显示通知」卡片上开关；没有抬头显示的车什么都不做。</p>
 *
 * <p>截字和摆位是纯函数，单测钉着。</p>
 */
public final class HudNotice {

    /** 一张卡片停多久。 */
    public static final long SHOW_MS = 4_000L;
    /** 一行最多几个字，多了用省略号收尾。抬头显示上太长看不完。 */
    public static final int MAX_TEXT = 60;
    public static final int MAX_TITLE = 40;

    private HudNotice() {
    }

    /** 开着「抬头显示通知」时把这一句放到抬头显示上。任何线程都能调。 */
    public static void post(Context context, CharSequence title) {
        post(context, title, null);
    }

    public static void post(Context context, CharSequence title, CharSequence detail) {
        if (context == null || isBlank(title) || !new AppConfig(context).isHudNotificationsEnabled()) {
            return;
        }
        HudNoticeWindow.show(context, "7xDash", clip(title, MAX_TITLE), clip(detail, MAX_TEXT));
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

    /** 离抬头显示左沿、上沿的空，和侧视（{@code HudFrame}）贴边留的一样：那一块实车看得全。 */
    public static final int EDGE = 8;

    /**
     * 卡片左对齐，贴着整块屏的左上角，只留 {@link #EDGE} 的空。{@code [x, y, width, height]}。
     *
     * <p>实车上整块屏最左边那一截看不到，所以真正用的是下面带 {@code leftPercent} 的那个。
     * 宽 40%、高 36%，再大也收在屏里。窗口本身从 (0,0) 起，卡片在窗口里挪到 (x, y)，
     * 见 {@link HudNoticeWindow}。</p>
     */
    public static int[] place(int displayW, int displayH) {
        return place(displayW, displayH, 0);
    }

    /**
     * Same, with the card's left edge moved in by {@code leftPercent} of the width: on the 7X
     * the leftmost part of the HUD panel is not visible, and how much is hidden is set in
     * Settings (AppConfig.getHudNoticeLeftPercent).
     */
    public static int[] place(int displayW, int displayH, int leftPercent) {
        if (displayW < 2 || displayH < 2) {
            return new int[]{0, 0, Math.max(displayW, 1), Math.max(displayH, 1)};
        }
        int inset = displayW * Math.max(0, Math.min(90, leftPercent)) / 100;
        int x = Math.min(Math.max(EDGE, inset), Math.max(0, displayW - displayW / 4 - 1));
        int y = Math.min(EDGE, displayH / 4);
        int width = Math.max(1, Math.min(displayW * 40 / 100, displayW - x - EDGE));
        int height = Math.max(1, Math.min(displayH * 36 / 100, displayH - y * 2));
        return new int[]{x, y, width, height};
    }

    private static boolean isBlank(CharSequence value) {
        return value == null || value.toString().trim().isEmpty();
    }
}
