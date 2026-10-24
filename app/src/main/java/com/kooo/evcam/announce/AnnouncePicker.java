package com.kooo.evcam.announce;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.kooo.evcam.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Floating announcement picker over whatever is on screen (the shortcut's job): sounds, then
 * phrases, three to a row. Tapping one plays it and closes the picker. Closes by itself after
 * {@link #IDLE_MS} untouched, or on a tap outside it. Main thread.
 */
public final class AnnouncePicker {

    private static final long IDLE_MS = 20_000L;
    private static final int PER_ROW = 3;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static View shown;
    private static final Runnable idle = AnnouncePicker::hide;

    private AnnouncePicker() {
    }

    public static void toggle(Context context) {
        MAIN.post(() -> {
            if (shown != null) {
                hide();
            } else {
                show(context.getApplicationContext());
            }
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private static void show(Context app) {
        if (!com.kooo.evcam.WakeUpHelper.hasOverlayPermission(app)) {
            com.kooo.evcam.ui.Notice.makeText(app, R.string.msg_need_overlay, Toast.LENGTH_SHORT).show();
            return;
        }
        Context themed = new ContextThemeWrapper(app, R.style.Theme_Cam);
        LayoutInflater inflater = LayoutInflater.from(themed);
        View root = inflater.inflate(R.layout.overlay_announce, null);
        root.findViewById(R.id.announce_close).setOnClickListener(v -> hide());
        root.findViewById(R.id.announce_stop).setOnClickListener(v -> {
            Announcer.stop();
            touched();
        });
        LinearLayout grid = root.findViewById(R.id.announce_grid);
        List<Announcement> sounds = new ArrayList<>();
        List<Announcement> phrases = new ArrayList<>();
        for (Announcement a : new AnnouncementStore(app).all()) {
            (a.isSound() ? sounds : phrases).add(a);
        }
        section(themed, inflater, grid, R.string.announce_sounds, sounds);
        section(themed, inflater, grid, R.string.announce_phrases, phrases);
        root.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                hide();
            } else {
                touched();
            }
            return false;
        });

        DisplayMetrics metrics = app.getResources().getDisplayMetrics();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                Math.min(metrics.widthPixels - dp(app, 48), dp(app, 900)),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;
        lp.dimAmount = 0.4f;
        // Long lists scroll instead of running off the screen
        View scroll = root.findViewById(R.id.announce_scroll);
        root.measure(View.MeasureSpec.makeMeasureSpec(lp.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int maxHeight = metrics.heightPixels - dp(app, 48);
        if (root.getMeasuredHeight() > maxHeight) {
            ViewGroup.LayoutParams sp = scroll.getLayoutParams();
            sp.height = maxHeight - (root.getMeasuredHeight() - scroll.getMeasuredHeight());
            scroll.setLayoutParams(sp);
        }
        try {
            app.getSystemService(WindowManager.class).addView(root, lp);
            shown = root;
            touched();
        } catch (RuntimeException e) {
            com.kooo.evcam.ui.Notice.makeText(app, R.string.msg_need_overlay, Toast.LENGTH_SHORT).show();
        }
    }

    private static void section(Context themed, LayoutInflater inflater, LinearLayout grid, int title,
                                List<Announcement> items) {
        if (items.isEmpty()) {
            return;
        }
        TextView header = new TextView(themed);
        header.setText(title);
        header.setTextAppearance(R.style.TextAppearance_Cam_Body);
        header.setTextColor(themed.getColor(R.color.text_secondary));
        header.setPadding(0, grid.getChildCount() == 0 ? 0 : dp(themed, 12), 0, dp(themed, 6));
        grid.addView(header);
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % PER_ROW == 0) {
                row = new LinearLayout(themed);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            Announcement a = items.get(i);
            Button button = (Button) inflater.inflate(R.layout.item_announce_button, row, false);
            button.setText(Labels.of(themed, a));
            button.setOnClickListener(v -> {
                Announcer.play(themed, a, "picker");
                hide();
            });
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) button.getLayoutParams();
            lp.setMarginEnd(i % PER_ROW == PER_ROW - 1 ? 0 : dp(themed, 8));
            lp.bottomMargin = dp(themed, 8);
            row.addView(button, lp);
        }
        // keep the last row's buttons the same width as the full rows
        for (int left = items.size() % PER_ROW; left > 0 && left < PER_ROW; left++) {
            View spacer = new View(themed);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1);
            lp.setMarginStart(dp(themed, 8));
            row.addView(spacer, lp);
        }
    }

    private static void touched() {
        MAIN.removeCallbacks(idle);
        MAIN.postDelayed(idle, IDLE_MS);
    }

    private static void hide() {
        MAIN.removeCallbacks(idle);
        View view = shown;
        shown = null;
        if (view != null) {
            try {
                view.getContext().getSystemService(WindowManager.class).removeView(view);
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
