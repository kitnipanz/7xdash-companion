package com.kooo.evcam.hud;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.input.AccessibilityGate;

/**
 * 读通知的权限。车机上多半没有系统的「通知使用权」页（和无障碍一样），
 * 授过 WRITE_SECURE_SETTINGS 后应用自己把监听服务写进系统设置。
 */
public final class NotificationGate {

    private static final String TAG = "NotificationGate";
    /** {@code Settings.Secure.ENABLED_NOTIFICATION_LISTENERS}，SDK 里藏着，值是公开的。 */
    private static final String ENABLED_LISTENERS = "enabled_notification_listeners";

    private NotificationGate() {
    }

    public static ComponentName component(Context context) {
        return new ComponentName(context, HudNotificationService.class);
    }

    public static boolean isEnabled(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(), ENABLED_LISTENERS);
        return contains(enabled, component(context).flattenToString());
    }

    /** 已经开着、或者自己写成了返回 true。 */
    public static boolean selfEnable(Context context) {
        if (isEnabled(context)) {
            return true;
        }
        if (!AccessibilityGate.canWriteSecure(context)) {
            return false;
        }
        try {
            String self = component(context).flattenToString();
            String enabled = Settings.Secure.getString(context.getContentResolver(), ENABLED_LISTENERS);
            String value = enabled == null || enabled.isEmpty() ? self : enabled + ":" + self;
            Settings.Secure.putString(context.getContentResolver(), ENABLED_LISTENERS, value);
            AppLog.i(TAG, "通知监听已由应用自己打开");
            return true;
        } catch (Exception e) {
            AppLog.e(TAG, "自己打开通知监听失败", e);
            return false;
        }
    }

    /** 系统有那一页就跳过去；车机上没有时返回 false。 */
    public static boolean openSystemPage(Context context) {
        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.resolveActivity(context.getPackageManager()) == null) {
            return false;
        }
        try {
            context.startActivity(intent);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean contains(String list, String component) {
        if (list == null || list.isEmpty()) {
            return false;
        }
        for (String item : list.split(":")) {
            ComponentName name = ComponentName.unflattenFromString(item);
            if (item.equalsIgnoreCase(component)
                    || name != null && component.equalsIgnoreCase(name.flattenToString())) {
                return true;
            }
        }
        return false;
    }
}
