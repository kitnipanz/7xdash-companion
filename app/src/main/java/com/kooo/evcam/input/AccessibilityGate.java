package com.kooo.evcam.input;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.view.KeyEvent;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.KeepAliveAccessibilityService;

/**
 * 车机上没有系统的无障碍设置页（2026-10-03 实车：跳过去只弹「没有应用能执行此操作」）。
 * 用 adb 授一次 WRITE_SECURE_SETTINGS 之后，应用自己把无障碍服务写进系统设置。
 */
public final class AccessibilityGate {
    private static final String TAG = "AccessibilityGate";

    private AccessibilityGate() {
    }

    public static ComponentName component(Context context) {
        return new ComponentName(context, KeepAliveAccessibilityService.class);
    }

    public static boolean canWriteSecure(Context context) {
        return context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static String grantCommand(Context context) {
        return "adb shell pm grant " + context.getPackageName()
                + " android.permission.WRITE_SECURE_SETTINGS";
    }

    public static boolean isEnabled(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return contains(enabled, component(context).flattenToString());
    }

    /** 有权限就把服务写进系统设置；写成了或本来就开着返回 true。 */
    public static boolean selfEnable(Context context) {
        if (KeepAliveAccessibilityService.isRunning()) {
            return true;
        }
        if (!canWriteSecure(context)) {
            return false;
        }
        try {
            String self = component(context).flattenToString();
            String enabled = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (!contains(enabled, self)) {
                String value = enabled == null || enabled.isEmpty() ? self : enabled + ":" + self;
                Settings.Secure.putString(context.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value);
            }
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, "1");
            AppLog.i(TAG, "无障碍服务已由应用自己打开");
            return true;
        } catch (Exception e) {
            AppLog.e(TAG, "自己打开无障碍服务失败", e);
            return false;
        }
    }

    /** 启动时：存着快捷键就试着把服务开起来。 */
    public static void ensureForShortcuts(Context context) {
        if (!new AppConfig(context).getButtonShortcuts().isEmpty()) {
            selfEnable(context);
        }
    }

    /**
     * 应用自己在前台时，按键直接走这里，不靠无障碍服务。
     * 返回 true 表示这一下归快捷键（按下时已执行，抬起和重复吃掉）。
     */
    public static boolean handleInApp(Context context, KeyEvent event) {
        if (KeepAliveAccessibilityService.isRunning()) {
            return false;
        }
        return ShortcutKeys.dispatch(context, event);
    }

    static boolean contains(String list, String component) {
        if (list == null || list.isEmpty()) {
            return false;
        }
        for (String item : list.split(":")) {
            if (item.equalsIgnoreCase(component)
                    || ComponentName.unflattenFromString(item) != null
                    && component.equalsIgnoreCase(ComponentName.unflattenFromString(item).flattenToString())) {
                return true;
            }
        }
        return false;
    }
}
