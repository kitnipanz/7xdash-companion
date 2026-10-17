package com.kooo.evcam.input;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;

/** Shade notice and a short blip when a shortcut turns the driver HUD on or off. */
final class HudCue {

    private static final String TAG = "HudCue";
    private static final String CHANNEL_ID = "hud_toggle";
    private static final int ID = 7102;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static ToneGenerator tone;
    private static Runnable pendingDismiss;

    private HudCue() {
    }

    static void show(Context context, boolean on) {
        Context app = context.getApplicationContext();
        MAIN.post(() -> {
            note(app, on);
            blip(on);
        });
    }

    private static void note(Context app, boolean on) {
        NotificationManager manager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    app.getString(R.string.shortcut_hud_channel),
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setSound(null, null);
            channel.enableVibration(false);
            manager.createNotificationChannel(channel);
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_app)
                .setContentTitle(app.getString(R.string.shortcut_action_hud))
                .setContentText(app.getString(on ? R.string.shortcut_hud_on : R.string.shortcut_hud_off))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false);
        try {
            manager.notify(ID, builder.build());
        } catch (SecurityException e) {
            AppLog.w(TAG, "抬头显示通知发不出: " + e.getMessage());
        }
        if (pendingDismiss != null) {
            MAIN.removeCallbacks(pendingDismiss);
        }
        pendingDismiss = () -> manager.cancel(ID);
        MAIN.postDelayed(pendingDismiss, 3000L);
    }

    private static void blip(boolean on) {
        ToneGenerator created;
        try {
            created = new ToneGenerator(AudioManager.STREAM_MUSIC, 90);
        } catch (RuntimeException e) {
            AppLog.w(TAG, "提示音失败: " + e.getMessage());
            return;
        }
        ToneGenerator previous = tone;
        tone = created;
        if (previous != null) {
            previous.release();
        }
        created.startTone(on ? ToneGenerator.TONE_PROP_ACK : ToneGenerator.TONE_PROP_NACK, 180);
        MAIN.postDelayed(() -> {
            if (tone == created) {
                tone = null;
                created.release();
            }
        }, 400L);
    }
}
