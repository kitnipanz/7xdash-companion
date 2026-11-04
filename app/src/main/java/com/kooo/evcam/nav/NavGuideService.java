package com.kooo.evcam.nav;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;

/**
 * Keeps the GPS coming while a trip is on. Android stops an app's location as soon as no screen of
 * it is in front, a floating window included, unless a location foreground service is running; without
 * this the map and the HUD freeze when Navigation is closed. Started and stopped by {@link Navigator}.
 */
public final class NavGuideService extends Service {

    private static final String TAG = "NavGuideService";
    private static final String CHANNEL = "nav_guide";
    private static final int NOTIFICATION_ID = 1707;

    static void start(Context context) {
        try {
            context.startForegroundService(new Intent(context, NavGuideService.class));
        } catch (RuntimeException e) {
            // not allowed from the background on newer Android: the trip goes on with what location it gets
            AppLog.w(TAG, "not started: " + e.getClass().getSimpleName());
        }
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, NavGuideService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, notification());
            }
        } catch (RuntimeException e) {
            // no location permission, or not eligible right now
            AppLog.w(TAG, "not in the foreground: " + e.getClass().getSimpleName());
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification notification() {
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,
                getString(R.string.nav_title), NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this, NavigationActivity.class);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_navigation)
                .setContentTitle(getString(R.string.nav_title))
                .setContentText(getString(R.string.nav_guide_running))
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
