package com.kooo.evcam.ui;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.kooo.evcam.hud.HudNotice;

/**
 * 7xDash 的提示：每一页、每个服务弹的那一句都走这里，长得都一样 ——
 * 和抬头显示上那张卡片同一个样子（近黑底、极氪橙描边、上面一行小字「7xDash」、下面是那句话）。
 * 开着「抬头显示通知」时同一句也上抬头显示（{@link HudNotice}）。
 *
 * <p>用法和 {@link Toast} 一样：{@code Notice.makeText(context, R.string.x, Toast.LENGTH_SHORT).show()}，
 * 原来的调用把 {@code Toast} 换成 {@code Notice} 就行。</p>
 *
 * <p>卡片是一个不抢焦点、不挡触摸的悬浮窗，贴在中控屏下方居中；同一时刻只有一张，新的替掉旧的。
 * 没有悬浮窗权限时退回系统的 Toast。字一律从 Application 的资源取（已按应用语言换过，见
 * {@code Languages.applyToApp}），服务里弹的也是界面上那种语言。</p>
 */
public final class Notice {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int ENERGY = 0xFFF0622D;
    private static final long SHORT_MS = 2_500L;
    private static final long LONG_MS = 4_500L;

    private static WindowManager shownIn;
    private static View shown;
    private static final Runnable HIDE = Notice::hide;

    private final Context context;
    private final CharSequence text;
    private final int duration;

    private Notice(Context context, CharSequence text, int duration) {
        this.context = context.getApplicationContext();
        this.text = text;
        this.duration = duration;
    }

    public static Notice makeText(Context context, CharSequence text, int duration) {
        return new Notice(context, text, duration);
    }

    public static Notice makeText(Context context, int resId, int duration) {
        return new Notice(context, context.getApplicationContext().getString(resId), duration);
    }

    public void show() {
        if (TextUtils.isEmpty(text)) {
            return;
        }
        HudNotice.post(context, text);
        MAIN.post(() -> showNow(context, text.toString(), duration == Toast.LENGTH_LONG ? LONG_MS : SHORT_MS));
    }

    private static void showNow(Context context, String text, long ms) {
        if (!Settings.canDrawOverlays(context)) {
            Toast.makeText(context, text, ms > SHORT_MS ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            return;
        }
        hide();
        try {
            WindowManager wm = context.getSystemService(WindowManager.class);
            View card = card(context, text);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.y = dp(context, 72);
            lp.windowAnimations = android.R.style.Animation_Toast;
            wm.addView(card, lp);
            shownIn = wm;
            shown = card;
            MAIN.postDelayed(HIDE, ms);
        } catch (RuntimeException e) {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
        }
    }

    private static void hide() {
        MAIN.removeCallbacks(HIDE);
        if (shownIn != null && shown != null) {
            try {
                shownIn.removeView(shown);
            } catch (RuntimeException ignored) {
                // 窗口已经没了
            }
        }
        shownIn = null;
        shown = null;
    }

    private static View card(Context context, String text) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        int padH = dp(context, 28);
        int padV = dp(context, 16);
        card.setPadding(padH, padV, padH, padV);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2101114);
        bg.setCornerRadius(dp(context, 20));
        bg.setStroke(dp(context, 2), ENERGY);
        card.setBackground(bg);
        card.setMinimumWidth(dp(context, 360));

        TextView label = new TextView(context);
        label.setText(com.kooo.evcam.R.string.app_name);
        label.setTextColor(ENERGY);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        card.addView(label);

        TextView body = new TextView(context);
        body.setText(text);
        body.setTextColor(0xFFF0F1F2);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        body.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        body.setMaxWidth(context.getResources().getDisplayMetrics().widthPixels / 2);
        body.setPadding(0, dp(context, 4), 0, 0);
        card.addView(body);
        return card;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
