package com.kooo.evcam.recording;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import com.kooo.evcam.MainActivity;
import com.kooo.evcam.R;

/**
 * The shade notice for save-this-moment. It appears as soon as the button is
 * pressed, then shows a percent while clips are copied or uploaded.
 */
final class MomentNote {

    private static final String CHANNEL_ID = "save_moment";
    private static final int ID = 7101;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Runnable pendingDismiss;

    private MomentNote() {
    }

    static void start(Context context) {
        Context app = context.getApplicationContext();
        MAIN.post(() -> show(app, app.getString(R.string.save_moment_starting), 0, true));
    }

    static void waiting(Context context) {
        Context app = context.getApplicationContext();
        MAIN.post(() -> show(app, app.getString(R.string.save_moment_waiting), 0, true));
    }

    static void progress(Context context, int percent) {
        Context app = context.getApplicationContext();
        int clamped = Math.max(0, Math.min(100, percent));
        MAIN.post(() -> show(app, app.getString(R.string.save_moment_progress, clamped), clamped, false));
    }

    static void finish(Context context, int textRes) {
        Context app = context.getApplicationContext();
        MAIN.post(() -> {
            show(app, app.getString(textRes), 100, false);
            if (pendingDismiss != null) {
                MAIN.removeCallbacks(pendingDismiss);
            }
            pendingDismiss = () -> {
                NotificationManager manager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
                if (manager != null) {
                    manager.cancel(ID);
                }
            };
            MAIN.postDelayed(pendingDismiss, 4000L);
        });
    }

    private static void show(Context app, String text, int percent, boolean indeterminate) {
        NotificationManager manager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    app.getString(R.string.save_moment_channel),
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setSound(null, null);
            manager.createNotificationChannel(channel);
        }
        Intent open = new Intent(app, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(app, 0, open, PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_app)
                .setContentTitle(app.getString(R.string.shortcut_action_save))
                .setContentText(text)
                .setContentIntent(pending)
                .setOnlyAlertOnce(true)
                .setOngoing(indeterminate || percent < 100)
                .setProgress(100, percent, indeterminate);
        manager.notify(ID, builder.build());
    }
}
