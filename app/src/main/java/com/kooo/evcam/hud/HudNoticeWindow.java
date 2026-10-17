package com.kooo.evcam.hud;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.overlay.OverlayCoordinator;
import com.kooo.evcam.zeekr.HudDisplay;

/**
 * 抬头显示上的一张通知卡片。同一时刻只有一张：新的来了替掉旧的，{@link HudNotice#SHOW_MS} 后收起。
 * 窗口建法和侧视弹窗一样（窗口上下文挂到那块屏上）。
 */
public final class HudNoticeWindow {

    private static final String TAG = "HudNoticeWindow";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** 极氪橙，和 colors.xml 里的 energy 一致。抬头显示上不跟昼夜走。 */
    private static final int ENERGY = 0xFFF0622D;

    private static WindowManager shownIn;
    private static View shown;
    private static final Runnable HIDE = HudNoticeWindow::hide;

    private HudNoticeWindow() {
    }

    public static void show(Context context, String app, String title, String text) {
        Context appContext = context.getApplicationContext();
        MAIN.post(() -> showNow(appContext, app, title, text));
    }

    private static void showNow(Context context, String app, String title, String text) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !OverlayCoordinator.canShowOverlay(context)) {
            return;
        }
        Display hud = HudDisplay.find(context);
        if (hud == null) {
            return;
        }
        hide();
        try {
            Context window = context.createWindowContext(hud,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            WindowManager wm = window.getSystemService(WindowManager.class);
            Point size = HudDisplay.sizeOf(hud);
            int[] box = HudNotice.place(size.x, size.y,
                    new com.kooo.evcam.AppConfig(context).getHudNoticeLeftPercent());
            // 窗口从 (0,0) 起，卡片在窗口里挪到 (x, y)。和侧视一样（SideViewPopupView.place）：
            // 窗口若不从原点起，这台抬头显示画出来的位置会错开，卡片就跑出看得见的那一块。
            // 不加 FLAG_LAYOUT_NO_LIMITS：窗口要被按在屏里，不许探出边。
            FrameLayout root = new FrameLayout(window);
            FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(box[2], box[3]);
            at.gravity = Gravity.TOP | Gravity.START;
            at.leftMargin = box[0];
            at.topMargin = box[1];
            root.addView(card(window, box[3], app, title, text), at);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    box[0] + box[2], box[1] + box[3],
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = 0;
            lp.y = 0;
            wm.addView(root, lp);
            shownIn = wm;
            shown = root;
            MAIN.postDelayed(HIDE, HudNotice.SHOW_MS);
        } catch (RuntimeException e) {
            AppLog.w(TAG, "抬头显示通知上不去: " + e.getMessage());
        }
    }

    private static void hide() {
        MAIN.removeCallbacks(HIDE);
        if (shownIn != null && shown != null) {
            try {
                shownIn.removeView(shown);
            } catch (RuntimeException ignored) {
                // 窗口已经跟着屏一起没了
            }
        }
        shownIn = null;
        shown = null;
    }

    /** 字号按卡片高度算：抬头显示的密度各车不同，按 dp 排会忽大忽小。 */
    private static View card(Context context, int height, String app, String title, String text) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        int pad = height / 8;
        card.setPadding(pad * 2, pad, pad * 2, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xD9101114);
        bg.setCornerRadius(height / 6f);
        bg.setStroke(Math.max(2, height / 60), ENERGY);
        card.setBackground(bg);
        // 不写应用名：抬头显示上地方小，橙色描边已经说明是 7xDash 的
        if (!TextUtils.isEmpty(title)) {
            card.addView(line(context, title, height * 0.22f, Color.WHITE, true));
        }
        if (!TextUtils.isEmpty(text)) {
            card.addView(line(context, text, height * 0.16f, 0xFFD0D2D6, false));
        }
        return card;
    }

    private static TextView line(Context context, String value, float px, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        view.setTextColor(color);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setGravity(Gravity.START);
        view.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }
}
