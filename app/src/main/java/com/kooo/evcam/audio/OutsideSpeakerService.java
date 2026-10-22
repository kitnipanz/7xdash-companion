package com.kooo.evcam.audio;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.R;
import com.kooo.evcam.input.MegaphoneService;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Beta outside speaker. Android only lets an app hear other apps' playback through a screen
 * capture grant, so every start asks once on the screen ({@link SpeakerConsentActivity}),
 * also when the start came from 7xDash web. Foreground for as long as it plays.
 */
public class OutsideSpeakerService extends Service {

    static final String ACTION_START = "com.kooo.evcam.action.OUTSIDE_SPEAKER_START";
    static final String ACTION_STOP = "com.kooo.evcam.action.OUTSIDE_SPEAKER_STOP";
    static final String EXTRA_RESULT = "result";
    static final String EXTRA_DATA = "data";

    private static final String CHANNEL_ID = "outside_speaker";
    private static final int NOTIFICATION_ID = 7103;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    private static volatile boolean on;
    private static volatile boolean asking;
    private static OutsideSpeakerService live;

    private final OutsideSpeaker pump = new OutsideSpeaker();
    private MediaProjection projection;
    private boolean closed;

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    public static boolean isOn() {
        return on;
    }

    /** Waiting for the driver to allow the capture on the screen. */
    public static boolean isAsking() {
        return asking;
    }

    /** Runs on the main thread whenever on, asking or the volume changes. */
    public static void addListener(Runnable listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public static void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    public static void toggle(Context context) {
        if (on || asking) {
            stop(context);
        } else {
            start(context);
        }
    }

    public static void start(Context context) {
        if (context == null || on || asking) {
            return;
        }
        if (!isSupported()) {
            com.kooo.evcam.ui.Notice.makeText(context, R.string.outside_speaker_unsupported, Toast.LENGTH_SHORT).show();
            return;
        }
        asking = true;
        changed();
        Intent intent = new Intent(context, SpeakerConsentActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            context.startActivity(intent);
        } catch (RuntimeException e) {
            asking = false;
            changed();
            com.kooo.evcam.ui.Notice.makeText(context, R.string.outside_speaker_refused, Toast.LENGTH_SHORT).show();
        }
    }

    public static void stop(Context context) {
        boolean wasAsking = asking;
        asking = false;
        MAIN.post(() -> {
            OutsideSpeakerService current = live;
            if (current != null) {
                current.halt();
            }
        });
        if (wasAsking) {
            SpeakerConsentActivity.dismiss();
            changed();
        }
    }

    /** Saves the slider and applies it to the music already playing. */
    public static void setVolume(Context context, int percent) {
        if (context == null) {
            return;
        }
        AppConfig config = new AppConfig(context);
        config.setOutsideSpeakerVolume(percent);
        OutsideSpeakerService current = live;
        if (current != null) {
            current.pump.setVolume(config.getOutsideSpeakerVolume());
        }
        changed();
    }

    /** From {@link SpeakerConsentActivity}: the driver answered. */
    static void answered(Context context, int resultCode, Intent data) {
        if (!asking) {
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null) {
            asking = false;
            changed();
            return;
        }
        Intent intent = new Intent(context, OutsideSpeakerService.class);
        intent.setAction(ACTION_START);
        intent.putExtra(EXTRA_RESULT, resultCode);
        intent.putExtra(EXTRA_DATA, data);
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (RuntimeException e) {
            asking = false;
            changed();
            com.kooo.evcam.ui.Notice.makeText(context, R.string.outside_speaker_refused, Toast.LENGTH_SHORT).show();
        }
    }

    private static void changed() {
        MAIN.post(() -> {
            for (Runnable listener : listeners) {
                listener.run();
            }
        });
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            asking = false;
            halt();
            return START_NOT_STICKY;
        }
        // A media projection may only be fetched once the service is in the foreground
        show();
        if (intent == null || !ACTION_START.equals(intent.getAction()) || !asking || pump.isGoing()) {
            if (!pump.isGoing()) {
                halt();
            }
            return START_NOT_STICKY;
        }
        asking = false;
        closed = false;
        live = this;
        int result = intent.getIntExtra(EXTRA_RESULT, Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        try {
            projection = manager == null || data == null ? null : manager.getMediaProjection(result, data);
        } catch (RuntimeException e) {
            projection = null;
        }
        if (projection == null || !isSupported()) {
            fail(R.string.outside_speaker_refused);
            return START_NOT_STICKY;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                halt();
            }
        }, MAIN);
        AppConfig config = new AppConfig(this);
        pump.begin(this, projection, config.getSpeakSpeaker(), config.getOutsideSpeakerVolume(),
                MegaphoneService::isOpen, failure -> MAIN.post(() -> {
                    if (failure != OutsideSpeaker.ENDED) {
                        fail(failure);
                    } else {
                        halt();
                    }
                }));
        on = true;
        changed();
        com.kooo.evcam.hud.HudNotice.post(this, getString(R.string.outside_speaker_playing));
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        halt();
        super.onDestroy();
    }

    private void fail(int message) {
        com.kooo.evcam.ui.Notice.makeText(this, message, Toast.LENGTH_SHORT).show();
        halt();
    }

    private void halt() {
        if (closed) {
            return;
        }
        closed = true;
        if (live == this) {
            live = null;
        }
        pump.end();
        MediaProjection held = projection;
        projection = null;
        if (held != null) {
            try {
                held.stop();
            } catch (RuntimeException ignored) {
                // already stopped by the system
            }
        }
        boolean was = on;
        on = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        if (was) {
            changed();
        }
    }

    private void show() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.outside_speaker_channel), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            manager.createNotificationChannel(channel);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, OutsideSpeakerService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification note = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_app)
                .setContentTitle(getString(R.string.outside_speaker_playing))
                .setContentText(getString(R.string.outside_speaker_note))
                .setContentIntent(open)
                .addAction(0, getString(R.string.outside_speaker_stop), stop)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, note);
        }
    }
}
