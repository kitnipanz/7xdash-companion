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
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Telemetry;
import com.kooo.evcam.zeekr.FloatingWindowGestures;

/**
 * Gauges only: the three dials ({@link DashGaugesView}) floating on their own over any app, for
 * when the Dash panel is off. Moves, pinches and docks like the Dash panel; a tap on a gauge opens
 * the screen to choose what it shows. The Macro pad hangs under it like it does under the panel.
 * With the panel on, the gauges live under the panel instead ({@link DashPanelService}).
 */
public class GaugesService extends Service implements Telemetry.Listener {

    private static final String TAG = "Gauges";
    private static final String TELEMETRY_USER = "gauges";
    private static final String ACTION_SHOW = "com.kooo.evcam.action.SHOW_GAUGES";
    private static final String ACTION_HIDE = "com.kooo.evcam.action.HIDE_GAUGES";
    private static final String ACTION_RELOAD = "com.kooo.evcam.action.RELOAD_GAUGES";
    /** Default and smallest height, in dp. */
    private static final int DEFAULT_HEIGHT_DP = 200;
    private static final int MIN_HEIGHT_DP = 90;
    private static final long TICK_MS = 500L;
    /** The bottom row, as on the Dash panel: surround photo, instant capture, Super mirror, open 7xDash. */
    private static final ShortcutAction[] ROW_ACTIONS = {ShortcutAction.PHOTO_SURROUND,
            ShortcutAction.INSTANT_CAPTURE, ShortcutAction.MIRROR, ShortcutAction.APP};

    private static volatile GaugesService instance;

    /** The window {x, y, width, height} in pixels, or null when it is not up. Main thread. */
    @Nullable
    static int[] windowBounds() {
        GaugesService svc = instance;
        if (svc == null || svc.view == null || svc.view.getParent() == null || svc.params == null) {
            return null;
        }
        return new int[]{svc.params.x, svc.params.y, svc.params.width, svc.params.height};
    }

    /** The dim went on top: put the gauges back above it, so the dim button still works at full dark. */
    public static void raiseAboveShade() {
        GaugesService svc = instance;
        if (svc != null) {
            svc.main.post(() -> {
                if (svc.view == null || svc.view.getParent() == null) {
                    return;
                }
                try {
                    svc.windowManager.removeViewImmediate(svc.view);
                    svc.windowManager.addView(svc.view, svc.params);
                } catch (Exception e) {
                    AppLog.w(TAG, "raise above the dim: " + e.getMessage());
                }
            });
        }
    }

    public static void show(Context context) {
        start(context, ACTION_SHOW);
    }

    public static void hide(Context context) {
        start(context, ACTION_HIDE);
    }

    /** A gauge changed: redraw if up. Does not show it. */
    static void reload() {
        GaugesService svc = instance;
        if (svc != null) {
            svc.main.post(svc::loadGauges);
        }
    }

    private static void start(Context context, String action) {
        try {
            context.startService(new Intent(context, GaugesService.class).setAction(action));
        } catch (Exception e) {
            AppLog.e(TAG, "start failed", e);
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final GaugeFeed feed = new GaugeFeed();
    private WindowManager windowManager;
    private DashGaugesView view;
    private WindowManager.LayoutParams params;
    private AppConfig config;
    private DashGauge[] gauges = DashGauge.parse("");
    /** Folded to the card of buttons. */
    private boolean minimized;
    /** Height of the window; the width follows from it and {@link #minimized}. */
    private int height;
    private boolean quitArmed;
    /** Quitting stops recording, so one stray tap only arms the button; a second tap within this quits. */
    private static final long QUIT_ARM_MS = 3_000L;
    private final Runnable disarmQuit = () -> {
        quitArmed = false;
        refresh();
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            main.postDelayed(this, TICK_MS);
        }
    };

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
        } else {
            attach();
            loadGauges();
        }
        return START_NOT_STICKY;
    }

    private void loadGauges() {
        gauges = DashGauge.parse(config.getDashGaugeSlots());
        if (view != null) {
            view.setGauges(gauges);
            refresh();
        }
    }

    private void attach() {
        if (view != null) {
            return;
        }
        view = new DashGaugesView(this);
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        int[] saved = config.getDashGaugesBounds();
        int saveH = saved[3] > 0 ? saved[3] : dp(DEFAULT_HEIGHT_DP);
        height = Math.max(dp(MIN_HEIGHT_DP), Math.min(saveH, screenHeight()));
        params.height = height;
        params.width = widthFor(height);
        view.setButtons(true, false);
        params.x = saved[2] > 0 ? saved[0] : screenWidth() - params.width - dp(24);
        params.y = saved[2] > 0 ? saved[1] : dp(96);
        FloatingWindowGestures gestures = new FloatingWindowGestures(this, new FloatingWindowGestures.Host() {
            @Override
            public WindowManager.LayoutParams params() {
                return params;
            }

            @Override
            public int screenWidth() {
                return GaugesService.this.screenWidth();
            }

            @Override
            public int screenHeight() {
                return GaugesService.this.screenHeight();
            }

            @Override
            public int minSize() {
                return dp(MIN_HEIGHT_DP);
            }

            @Override
            public void apply() {
                if (view != null && view.isAttachedToWindow()) {
                    // a pinch sets the height; the width follows
                    height = params.height;
                    params.width = widthFor(height);
                    windowManager.updateViewLayout(view, params);
                    OverlayCoordinator.dashPanelMoved();
                }
            }

            @Override
            public void save() {
                config.setDashGaugesBounds(params.x, params.y, Math.round(height * DashGaugesView.ASPECT_WITH_BUTTONS), height);
            }

            @Override
            public void onTap(MotionEvent up) {
                int button = view.buttonAt(up);
                if (button >= 0) {
                    press(button);
                    return;
                }
                int slot = view.gaugeAt(up);
                if (slot < 0) {
                    return;
                }
                view.flash(slot);
                try {
                    startActivity(new Intent(GaugesService.this, DashGaugesActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra(DashGaugesActivity.EXTRA_SLOT, slot));
                } catch (Exception e) {
                    AppLog.w(TAG, "open gauges: " + e.getMessage());
                }
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
        OverlayCoordinator.dashPanelMoved();
        Telemetry.get().acquire(this, TELEMETRY_USER);
        Telemetry.get().addListener(this);
        main.post(tick);
        AppLog.i(TAG, "shown");
    }

    private int widthFor(int h) {
        float aspect = minimized ? DashGaugesView.ASPECT_BUTTONS_ONLY : DashGaugesView.ASPECT_WITH_BUTTONS;
        return Math.min(Math.round(h * aspect), screenWidth());
    }

    /** The card on the right, as on the Dash panel: minimize, record, dim, quit (tap twice). */
    private void press(int button) {
        view.flashButton(button);
        switch (button) {
            case DashGaugesView.MINIMIZE:
                setMinimized(!minimized);
                return;
            case DashGaugesView.RECORD:
                ShortcutPerformer.perform(this, ShortcutAction.RECORD.key, "gauges", null);
                break;
            case DashGaugesView.DIM_BUTTON:
                ShortcutPerformer.perform(this, ShortcutAction.DIM.key, "gauges", null);
                break;
            case DashGaugesView.QUIT_BUTTON:
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
                AppLog.i(TAG, "Quit from the gauges");
                ShortcutPerformer.perform(this, ShortcutAction.QUIT.key, "gauges", null);
                break;
            default:
                int row = button - DashGaugesView.ROW_START;
                if (row < 0 || row >= ROW_ACTIONS.length) {
                    return;
                }
                ShortcutPerformer.perform(this, ROW_ACTIONS[row].key, "gauges", null);
                break;
        }
        main.postDelayed(this::refresh, 300);
    }

    /** Folds the dials away to the card (or back), keeping the right edge where it was. */
    private void setMinimized(boolean minimize) {
        if (minimized == minimize) {
            return;
        }
        int right = params.x + params.width;
        minimized = minimize;
        view.setButtons(true, minimize);
        params.width = widthFor(height);
        params.x = Math.max(0, Math.min(right - params.width, screenWidth() - params.width));
        try {
            windowManager.updateViewLayout(view, params);
        } catch (Exception e) {
            AppLog.w(TAG, "resize: " + e.getMessage());
        }
        OverlayCoordinator.dashPanelMoved();
    }

    @Override
    public void onReadingsChanged(Readings readings) {
        feed.onReadings(readings);
        main.post(this::refresh);
    }

    private void refresh() {
        if (view != null) {
            view.setValues(feed.values(gauges, Telemetry.get().readings()));
            view.controls(com.kooo.evcam.recording.RecordingCoordinator.get(this).isRecordingOrResuming(),
                    config.isDimOverlayEnabled(), quitArmed);
        }
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
        OverlayCoordinator.dashPanelMoved();
        AppLog.i(TAG, "hidden");
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
