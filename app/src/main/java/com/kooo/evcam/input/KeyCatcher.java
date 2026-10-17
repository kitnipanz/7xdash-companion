package com.kooo.evcam.input;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.KeepAliveAccessibilityService;
import com.kooo.evcam.WakeUpHelper;

/**
 * 没有无障碍服务时，让快捷键在任何界面都能用：一个 1 像素、看不见的悬浮窗拿着按键焦点。
 *
 * <p>拿着焦点时，前台应用收不到实体键，也弹不出输入法。所以一碰屏幕就把焦点让出去
 * （点输入框、打字都正常），{@link #REGAIN_MS} 内没再碰才拿回来。本应用自己的界面在前台时也让开，
 * 它们自己收键（主界面走 {@link AccessibilityGate#handleInApp}，添加快捷键页要录键）。</p>
 */
public final class KeyCatcher {
    private static final String TAG = "KeyCatcher";
    static final long REGAIN_MS = 3000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Context appContext;
    private static WindowManager windowManager;
    private static CatcherView view;
    private static WindowManager.LayoutParams params;
    private static boolean appInFront;
    private static boolean yielded;

    private static final Runnable REGAIN = () -> {
        yielded = false;
        applyFocus();
    };

    private KeyCatcher() {
    }

    /** 按当前设置挂上或拿掉。主线程调用。 */
    public static void sync(Context context) {
        appContext = context.getApplicationContext();
        if (wanted(appContext)) {
            attach();
        } else {
            detach();
        }
    }

    /** 本应用有界面在前台：让开焦点。 */
    public static void setAppInFront(Context context, boolean front) {
        appInFront = front;
        if (front) {
            applyFocus();
        } else {
            sync(context);
        }
    }

    static boolean wanted(Context context) {
        AppConfig config = new AppConfig(context);
        return config.isShortcutCatchEverywhere()
                && !config.getButtonShortcuts().isEmpty()
                && !KeepAliveAccessibilityService.isRunning()
                && WakeUpHelper.hasOverlayPermission(context);
    }

    private static void attach() {
        if (view != null) {
            applyFocus();
            return;
        }
        windowManager = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
        view = new CatcherView(appContext);
        params = new WindowManager.LayoutParams(
                1, 1,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                flags(),
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        try {
            windowManager.addView(view, params);
            AppLog.i(TAG, "按键捕捉窗已挂上");
        } catch (Exception e) {
            AppLog.e(TAG, "按键捕捉窗添加失败", e);
            view = null;
        }
    }

    private static void detach() {
        MAIN.removeCallbacks(REGAIN);
        if (view != null && windowManager != null) {
            try {
                windowManager.removeView(view);
            } catch (Exception e) {
                AppLog.w(TAG, "按键捕捉窗移除失败: " + e);
            }
            AppLog.i(TAG, "按键捕捉窗已拿掉");
        }
        view = null;
    }

    /**
     * 能拿焦点时不带 NOT_FOCUSABLE；ALT_FOCUSABLE_IM 让输入法仍以下面的应用为目标。
     * WATCH_OUTSIDE_TOUCH 用来知道用户碰了屏幕。
     */
    private static int flags() {
        int flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (appInFront || yielded) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        return flags;
    }

    private static void applyFocus() {
        if (view == null || params == null) {
            return;
        }
        int next = flags();
        if (next == params.flags) {
            return;
        }
        params.flags = next;
        try {
            windowManager.updateViewLayout(view, params);
        } catch (Exception e) {
            AppLog.w(TAG, "按键捕捉窗更新失败: " + e);
        }
        if ((next & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0) {
            view.requestFocus();
        }
    }

    private static void yieldForTouch() {
        MAIN.removeCallbacks(REGAIN);
        MAIN.postDelayed(REGAIN, REGAIN_MS);
        if (!yielded) {
            yielded = true;
            applyFocus();
        }
    }

    private static final class CatcherView extends View {
        CatcherView(Context context) {
            super(context);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (ShortcutCapture.isActive()) {
                return false;
            }
            if (ShortcutKeys.dispatch(getContext(), event)) {
                return true;
            }
            // 不是快捷键（比如返回键）：这一下送不到前台应用了，至少让下一下能送到
            if (event.getAction() == KeyEvent.ACTION_UP) {
                yieldForTouch();
            }
            return false;
        }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE
                    || event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                yieldForTouch();
            }
            return false;
        }
    }
}
