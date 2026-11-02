package com.kooo.evcam.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Telemetry;
import com.kooo.evcam.telemetry.VehicleState;

/**
 * Red screen edge for a car in the blind spot, like Tesla: while the car warns about a side
 * ({@link VehicleState#blindSpotWarning}, read by {@code telemetry.BlindSpotWatch}), that edge of
 * the screen blinks red over any app. Touches go straight through. On by default
 * ({@link AppConfig#isBlindSpotEdgeEnabled}); needs the show-over-other-apps permission.
 */
public final class BlindSpotEdge implements Telemetry.Listener {

    private static final String TAG = "BlindSpotEdge";
    /** Width of the glow, in dp. */
    private static final int WIDTH_DP = 56;
    /** Half a blink: on this long, then off this long. */
    private static final long BLINK_MS = 350L;
    /** How long Show a test blink lasts. */
    private static final long TEST_MS = 3_000L;
    private static final int RED = 0xFFFF2A2A;

    private static BlindSpotEdge installed;

    private final Context app;
    private final AppConfig config;
    private final WindowManager windowManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private EdgeView left;
    private EdgeView right;
    private int shown;
    private int testSides;
    private boolean blinkOn = true;

    private final Runnable blink = new Runnable() {
        @Override
        public void run() {
            blinkOn = !blinkOn;
            float alpha = blinkOn ? 1f : 0.15f;
            if (left != null) {
                left.setAlpha(alpha);
            }
            if (right != null) {
                right.setAlpha(alpha);
            }
            main.postDelayed(this, BLINK_MS);
        }
    };

    private BlindSpotEdge(Context context) {
        app = context.getApplicationContext();
        config = new AppConfig(app);
        windowManager = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
    }

    /** Application start. Repeat calls do nothing. */
    public static void install(Context context) {
        if (installed != null) {
            return;
        }
        installed = new BlindSpotEdge(context);
        Telemetry.get().addListener(installed);
    }

    /** The switch changed: show or hide to match. */
    public static void refresh() {
        if (installed != null) {
            installed.main.post(() -> installed.update());
        }
    }

    /** Settings → Show a test blink: both edges blink for a few seconds, whatever the car says. */
    public static void test(Context context) {
        install(context);
        BlindSpotEdge e = installed;
        e.main.post(() -> {
            e.testSides = VehicleState.TURN_LEFT | VehicleState.TURN_RIGHT;
            e.update();
            e.main.postDelayed(() -> {
                e.testSides = 0;
                e.update();
            }, TEST_MS);
        });
    }

    @Override
    public void onReadingsChanged(Readings readings) {
        update();
    }

    private void update() {
        VehicleState s = Telemetry.get().latest();
        int warned = s == null || s.blindSpotWarning == null ? 0 : s.blindSpotWarning;
        int want = (config.isBlindSpotEdgeEnabled() ? warned : 0) | testSides;
        if (want != 0 && !OverlayCoordinator.canShowOverlay(app)) {
            want = 0;
        }
        if (want == shown) {
            return;
        }
        left = side(left, (want & VehicleState.TURN_LEFT) != 0, true);
        right = side(right, (want & VehicleState.TURN_RIGHT) != 0, false);
        main.removeCallbacks(blink);
        if (want != 0) {
            blinkOn = false;
            main.post(blink);
        }
        if (want != 0 && shown == 0) {
            AppLog.i(TAG, "red edge on: " + (want == 3 ? "both" : want == VehicleState.TURN_LEFT ? "left" : "right"));
        }
        shown = want;
    }

    /** Adds or removes one edge's window; returns what is now showing on that side. */
    private EdgeView side(EdgeView current, boolean on, boolean isLeft) {
        if (on && current == null) {
            EdgeView v = new EdgeView(app, isLeft);
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    Math.round(WIDTH_DP * app.getResources().getDisplayMetrics().density),
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            p.gravity = (isLeft ? Gravity.START : Gravity.END) | Gravity.TOP;
            try {
                windowManager.addView(v, p);
                return v;
            } catch (Exception e) {
                AppLog.e(TAG, "add edge failed", e);
                return null;
            }
        }
        if (!on && current != null) {
            try {
                windowManager.removeView(current);
            } catch (Exception ignored) {
            }
            return null;
        }
        return current;
    }

    /** A red glow, strongest at the screen edge and fading inward. */
    private static final class EdgeView extends View {
        private final Paint glow = new Paint();
        private final Paint line = new Paint();
        private final boolean isLeft;

        EdgeView(Context context, boolean isLeft) {
            super(context);
            this.isLeft = isLeft;
            line.setColor(RED);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            int edge = 0xCCFF2A2A;
            int clear = 0x00FF2A2A;
            glow.setShader(new LinearGradient(isLeft ? 0 : w, 0, isLeft ? w : 0, 0, edge, clear, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            canvas.drawRect(0, 0, w, h, glow);
            float bar = w * 0.12f;
            canvas.drawRect(isLeft ? 0 : w - bar, 0, isLeft ? bar : w, h, line);
        }
    }
}
