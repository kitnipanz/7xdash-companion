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
 * capture, Super mirror and open 7xDash, and on the right what the floating button used to carry:
 * record, dim (tap on / off, slide for how dark) and quit (tap twice). Head units rarely allow real split screen for normal apps
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
    private static final String ACTION_RESET = "com.kooo.evcam.action.RESET_DASH_PANEL";
    /** Default and smallest height, in dp. */
    private static final int DEFAULT_HEIGHT_DP = 220;
    private static final int MIN_HEIGHT_DP = 90;
    private static final long TICK_MS = 500L;
    /** Buttons, in the order {@link DashPanelView#ICONS} draws them: the bottom row, then the column. */
    private static final ShortcutAction[] ACTIONS = {ShortcutAction.PHOTO_SURROUND, ShortcutAction.INSTANT_CAPTURE,
            ShortcutAction.MIRROR, ShortcutAction.APP, ShortcutAction.RECORD, ShortcutAction.DIM, ShortcutAction.QUIT};
    /** Quitting stops recording, so one stray tap only arms the button; a second tap within this quits. */
    private static final long QUIT_ARM_MS = 3_000L;

    private static volatile DashPanelService instance;

    /**
     * The dim was put on top: put the panel back above it, so its buttons (Dim among them) still
     * work at full dark. Waits while a finger is on the dim button: taking the window off would end the slide.
     */
    public static void raiseAboveShade() {
        DashPanelService svc = instance;
        if (svc != null) {
            svc.main.post(svc::raise);
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private DashPanelView view;
    private WindowManager.LayoutParams params;
    private AppConfig config;
    private FloatingWindowGestures gestures;
    private boolean blink;
    private boolean raiseWhenLifted;
    private boolean quitArmed;
    private final Runnable disarmQuit = () -> {
        quitArmed = false;
        refresh();
    };

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

    /** Size and place back to the default. Only moves the panel if it is up. */
    public static void resetPosition(Context context) {
        new AppConfig(context).setDashPanelBounds(0, 0, 0, 0);
        if (instance != null) {
            start(context, ACTION_RESET);
        }
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
        instance = this;
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
        } else if (ACTION_RESET.equals(action)) {
            if (view != null) {
                place();
                windowManager.updateViewLayout(view, params);
            }
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
        place();
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
        view.setOnTouchListener((v, event) -> dimTouch(event) || gestures.onTouch(event));
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

    /** Saved size and place, or the default (top right). Width follows the height. */
    private void place() {
        int[] saved = config.getDashPanelBounds();
        int height = saved[3] > 0 ? saved[3] : dp(DEFAULT_HEIGHT_DP);
        params.height = Math.max(dp(MIN_HEIGHT_DP), Math.min(height, screenHeight()));
        params.width = Math.min(Math.round(params.height * DashPanelView.ASPECT), screenWidth());
        params.x = saved[2] > 0 ? saved[0] : screenWidth() - params.width - dp(24);
        params.y = saved[2] > 0 ? saved[1] : dp(96);
    }

    private void press(int button) {
        if (button < 0 || button >= ACTIONS.length) {
            return;
        }
        if (button == DashPanelView.QUIT_BUTTON) {
            main.removeCallbacks(disarmQuit);
            if (!quitArmed) {
                quitArmed = true;
                refresh();
                com.kooo.evcam.ui.Notice.makeText(this, com.kooo.evcam.R.string.floating_quit_again,
                        android.widget.Toast.LENGTH_SHORT).show();
                main.postDelayed(disarmQuit, QUIT_ARM_MS);
                return;
            }
            quitArmed = false;
            AppLog.i(TAG, "Quit from the Dash panel");
        }
        ShortcutPerformer.perform(this, ACTIONS[button].key, "dash_panel", null);
        main.postDelayed(this::refresh, 300);
    }

    private void raise() {
        if (view == null || !view.isAttachedToWindow() || params == null) {
            return;
        }
        if (onDim) {
            raiseWhenLifted = true;
            return;
        }
        raiseWhenLifted = false;
        try {
            windowManager.removeViewImmediate(view);
            windowManager.addView(view, params);
        } catch (Exception e) {
            AppLog.w(TAG, "raise above the dim: " + e.getMessage());
        }
    }

    /** Finger went down on the dim button: it stays with it until lifted, the panel does not move. */
    private boolean onDim;
    private boolean dimSliding;
    private float dimDownX;
    private float dimDownY;
    private int dimOrigin;
    private int dimShown;
    private static final int DIM_MIN = 10;
    private static final int DIM_MAX = 100;

    /**
     * The dim button, like the moon on the old floating button: a tap turns the dim on or off,
     * sliding from it sets how dark (right or down darker, left or up lighter), turning it on if it was off.
     */
    private boolean dimTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                onDim = view != null && view.buttonAt(event) == DashPanelView.DIM_BUTTON;
                if (onDim) {
                    dimSliding = false;
                    dimDownX = event.getRawX();
                    dimDownY = event.getRawY();
                    dimOrigin = config.getDimOpacity();
                    dimShown = -1;
                }
                return onDim;
            case MotionEvent.ACTION_MOVE:
                if (!onDim) {
                    return false;
                }
                float dx = event.getRawX() - dimDownX;
                float dy = event.getRawY() - dimDownY;
                if (dimSliding || Math.abs(dx) > dp(10) || Math.abs(dy) > dp(10)) {
                    dimSliding = true;
                    setDim(dimOrigin + Math.round((dx + dy) / Math.max(1, dp(6))));
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!onDim) {
                    return false;
                }
                onDim = false;
                if (!dimSliding) {
                    press(DashPanelView.DIM_BUTTON);
                }
                if (raiseWhenLifted) {
                    main.post(this::raise);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                boolean was = onDim;
                onDim = false;
                return was;
            default:
                return onDim;
        }
    }

    private void setDim(int level) {
        level = Math.max(DIM_MIN, Math.min(DIM_MAX, level));
        if (level == dimShown) {
            return;
        }
        dimShown = level;
        if (!config.isDimOverlayEnabled() && !OverlayCoordinator.setDimOverlayEnabled(this, true)) {
            return;
        }
        config.setDimOpacity(level);
        DimOverlayService.apply(this);
        refresh();
    }

    @Override
    public void onReadingsChanged(Readings readings) {
        refresh();
    }

    private void refresh() {
        if (view == null) {
            return;
        }
        // the trip from Navigation, or from the place shared from the iPhone
        com.kooo.evcam.nav.TripLine trip = com.kooo.evcam.nav.TripLine.now(this);
        view.arrival(trip == null ? null : trip.text, trip == null ? 0xFFFFFFFF : trip.color(
                0xFFFFFFFF, androidx.core.content.ContextCompat.getColor(this, com.kooo.evcam.R.color.energy),
                androidx.core.content.ContextCompat.getColor(this, com.kooo.evcam.R.color.recording)));
        view.controls(config.isDimOverlayEnabled(), quitArmed);
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
        instance = null;
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
