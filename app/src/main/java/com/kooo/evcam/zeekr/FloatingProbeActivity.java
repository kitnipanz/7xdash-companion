package com.kooo.evcam.zeekr;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;

/**
 * Screen probe test: can 7xDash put something on the main screen without the overlay permission?
 * Not an overlay window but a small floating activity in its own task, laid over whatever is open.
 * Taps outside it go to the app underneath. Android may refuse to start it while 7xDash is in the
 * background (background activity starts); {@link #launchLater} records which way it went.
 */
public class FloatingProbeActivity extends Activity implements com.kooo.evcam.home.SideRail.Bare {

    private static final String TAG = "FloatingProbe";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Time to switch to another app before the test window tries to open. */
    public static final long DELAY_MS = 5_000L;
    private static final long SHOW_MS = 10_000L;
    /** No window this long after asking: Android refused. */
    private static final long BLOCKED_AFTER_MS = 4_000L;

    /** Result of the last try, for the probe row. Empty before the first. */
    public enum Result { NONE, WAITING, OPENED_BACKGROUND, OPENED_FOREGROUND, BLOCKED, FAILED }

    private static volatile Result result = Result.NONE;
    private static volatile String detail = "";
    private static volatile long askedAt;
    private static volatile boolean askedFromBackground;
    private static Runnable onChange;

    public static Result result() {
        return result;
    }

    public static String detail() {
        return detail;
    }

    /** The probe page wants to redraw when the result changes. Main thread. */
    public static void setOnChange(Runnable r) {
        onChange = r;
    }

    /** Waits {@link #DELAY_MS}, then tries to open the test window. Main thread. */
    public static void launchLater(Context context) {
        Context app = context.getApplicationContext();
        result = Result.WAITING;
        detail = "";
        changed();
        MAIN.postDelayed(() -> launch(app), DELAY_MS);
    }

    private static void launch(Context app) {
        askedFromBackground = !inForeground();
        askedAt = SystemClock.elapsedRealtime();
        Intent intent = new Intent(app, FloatingProbeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            app.startActivity(intent);
            AppLog.i(TAG, "asked to open, from " + (askedFromBackground ? "background" : "foreground"));
        } catch (RuntimeException e) {
            result = Result.FAILED;
            detail = e.getClass().getSimpleName() + ": " + e.getMessage();
            AppLog.w(TAG, "start failed: " + detail);
            changed();
            return;
        }
        long mine = askedAt;
        MAIN.postDelayed(() -> {
            if (result == Result.WAITING && askedAt == mine) {
                result = Result.BLOCKED;
                detail = askedFromBackground ? "from background" : "from foreground";
                AppLog.w(TAG, "no window after " + BLOCKED_AFTER_MS + " ms: Android blocked it (" + detail + ")");
                changed();
            }
        }, BLOCKED_AFTER_MS);
    }

    private static boolean inForeground() {
        ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(info);
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }

    private static void changed() {
        Runnable r = onChange;
        if (r != null) {
            MAIN.post(r);
        }
    }

    private final Runnable close = this::finish;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        long ms = SystemClock.elapsedRealtime() - askedAt;
        result = askedFromBackground ? Result.OPENED_BACKGROUND : Result.OPENED_FOREGROUND;
        detail = ms + " ms";
        AppLog.i(TAG, "opened " + result + " after " + ms + " ms");
        changed();

        float d = getResources().getDisplayMetrics().density;
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        // a floating activity closes on a touch outside and eats that touch: keep it, let the touch through
        setFinishOnTouchOutside(false);
        // taps outside the card go to the app underneath; keys stay with it too
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * d);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF0101114);
        bg.setCornerRadius(18 * d);
        bg.setStroke(Math.round(3 * d), 0xFFF0622D);
        card.setBackground(bg);
        card.addView(text(getString(R.string.floating_probe_title), 22, Color.WHITE, true));
        card.addView(text(getString(R.string.floating_probe_body), 16, 0xFFD0D2D6, false));
        card.setOnClickListener(v -> finish());
        setContentView(card);
        // size after the content: setContentView would put the window back to full size
        window.setLayout(Math.round(560 * d), WindowManager.LayoutParams.WRAP_CONTENT);
        window.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.y = Math.round(80 * d);
        window.setAttributes(lp);
        MAIN.postDelayed(close, SHOW_MS);
    }

    @Override
    protected void onDestroy() {
        MAIN.removeCallbacks(close);
        super.onDestroy();
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }
}
