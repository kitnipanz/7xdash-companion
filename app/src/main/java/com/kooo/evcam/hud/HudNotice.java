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
     * 卡片左对齐，从抬头显示看得见那一块的左上角起，只留 {@link #EDGE} 的空。{@code [x, y, width, height]}。
     *
     * <p>看得见的那一块就是整块屏：侧视、后视镜也都按整块屏摆（{@code HudFrame}、
     * {@code RearViewMirrorView}），没有另外的内缩。宽 40%、高 36%，再大也收在屏里、四边留空。
     * 窗口本身从 (0,0) 起，卡片在窗口里挪到 (x, y)，见 {@link HudNoticeWindow}。</p>
     */
    public static int[] place(int displayW, int displayH) {
        if (displayW < 2 || displayH < 2) {
            return new int[]{0, 0, Math.max(displayW, 1), Math.max(displayH, 1)};
        }
        int x = Math.min(EDGE, displayW / 4);
        int y = Math.min(EDGE, displayH / 4);
        int width = Math.max(1, Math.min(displayW * 40 / 100, displayW - x * 2));
        int height = Math.max(1, Math.min(displayH * 36 / 100, displayH - y * 2));
        return new int[]{x, y, width, height};
    }

    private static boolean isBlank(CharSequence value) {
        return value == null || value.toString().trim().isEmpty();
    }
}
