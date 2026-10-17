package com.kooo.evcam.overlay;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.blackbox.BlackBox;

import java.util.Calendar;

/**
 * Experimental: turn dim on at night and off in the morning by wall-clock time.
 * Also logs system brightness once when enabled, so we can see if the head unit
 * exposes it (many cars do not).
 */
public final class AutoDim {

    private static final String TAG = "AutoDim";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long CHECK_MS = 60_000L;

    private static Context appContext;
    private static boolean started;
    private static boolean probed;

    private static final Runnable TICK = AutoDim::tick;

    private AutoDim() {
    }

    public static void sync(Context context) {
        appContext = context.getApplicationContext();
        AppConfig config = new AppConfig(appContext);
        if (config.isAutoDimEnabled()) {
            start();
        } else {
            stop();
        }
    }

    private static void start() {
        if (started) {
            tick();
            return;
        }
        started = true;
        AppLog.i(TAG, "自动遮罩试验已启动");
        probeBrightnessOnce();
        tick();
    }

    private static void stop() {
        started = false;
        MAIN.removeCallbacks(TICK);
        AppLog.i(TAG, "自动遮罩试验已停止");
    }

    private static void tick() {
        MAIN.removeCallbacks(TICK);
        if (!started || appContext == null) {
            return;
        }
        AppConfig config = new AppConfig(appContext);
        if (!config.isAutoDimEnabled()) {
            stop();
            return;
        }
        boolean want = nightNow(config.getAutoDimStartHour(), config.getAutoDimEndHour());
        boolean on = config.isDimOverlayEnabled();
        if (want != on) {
            AppLog.i(TAG, "自动遮罩: " + (want ? "开" : "关")
                    + "（时段 " + config.getAutoDimStartHour() + ":00–"
                    + config.getAutoDimEndHour() + ":00）");
            OverlayCoordinator.setDimOverlayEnabled(appContext, want);
        }
        MAIN.postDelayed(TICK, CHECK_MS);
    }

    /** True when hour is in [start, end) wrapping midnight. */
    static boolean nightNow(int startHour, int endHour) {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return inWindow(hour, startHour, endHour);
    }

    static boolean inWindow(int hour, int startHour, int endHour) {
        int start = clampHour(startHour);
        int end = clampHour(endHour);
        if (start == end) {
            return false;
        }
        if (start < end) {
            return hour >= start && hour < end;
        }
        return hour >= start || hour < end;
    }

    private static int clampHour(int hour) {
        if (hour < 0) {
            return 0;
        }
        if (hour > 23) {
            return 23;
        }
        return hour;
    }

    private static void probeBrightnessOnce() {
        if (probed || appContext == null) {
            return;
        }
        probed = true;
        try {
            int mode = Settings.System.getInt(appContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE, -1);
            int value = Settings.System.getInt(appContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, -1);
            BlackBox.noteImportant("自动遮罩试验：系统亮度 mode=" + mode + " value=" + value
                    + "（-1=读不到）");
            AppLog.i(TAG, "系统亮度探测 mode=" + mode + " value=" + value);
        } catch (Exception e) {
            BlackBox.noteImportant("自动遮罩试验：系统亮度读失败 " + e.getClass().getSimpleName());
            AppLog.w(TAG, "系统亮度读失败: " + e);
        }
    }
}
