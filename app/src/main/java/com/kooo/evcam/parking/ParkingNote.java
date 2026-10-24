package com.kooo.evcam.parking;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.text.format.DateFormat;

import androidx.core.app.NotificationCompat;

import com.kooo.evcam.MainActivity;
import com.kooo.evcam.R;

/** The shade notice for where the car is parked. Stays until swiped or replaced. */
final class ParkingNote {

    private static final String CHANNEL_ID = "where_parked";
    private static final int ID = 7201;

    private ParkingNote() {
    }

    static void reading(Context app) {
        post(app, app.getString(R.string.parking_reading), null, null);
    }

    static void noCamera(Context app) {
        post(app, app.getString(R.string.parking_no_camera), null, null);
    }

    static void show(Context app, ParkingText.Spot spot, long at, Bitmap picture) {
        String title = spot.isEmpty()
                ? app.getString(R.string.parking_no_sign)
                : app.getString(R.string.parking_at, spot.label());
        post(app, title, details(app, spot, at), picture);
    }

    /**
     * Pillar scan saw a new floor or bay while creeping: a card on the HUD, not the shade (eyes stay
     * on the road; the shade gets the parked result). Shown whatever the HUD notices switch says,
     * nothing on a car without a HUD.
     */
    static void seen(Context app, ParkingText.Spot spot, long at) {
        com.kooo.evcam.hud.HudNoticeWindow.show(app, "7xDash",
                app.getString(R.string.parking_seen, spot.label()), details(app, spot, at));
    }

    private static String details(Context app, ParkingText.Spot spot, long at) {
        String time = DateFormat.getTimeFormat(app).format(at);
        StringBuilder text = new StringBuilder();
        if (!spot.floor.isEmpty()) {
            text.append(app.getString(R.string.parking_floor, spot.floor)).append("  ");
        }
        if (!spot.bay.isEmpty()) {
            text.append(app.getString(R.string.parking_bay, spot.bay)).append("  ");
        }
        text.append(time);
        return text.toString();
    }

    private static void post(Context app, String title, String text, Bitmap picture) {
        NotificationManager manager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    app.getString(R.string.parking_channel),
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setSound(null, null);
            manager.createNotificationChannel(channel);
        }
        Intent open = new Intent(app, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(app, 0, open, PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_app)
                .setContentTitle(title)
                .setContentIntent(pending)
                .setOnlyAlertOnce(true)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true);
        if (text != null) {
            builder.setContentText(text);
        }
        if (picture != null) {
            builder.setLargeIcon(picture)
                    .setStyle(new NotificationCompat.BigPictureStyle()
                            .bigPicture(picture)
                            .bigLargeIcon((Bitmap) null)
                            .setSummaryText(text));
        }
        manager.notify(ID, builder.build());
    }
}
