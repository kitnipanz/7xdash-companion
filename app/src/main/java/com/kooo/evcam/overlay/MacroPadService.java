package com.kooo.evcam.overlay;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.UserExit;
import com.kooo.evcam.input.ShortcutPerformer;
import com.kooo.evcam.zeekr.FloatingWindowGestures;
import com.kooo.evcam.zeekr.RearViewTouchModel;

/**
 * Macro pad: a small floating grid of buttons over any app, like the floating buttons, where each
 * button runs the action the driver picked for it ({@link com.kooo.evcam.input.MacroPadActivity}).
 * An empty button opens that screen. Moves, pinches and docks like the Dash panel.
 *
 * <p>While the Dash panel is up the pad hangs right under it ({@link MacroPadDock}): as wide as the
 * panel's window (the whole panel, or the minimized pill with its buttons), and it follows the panel around. Dragging it
 * then does nothing (drag the panel); a pinch still sets its height. When the panel closes it goes
 * back to where it floated before.</p>
 */
public class MacroPadService extends Service {

    private static final String TAG = "MacroPad";
    private static final String ACTION_SHOW = "com.kooo.evcam.action.SHOW_MACRO_PAD";
    private static final String ACTION_HIDE = "com.kooo.evcam.action.HIDE_MACRO_PAD";
    private static final String ACTION_RELOAD = "com.kooo.evcam.action.RELOAD_MACRO_PAD";
    /** Default and smallest height, in dp. */
    private static final int DEFAULT_HEIGHT_DP = 170;
    private static final int MIN_HEIGHT_DP = 80;
    /** Space between the Dash panel and the pad docked under it, in dp. */
    private static final int DOCK_GAP_DP = 6;

    private static volatile MacroPadService instance;

    /** The Dash panel moved, resized, minimized, opened or closed: dock to it, follow it, or float again. */
    static void followDashPanel() {
        MacroPadService svc = instance;
        if (svc != null) {
            svc.main.post(svc::follow);
        }
    }

    /** True while the pad's window is up. Main thread. */
    static boolean isUp() {
        MacroPadService svc = instance;
        return svc != null && svc.view != null;
    }

    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    /** Hanging under the Dash panel. */
    private boolean docked;
    /** Where the pad floats on its own, kept while it is docked. */
    private int floatX;
    private int floatY;

    private WindowManager windowManager;
    private MacroPadView view;
    private WindowManager.LayoutParams params;
    private AppConfig config;
    private String[] slots;

    public static void show(Context context) {
        start(context, ACTION_SHOW);
    }

    public static void hide(Context context) {
        start(context, ACTION_HIDE);
    }

    /** The buttons changed: redraw if the pad is up. Does not show it. */
    public static void reload(Context context) {
        if (new AppConfig(context).isMacroPadEnabled()) {
            start(context, ACTION_RELOAD);
        }
    }

    private static void start(Context context, String action) {
        try {
            context.startService(new Intent(context, MacroPadService.class).setAction(action));
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
        } else {
            attach();
            loadSlots();
        }
        return START_NOT_STICKY;
    }

    private void loadSlots() {
        slots = MacroPadSlots.parse(config.getMacroPadSlots());
        if (view != null) {
            view.setSlots(slots);
        }
    }

    private void attach() {
        if (view != null) {
            return;
        }
        view = new MacroPadView(this);
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        int[] saved = config.getMacroPadBounds();
        int height = saved[3] > 0 ? saved[3] : dp(DEFAULT_HEIGHT_DP);
        params.height = Math.max(dp(MIN_HEIGHT_DP), Math.min(height, screenHeight()));
        params.width = Math.round(params.height * MacroPadView.ASPECT);
        params.x = saved[2] > 0 ? saved[0] : screenWidth() - params.width - dp(24);
        params.y = saved[2] > 0 ? saved[1] : screenHeight() - params.height - dp(96);
        floatX = params.x;
        floatY = params.y;
        docked = false;
        snapToPanel();
        FloatingWindowGestures gestures = new FloatingWindowGestures(this, new FloatingWindowGestures.Host() {
            @Override
            public WindowManager.LayoutParams params() {
                return params;
            }

            @Override
            public int screenWidth() {
                return MacroPadService.this.screenWidth();
            }

            @Override
            public int screenHeight() {
                return MacroPadService.this.screenHeight();
            }

            @Override
            public int minSize() {
                return dp(MIN_HEIGHT_DP);
            }

            @Override
            public void apply() {
                if (view != null && view.isAttachedToWindow()) {
                    // docked, a drag is undone here: the pad stays under the panel
                    snapToPanel();
                    windowManager.updateViewLayout(view, params);
                }
            }

            @Override
            public void save() {
                if (!docked) {
                    floatX = params.x;
                    floatY = params.y;
                }
                config.setMacroPadBounds(floatX, floatY, Math.round(params.height * MacroPadView.ASPECT),
                        params.height);
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
        // a minimized Dash panel moves its buttons beside the pill, so the pad docks under one strip
        DashPanelService.macroPadChanged();
        AppLog.i(TAG, "shown");
    }

    /**
     * Puts the pad under the Dash panel if it is up, or back where it floated if it just closed.
     * Only changes {@link #params}. Returns whether anything changed.
     */
    private boolean snapToPanel() {
        if (params == null) {
            return false;
        }
        int[] panel = DashPanelService.windowBounds();
        int x = params.x;
        int y = params.y;
        int w = params.width;
        if (panel != null) {
            docked = true;
            // as wide as the panel's window: the whole panel, or the pill and its row of buttons
            params.width = panel[2];
            int[] at = MacroPadDock.place(panel, params.width, params.height, screenHeight(), dp(DOCK_GAP_DP));
            params.x = at[0];
            params.y = at[1];
        } else if (docked) {
            docked = false;
            params.width = Math.round(params.height * MacroPadView.ASPECT);
            params.x = RearViewTouchModel.clampX(floatX, params.width, screenWidth(),
                    FloatingWindowGestures.PEEK_WIDTH_PX);
            params.y = RearViewTouchModel.clampY(floatY, params.height, screenHeight());
        }
        return x != params.x || y != params.y || w != params.width;
    }

    private void follow() {
        // getParent(), not isAttachedToWindow(): set by addView at once, not a frame later
        if (view == null || view.getParent() == null) {
            return;
        }
        if (snapToPanel()) {
            try {
                windowManager.updateViewLayout(view, params);
            } catch (Exception e) {
                AppLog.w(TAG, "follow the Dash panel: " + e.getMessage());
            }
        }
    }

    private void press(int button) {
        if (view == null || slots == null || button < 0 || button >= slots.length) {
            return;
        }
        view.flash(button);
        String key = slots[button];
        if (key.isEmpty()) {
            // An empty button: go and choose what it should do
            startActivity(new Intent(this, com.kooo.evcam.input.MacroPadActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(com.kooo.evcam.input.MacroPadActivity.EXTRA_SLOT, button));
            return;
        }
        ShortcutPerformer.perform(this, key, "macro_pad", null);
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
        if (view != null) {
            try {
                windowManager.removeView(view);
            } catch (Exception ignored) {
            }
            view = null;
        }
        DashPanelService.macroPadChanged();
        AppLog.i(TAG, "hidden");
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
