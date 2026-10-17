package com.kooo.evcam.hud;

import android.app.Notification;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import com.kooo.evcam.AppConfig;

/** 别的应用发通知时，按设置把它投到抬头显示上（{@link HudNoticeWindow}）。 */
public class HudNotificationService extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !new AppConfig(this).isHudNotificationsEnabled()) {
            return;
        }
        Notification notification = sbn.getNotification();
        if (notification == null) {
            return;
        }
        Bundle extras = notification.extras;
        CharSequence title = extras == null ? null : extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = extras == null ? null : extras.getCharSequence(Notification.EXTRA_TEXT);
        boolean summary = (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
        if (!HudNotice.accept(sbn.getPackageName(), getPackageName(), sbn.isOngoing(), summary, title, text)) {
            return;
        }
        HudNoticeWindow.show(this, appLabel(sbn.getPackageName()),
                HudNotice.clip(title, HudNotice.MAX_TITLE), HudNotice.clip(text, HudNotice.MAX_TEXT));
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(info).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }
}
