package com.kooo.evcam.nav;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.hud.HudNotice;
import com.kooo.evcam.overlay.OverlayCoordinator;
import com.kooo.evcam.zeekr.HudDisplay;

/**
 * The next turn on the HUD while guiding: arrow, distance, and the road. Stays up and changes in
 * place (unlike {@link com.kooo.evcam.hud.HudNoticeWindow}'s cards, which come and go). Placed in
 * the part of the HUD the notice cards use, so Settings' left and right edges apply. Main thread.
 */
final class HudTurnWindow {

    private static final String TAG = "HudTurnWindow";
    private static final int ENERGY = 0xFFF0622D;

    private WindowManager wm;
    private FrameLayout root;
    private ManeuverIcon.ArrowView arrow;
    private TextView distance;
    private TextView road;
    private boolean failed;

    /** Shows (or updates) the card. Does nothing on a car without a HUD. */
    void show(Context context, Maneuver m, String toNext, String instruction) {
        if (root == null && !open(context)) {
            return;
        }
        arrow.set(m, Color.WHITE);
        distance.setText(toNext);
        road.setText(instruction);
    }

    void hide() {
        if (wm != null && root != null) {
            try {
                wm.removeView(root);
            } catch (RuntimeException ignored) {
                // gone with the display
            }
        }
        wm = null;
        root = null;
        failed = false;
    }

    private boolean open(Context context) {
        if (failed || Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !OverlayCoordinator.canShowOverlay(context)) {
            return false;
        }
        Display hud = HudDisplay.find(context);
        if (hud == null) {
            failed = true;
            return false;
        }
        try {
            Context window = context.getApplicationContext().createWindowContext(hud,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            WindowManager manager = window.getSystemService(WindowManager.class);
            Point size = HudDisplay.sizeOf(hud);
            AppConfig config = new AppConfig(context);
            int[] box = HudNotice.place(size.x, size.y,
                    config.getHudNoticeLeftPercent(), config.getHudNoticeRightPercent());
            int height = box[3];

            LinearLayout card = new LinearLayout(window);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            int pad = Math.max(4, height / 20);
            card.setPadding(pad * 2, pad, pad * 2, pad);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xD9101114);
            bg.setCornerRadius(height / 12f);
            bg.setStroke(Math.max(2, height / 60), ENERGY);
            card.setBackground(bg);

            arrow = new ManeuverIcon.ArrowView(window);
            int icon = Math.round(height * 0.7f);
            card.addView(arrow, new LinearLayout.LayoutParams(icon, icon));

            LinearLayout texts = new LinearLayout(window);
            texts.setOrientation(LinearLayout.VERTICAL);
            distance = text(window, height * 0.3f, Color.WHITE, true);
            road = text(window, height * 0.15f, 0xFFD0D2D6, false);
            road.setMaxLines(2);
            road.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(distance);
            texts.addView(road);
            LinearLayout.LayoutParams textsAt = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            textsAt.leftMargin = pad * 2;
            card.addView(texts, textsAt);

            FrameLayout frame = new FrameLayout(window);
            FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(box[2], FrameLayout.LayoutParams.WRAP_CONTENT);
            at.gravity = Gravity.TOP | Gravity.START;
            at.leftMargin = box[0];
            at.topMargin = box[1];
            frame.addView(card, at);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    box[0] + box[2], WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            manager.addView(frame, lp);
            wm = manager;
            root = frame;
            return true;
        } catch (RuntimeException e) {
            failed = true;
            AppLog.w(TAG, "no turn card on the HUD: " + e.getMessage());
            return false;
        }
    }

    private static TextView text(Context context, float px, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        view.setTextColor(color);
        view.setSingleLine(bold);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }
}
