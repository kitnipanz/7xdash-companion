package com.kooo.evcam.obd;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.kooo.evcam.R;

import java.util.List;
import java.util.Locale;

/**
 * A small floating window over any app while a capture runs: the ids whose steady bytes just moved
 * ({@link CanSniffer}), changed bytes in orange, and a Mark button to tap when the car alerts.
 * Drag it by its title. Main thread only. Goes away by itself when the capture ends.
 */
final class ObdSnifferWindow {

    private static final long TICK_MS = 250L;
    /** How long a moved byte stays listed. */
    private static final long SHOW_MS = 15_000L;
    private static final int ROWS = 8;
    private static final int WIDTH_DP = 460;
    private static final float DRAG_SLOP_PX = 6f;

    private static ObdSnifferWindow shown;
    private static int lastX = -1;
    private static int lastY = -1;

    private final Context app;
    private final ObdCapture capture;
    private final WindowManager windows;
    private final WindowManager.LayoutParams params;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinearLayout root;
    private final TextView title;
    private final TextView rows;
    private final TextView mark;
    private final int movedColor;
    private final int quietColor;
    private final Runnable tick = this::tick;

    /** False when the app may not draw over others yet. */
    static boolean show(Context context, ObdCapture capture) {
        if (shown != null && shown.capture == capture) {
            return true;
        }
        hide();
        Context app = context.getApplicationContext();
        if (!Settings.canDrawOverlays(app)) {
            return false;
        }
        shown = new ObdSnifferWindow(app, capture);
        shown.attach();
        return true;
    }

    static boolean isShown() {
        return shown != null;
    }

    static void hide() {
        if (shown != null) {
            shown.detach();
            shown = null;
        }
    }

    private ObdSnifferWindow(Context app, ObdCapture capture) {
        this.app = app;
        this.capture = capture;
        windows = app.getSystemService(WindowManager.class);
        movedColor = ContextCompat.getColor(app, R.color.energy_text);
        quietColor = ContextCompat.getColor(app, R.color.text_secondary);

        root = new LinearLayout(app);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(12));
        GradientDrawable card = new GradientDrawable();
        card.setColor(ContextCompat.getColor(app, R.color.surface));
        card.setCornerRadius(dp(16));
        card.setStroke(dp(1), ContextCompat.getColor(app, R.color.line));
        root.setBackground(card);

        LinearLayout header = new LinearLayout(app);
        header.setGravity(Gravity.CENTER_VERTICAL);
        title = label(R.style.TextAppearance_Cam_Caption, R.color.text_secondary);
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        mark = button(R.color.energy, R.color.on_energy);
        mark.setOnClickListener(v -> mark.setText(app.getString(R.string.obd_live_marked, capture.mark())));
        mark.setText(R.string.obd_live_mark);
        header.addView(mark);

        TextView close = button(R.color.sunken, R.color.text_primary);
        close.setText("✕");
        close.setContentDescription(app.getString(R.string.action_back));
        close.setOnClickListener(v -> hide());
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gap.setMarginStart(dp(8));
        header.addView(close, gap);
        root.addView(header);

        rows = label(R.style.TextAppearance_Cam_Body, R.color.text_primary);
        rows.setTypeface(Typeface.MONOSPACE);
        rows.setPadding(0, dp(8), 0, 0);
        rows.setMinLines(ROWS);
        rows.setMaxLines(ROWS);
        root.addView(rows);
        dragBy(header);

        params = new WindowManager.LayoutParams(dp(WIDTH_DP),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = lastX >= 0 ? lastX : dp(140);
        params.y = lastY >= 0 ? lastY : dp(90);
    }

    private void attach() {
        windows.addView(root, params);
        tick();
    }

    private void detach() {
        main.removeCallbacks(tick);
        lastX = params.x;
        lastY = params.y;
        windows.removeViewImmediate(root);
    }

    private void tick() {
        ObdCapture.Progress progress = capture.progress();
        if (progress.ended != ObdCapture.Ended.RUNNING) {
            hide();
            return;
        }
        CanSniffer sniffer = capture.sniffer();
        title.setText(app.getString(R.string.obd_live_title, sniffer.ids(), progress.frames,
                progress.protocol == null ? "—" : progress.protocol));
        render(sniffer.recent(capture.clock(), SHOW_MS, ROWS));
        main.postDelayed(tick, TICK_MS);
    }

    private void render(List<CanSniffer.Row> list) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        if (list.isEmpty()) {
            text.append(app.getString(R.string.obd_live_waiting));
            text.setSpan(new ForegroundColorSpan(quietColor), 0, text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        long now = capture.clock();
        for (CanSniffer.Row row : list) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(String.format(Locale.ROOT, "%-8s ", row.id));
            for (int i = 0; i < row.bytes.length; i++) {
                int start = text.length();
                text.append(row.bytes[i]);
                if (row.moved[i]) {
                    text.setSpan(new ForegroundColorSpan(movedColor), start, text.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                text.append(' ');
            }
            text.append(String.format(Locale.ROOT, "%ds", (now - row.eventAt) / 1000L));
        }
        rows.setText(text);
    }

    private TextView label(int appearance, int color) {
        TextView view = new TextView(app);
        view.setTextAppearance(appearance);
        view.setTextColor(ContextCompat.getColor(app, color));
        return view;
    }

    private TextView button(int fill, int color) {
        TextView view = label(R.style.TextAppearance_Cam_Body, color);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(48));
        view.setMinWidth(dp(48));
        view.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(ContextCompat.getColor(app, fill));
        shape.setCornerRadius(dp(24));
        view.setBackground(shape);
        return view;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void dragBy(View handle) {
        final float[] start = new float[4];
        handle.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start[0] = event.getRawX();
                    start[1] = event.getRawY();
                    start[2] = params.x;
                    start[3] = params.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - start[0];
                    float dy = event.getRawY() - start[1];
                    if (Math.abs(dx) + Math.abs(dy) > DRAG_SLOP_PX) {
                        params.x = Math.max(0, Math.round(start[2] + dx));
                        params.y = Math.max(0, Math.round(start[3] + dy));
                        windows.updateViewLayout(root, params);
                    }
                    return true;
                default:
                    return true;
            }
        });
    }

    private int dp(int value) {
        return Math.round(value * app.getResources().getDisplayMetrics().density);
    }
}
