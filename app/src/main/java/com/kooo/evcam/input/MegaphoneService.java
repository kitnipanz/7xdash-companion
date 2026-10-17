package com.kooo.evcam.input;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.kooo.evcam.MainActivity;
import com.kooo.evcam.R;

import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground only while the mic is open, so a held shortcut still works off the main screen. */
public class MegaphoneService extends Service {

    static final String ACTION_START = "com.kooo.evcam.action.MEGAPHONE_START";
    static final String ACTION_STOP = "com.kooo.evcam.action.MEGAPHONE_STOP";

    private static final String CHANNEL_ID = "megaphone";
    private static final int NOTIFICATION_ID = 7102;
    /** No key-up (the meter window can swallow it) still drops the card shortly after repeats stop. */
    private static final long RELEASE_GAP_MS = 2000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean armed = new AtomicBoolean(false);
    private static volatile long holdUntil;
    private static volatile boolean fixedWindow;
    private static volatile boolean up;
    private static MegaphoneService live;

    private final Megaphone pump = new Megaphone();
    private final Runnable leash = () -> {
        if (SystemClock.uptimeMillis() >= holdUntil) {
            halt();
        } else {
            scheduleLeash();
        }
    };
    private MegaphoneMeter meter;
    private boolean closed;

    public static void start(Context context) {
        if (context == null) {
            return;
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(context, R.string.megaphone_need_mic, Toast.LENGTH_SHORT).show();
            return;
        }
        start(context, 0L);
    }

    /** {@code windowMs} 0 keeps the hold leash. A positive window closes on its own. */
    public static void start(Context context, long windowMs) {
        if (context == null) {
            return;
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(context, R.string.megaphone_need_mic, Toast.LENGTH_SHORT).show();
            return;
        }
        armed.set(true);
        fixedWindow = windowMs > 0L;
        if (fixedWindow) {
            holdUntil = SystemClock.uptimeMillis() + windowMs;
        } else {
            beat();
        }
        Intent intent = new Intent(context, MegaphoneService.class);
        intent.setAction(ACTION_START);
        ContextCompat.startForegroundService(context, intent);
    }

    public static boolean isOpen() {
        return armed.get() || up;
    }

    /** Call on every key-down while the button is held, including repeats. */
    public static void beat() {
        if (fixedWindow) {
            return;
        }
        holdUntil = SystemClock.uptimeMillis() + RELEASE_GAP_MS;
        MAIN.post(() -> {
            MegaphoneService current = live;
            if (current != null) {
                current.scheduleLeash();
            }
        });
    }

    public static void stop(Context context) {
        armed.set(false);
        fixedWindow = false;
        holdUntil = 0L;
        if (context == null) {
            return;
        }
        MAIN.post(() -> {
            MegaphoneService current = live;
            if (current != null) {
                current.halt();
            }
        });
        Intent intent = new Intent(context, MegaphoneService.class);
        intent.setAction(ACTION_STOP);
        try {
            context.startService(intent);
        } catch (IllegalStateException ignored) {
            // not running yet; the pending start sees armed == false and closes
        }
    }

    /** Click-to-speak: the mic stays open until the next click, but never longer than this. */
    static final long TOGGLE_MAX_MS = 10L * 60 * 1000;

    /**
     * Click to open, click again to close. A sticker or a click-to-speak button has no key-up
     * and no repeats, so it must not use the hold leash (that closed the mic after 2 s).
     */
    public static void toggle(Context context) {
        if (isOpen()) {
            stop(context);
        } else {
            start(context, TOGGLE_MAX_MS);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        show();
        if (!armed.get() || (intent != null && ACTION_STOP.equals(intent.getAction()))) {
            halt();
            return START_NOT_STICKY;
        }
        closed = false;
        up = true;
        live = this;
        if (meter == null) {
            meter = new MegaphoneMeter(this);
        }
        meter.show();
        pump.begin(this, this::halt, meter::onPercent);
        scheduleLeash();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        up = false;
        if (live == this) {
            live = null;
        }
        MAIN.removeCallbacks(leash);
        pump.end();
        if (meter != null) {
            meter.hide();
        }
        super.onDestroy();
    }

    private void scheduleLeash() {
        MAIN.removeCallbacks(leash);
        long delay = holdUntil - SystemClock.uptimeMillis();
        if (delay < 0L) {
            delay = 0L;
        }
        MAIN.postDelayed(leash, delay);
    }

    private void halt() {
        if (closed) {
            return;
        }
        closed = true;
        armed.set(false);
        fixedWindow = false;
        up = false;
        if (live == this) {
            live = null;
        }
        MAIN.removeCallbacks(leash);
        pump.end();
        if (meter != null) {
            meter.hide();
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void show() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.megaphone_channel), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            manager.createNotificationChannel(channel);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification note = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_app)
                .setContentTitle(getString(R.string.megaphone_speaking))
                .setContentText(getString(R.string.megaphone_release))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, note);
        }
    }
}
