package com.kooo.evcam.nav;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.kooo.evcam.R;

/**
 * The navigation settings in their own window over any app, opened from the floating map's bar, so
 * they stay in reach while the car's own screens are up. Drag the bar to move it. Main thread.
 */
final class NavFloatSettings {

    private static final int WIDTH_DP = 460;
    private static final int HEIGHT_DP = 640;
    private static final int MARGIN_DP = 12;

    private final Context context;
    private final WindowManager wm;
    private final NavSettings.Host host;
    private final float density;
    private View root;
    private WindowManager.LayoutParams lp;

    NavFloatSettings(Context context, NavSettings.Host host) {
        this.context = context;
        this.host = host;
        wm = context.getSystemService(WindowManager.class);
        density = context.getResources().getDisplayMetrics().density;
    }

    boolean isShown() {
        return root != null;
    }

    /** Opens beside the map at {@code mapX, mapY, mapW} on a screen of {@code screenW} by {@code screenH}. */
    void show(int mapX, int mapY, int mapW, int screenW, int screenH) {
        if (root != null) {
            return;
        }
        int margin = Math.round(MARGIN_DP * density);
        int w = Math.min(Math.round(WIDTH_DP * density), screenW - 2 * margin);
        int h = Math.min(Math.round(HEIGHT_DP * density), screenH - 2 * margin);
        View box = box();
        lp = new WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = place(mapX, mapW, w, screenW, margin);
        lp.y = Math.max(margin, Math.min(mapY, screenH - h - margin));
        try {
            wm.addView(box, lp);
            root = box;
        } catch (RuntimeException e) {
            com.kooo.evcam.AppLog.w("NavFloatSettings", "refused: " + e.getMessage());
        }
    }

    void hide() {
        if (root != null) {
            try {
                wm.removeView(root);
            } catch (RuntimeException ignored) {
                // already gone with the display
            }
            root = null;
        }
    }

    /** Beside the map: on its left when that fits, else on its right, else as far over as it goes. */
    static int place(int mapX, int mapW, int w, int screenW, int margin) {
        int left = mapX - w - margin;
        if (left >= margin) {
            return left;
        }
        return Math.max(margin, Math.min(mapX + mapW + margin, screenW - w - margin));
    }

    @SuppressLint("ClickableViewAccessibility")
    private View box() {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ContextCompat.getColor(context, R.color.surface));
        bg.setCornerRadius(18 * density);
        bg.setStroke(Math.round(2 * density), ContextCompat.getColor(context, R.color.line));
        box.setBackground(bg);
        box.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 18 * density);
            }
        });
        box.setClipToOutline(true);

        LinearLayout bar = new LinearLayout(context);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xE6101114);
        bar.setPadding(Math.round(16 * density), 0, Math.round(6 * density), 0);
        TextView title = new TextView(context);
        title.setText(R.string.nav_settings_title);
        title.setTextAppearance(R.style.TextAppearance_Cam_Row);
        title.setTextColor(Color.WHITE);
        title.setSingleLine(true);
        bar.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        ImageView close = new ImageView(context);
        close.setImageResource(R.drawable.ic_close);
        close.setColorFilter(Color.WHITE);
        close.setContentDescription(context.getString(R.string.nav_hud_adjust_done));
        int pad = Math.round(12 * density);
        close.setPadding(pad, pad, pad, pad);
        close.setOnClickListener(v -> hide());
        int b = Math.round(48 * density);
        bar.addView(close, new LinearLayout.LayoutParams(b, b));
        bar.setOnTouchListener(new View.OnTouchListener() {
            private float rawX;
            private float rawY;
            private int startX;
            private int startY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    rawX = e.getRawX();
                    rawY = e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE && root != null) {
                    lp.x = startX + Math.round(e.getRawX() - rawX);
                    lp.y = startY + Math.round(e.getRawY() - rawY);
                    wm.updateViewLayout(root, lp);
                }
                return true;
            }
        });
        box.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Math.round(56 * density)));
        box.addView(NavSettings.build(context, host), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        return box;
    }
}
