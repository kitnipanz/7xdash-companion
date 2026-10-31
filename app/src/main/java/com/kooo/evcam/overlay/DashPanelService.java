package com.kooo.evcam.overlay;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.UserExit;
import com.kooo.evcam.input.ShortcutAction;
import com.kooo.evcam.input.ShortcutPerformer;
import com.kooo.evcam.recording.RecordingCoordinator;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Telemetry;
import com.kooo.evcam.zeekr.FloatingWindowGestures;

/**
 * Dash panel: a small floating card over any app (maps, music, AirPlay) with speed, gear, turn
 * signals, the clock and whether 7xDash is recording, plus four buttons: surround photo, instant
 * capture, Super mirror and open 7xDash. Head units rarely allow real split screen for normal apps
 * ({@link com.kooo.evcam.screen.MultiWindowProbe}); this is the part of 7xDash that can sit next to
 * another app anyway.
 *
 * <p>Moves, pinches and docks like Super mirror ({@link FloatingWindowGestures}).</p>
 */
public class DashPanelService extends Service implements Telemetry.Listener {

    private static final String TAG = "DashPanel";
    private static final String TELEMETRY_USER = "dash-panel";
    private static final String ACTION_SHOW = "com.kooo.evcam.action.SHOW_DASH_PANEL";
    private static final String ACTION_HIDE = "com.kooo.evcam.action.HIDE_DASH_PANEL";
    /** Default and smallest height, in dp. */
    private static final int DEFAULT_HEIGHT_DP = 220;
    private static final int MIN_HEIGHT_DP = 90;
    private static final long TICK_MS = 500L;
    /** Buttons, in the order {@link DashPanelView#ICONS} draws them. */
    private static final ShortcutAction[] ACTIONS = {ShortcutAction.PHOTO_SURROUND, ShortcutAction.INSTANT_CAPTURE,
            ShortcutAction.MIRROR, ShortcutAction.APP};

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private DashPanelView view;
    private WindowManager.LayoutParams params;
    private AppConfig config;
    private FloatingWindowGestures gestures;
    private boolean blink;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            blink = !blink;
            refresh();
            main.postDelayed(this, TICK_MS);
        }
    };

    public static void show(Context context) {
        start(context, ACTION_SHOW);
    }

    public static void hide(Context context) {
        start(context, ACTION_HIDE);
    }

    private static void start(Context context, String action) {
        try {
            context.startService(new Intent(context, DashPanelService.class).setAction(action));
        } catch (Exception e) {
            AppLog.e(TAG, "start failed", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        config = new AppConfig(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (UserExit.blocks(this, TAG)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent == null ? ACTION_SHOW : intent.getAction();
        if (ACTION_HIDE.equals(action)) {
            stopSelf();
        } else {
            attach();
        }
        return START_NOT_STICKY;
    }

    private void attach() {
        if (view != null) {
            return;
        }
        view = new DashPanelView(this);
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        int[] saved = config.getDashPanelBounds();
        int height = saved[3] > 0 ? saved[3] : dp(DEFAULT_HEIGHT_DP);
        params.height = Math.max(dp(MIN_HEIGHT_DP), Math.min(height, screenHeight()));
        params.width = Math.round(params.height * DashPanelView.ASPECT);
        params.x = saved[2] > 0 ? saved[0] : screenWidth() - params.width - dp(24);
        params.y = saved[2] > 0 ? saved[1] : dp(96);
        gestures = new FloatingWindowGestures(this, new FloatingWindowGestures.Host() {
            @Override
            public WindowManager.LayoutParams params() {
                return params;
            }

            @Override
            public int screenWidth() {
                return DashPanelService.this.screenWidth();
            }

            @Override
            public int screenHeight() {
                return DashPanelService.this.screenHeight();
            }

            @Override
            public int minSize() {
                return dp(MIN_HEIGHT_DP);
            }

            @Override
            public void apply() {
                if (view != null && view.isAttachedToWindow()) {
                    windowManager.updateViewLayout(view, params);
                }
            }

            @Override
            public void save() {
                config.setDashPanelBounds(params.x, params.y, params.width, params.height);
            }

            @Override
            public void onTap(MotionEvent up) {
                press(view.buttonAt(up));
            }
        });
        view.setOnTouchListener((v, event) -> gestures.onTouch(event));
        try {
            windowManager.addView(view, params);
        } catch (Exception e) {
            AppLog.e(TAG, "add window failed", e);
            view = null;
            stopSelf();
            return;
        }
        Telemetry.get().acquire(this, TELEMETRY_USER);
        Telemetry.get().addListener(this);
        main.post(tick);
        AppLog.i(TAG, "shown");
    }

    private void press(int button) {
        if (button < 0 || button >= ACTIONS.length) {
            return;
        }
        ShortcutPerformer.perform(this, ACTIONS[button].key, "dash_panel", null);
        main.postDelayed(this::refresh, 300);
    }

    @Override
    public void onReadingsChanged(Readings readings) {
        refresh();
    }

    private void refresh() {
        if (view == null) {
            return;
        }
        com.kooo.evcam.trip.Trip.Status trip = com.kooo.evcam.trip.Trip.get().status();
        view.arrival(com.kooo.evcam.trip.ArrivalText.line(this, trip), com.kooo.evcam.trip.ArrivalText.color(trip,
                0xFFFFFFFF, androidx.core.content.ContextCompat.getColor(this, com.kooo.evcam.R.color.energy),
                androidx.core.content.ContextCompat.getColor(this, com.kooo.evcam.R.color.recording)));
        view.show(Telemetry.get().latest(),
                RecordingCoordinator.get(this).isRecordingOrResuming(), blink);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int screenWidth() {
        DisplayMetrics m = getResources().getDisplayMetrics();
        return m.widthPixels;
    }

    private int screenHeight() {
        DisplayMetrics m = getResources().getDisplayMetrics();
        return m.heightPixels;
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        Telemetry.get().removeListener(this);
        Telemetry.get().release(TELEMETRY_USER);
        if (view != null) {
            try {
                windowManager.removeView(view);
            } catch (Exception ignored) {
            }
            view = null;
        }
        AppLog.i(TAG, "hidden");
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
