package com.kooo.evcam.airplay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.R;
import com.kooo.evcam.overlay.OverlayCoordinator;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;
import com.kooo.evcam.ui.Notice;

import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.jqssun.airplay.bridge.NativeBridge;
import io.github.jqssun.airplay.bridge.RaopCallbackHandler;

/**
 * AirPlay screen-mirroring receiver. While it runs the head unit shows up in the iPhone's
 * Screen Mirroring list as "7xDash"; when the iPhone mirrors, the picture plays in a floating
 * window ({@link AirPlayWindow}) and the sound through the car's speakers.
 *
 * <p>The protocol is UxPlay's, through jqssun/android-airplay-server's native bridge (both
 * GPL-3.0, see NOTICE.md). Only screen mirroring is offered: no music-only AirPlay (that needs
 * software ALAC) and no video URL casting.</p>
 *
 * <p>The picture hides while the car is in gear or moving ({@link DrivingGate}).</p>
 */
public class AirPlayService extends Service implements RaopCallbackHandler {

    private static final String TAG = "AirPlay";
    private static final String CHANNEL_ID = "airplay";
    private static final int NOTIFICATION_ID = 7071;
    private static final String PREFS = "airplay";
    private static final String KEY_ON = "on";
    private static final String KEY_MAC = "mac";
    private static final String TELEMETRY_USER = "airplay";
    private static final int PORT = 7000;
    private static final int FPS = 30;

    private static volatile AirPlayService live;
    private static volatile String state = "";

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread worker;
    private Handler work;
    private long handle;
    private NsdManager nsd;
    private final List<NsdManager.RegistrationListener> registrations = new ArrayList<>();
    private WifiManager.MulticastLock multicast;
    private final MirrorDecoder decoder = new MirrorDecoder();
    private AirPlayWindow window;
    private final AtomicInteger connections = new AtomicInteger();
    private boolean mirroring;
    private boolean watchingCar;
    /** Set when mirroring starts; the first decoded frame clears it and shows the picture. */
    private volatile boolean firstFrame;
    private String name = "7xDash";

    // ---- public switch ----

    public static boolean enabled(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ON, false);
    }

    public static void setEnabled(Context context, boolean on) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply();
        if (on) {
            start(context);
        } else {
            context.stopService(new Intent(context, AirPlayService.class));
        }
    }

    /** Start if the user left it on. Called when the app process starts. */
    public static void applySaved(Context context) {
        if (enabled(context) && live == null) {
            start(context);
        }
    }

    public static boolean isRunning() {
        return live != null;
    }

    /** One line for the AirPlay page: waiting / connected / mirroring. Empty when off. */
    public static String state() {
        return live == null ? "" : state;
    }

    public static String deviceName() {
        AirPlayService s = live;
        return s == null ? "7xDash" : s.name;
    }

    private static void start(Context context) {
        try {
            androidx.core.content.ContextCompat.startForegroundService(context,
                    new Intent(context, AirPlayService.class));
        } catch (RuntimeException e) {
            AppLog.w(TAG, "could not start: " + e);
        }
    }

    // ---- lifecycle ----

    @Override
    public void onCreate() {
        super.onCreate();
        live = this;
        promote();
        window = new AirPlayWindow(this, new AirPlayWindow.Listener() {
            @Override
            public void onSurface(Surface surface) {
                decoder.setSurface(surface);
            }

            @Override
            public void onCloseTapped() {
                endMirroring();
            }
        });
        worker = new HandlerThread("airplay");
        worker.start();
        work = new Handler(worker.getLooper());
        work.post(this::startServer);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        promote();
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        live = null;
        state = "";
        main.removeCallbacksAndMessages(null);
        if (window != null) {
            window.hide();
        }
        stopWatchingCar();
        if (work != null) {
            work.post(this::stopServer);
            worker.quitSafely();
        }
        super.onDestroy();
    }

    // ---- server ----

    private void startServer() {
        name = getString(R.string.airplay_device_name);
        multicast = getSystemService(WifiManager.class) == null ? null
                : getSystemService(WifiManager.class).createMulticastLock("airplay_mdns");
        if (multicast != null) {
            multicast.setReferenceCounted(false);
            multicast.acquire();
        }
        AudioManager am = getSystemService(AudioManager.class);
        NativeBridge.nativeSetDefaultStreamValues(
                parse(am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)),
                parse(am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)));
        String keyFile = getFilesDir().getAbsolutePath() + "/airplay.pem";
        handle = NativeBridge.nativeInit(this, hwAddr(), name, keyFile, true, false);
        if (handle == 0) {
            fail("native init failed");
            return;
        }
        NativeBridge.nativeServerAudioConfigure(handle, 0, 95, 0, false, false, true, true, false);
        NativeBridge.nativeSetH265Enabled(handle, false);
        NativeBridge.nativeSetCodecs(handle, false, true);
        NativeBridge.nativeSetHlsEnabled(handle, false);
        NativeBridge.nativeSetLang(handle, "", "",
                getResources().getConfiguration().getLocales().toLanguageTags().replace(',', ':'));
        NativeBridge.nativeSetFeature(handle, NativeBridge.FT_MIRROR, true);
        NativeBridge.nativeSetFeature(handle, NativeBridge.FT_AUDIO, false);
        NativeBridge.nativeSetPlist(handle, "maxFPS", FPS);
        NativeBridge.nativeSetPlist(handle, "overscanned", 0);
        int[] size = displaySize();
        NativeBridge.nativeSetDisplaySize(handle, size[0], size[1], FPS);

        int port = NativeBridge.nativeStart(handle, PORT);
        if (port < 0) {
            fail("could not listen on " + PORT);
            return;
        }
        Map<String, String> raopTxt = NativeBridge.nativeGetRaopTxtRecords(handle);
        Map<String, String> airplayTxt = NativeBridge.nativeGetAirplayTxtRecords(handle);
        String raopName = NativeBridge.nativeGetRaopServiceName(handle);
        String serverName = NativeBridge.nativeGetServerName(handle);
        nsd = getSystemService(NsdManager.class);
        register(raopName != null ? raopName : name, "_raop._tcp", port, raopTxt);
        register(serverName != null ? serverName : name, "_airplay._tcp", port, airplayTxt);
        setState(getString(R.string.airplay_state_waiting, name));
        AppLog.i(TAG, "receiver up on port " + port + " as " + name + " " + size[0] + "x" + size[1]);
    }

    private void stopServer() {
        if (nsd != null) {
            for (NsdManager.RegistrationListener l : registrations) {
                try {
                    nsd.unregisterService(l);
                } catch (RuntimeException ignored) {
                    // never registered
                }
            }
        }
        registrations.clear();
        if (handle != 0) {
            NativeBridge.nativeServerAudioStop(handle);
            NativeBridge.nativeStop(handle);
            NativeBridge.nativeDestroy(handle);
            handle = 0;
        }
        decoder.release();
        if (multicast != null && multicast.isHeld()) {
            multicast.release();
        }
    }

    private void fail(String why) {
        AppLog.w(TAG, why);
        main.post(() -> {
            Notice.makeText(this, R.string.airplay_failed, Toast.LENGTH_LONG).show();
            stopSelf();
        });
    }

    private void register(String serviceName, String type, int port, @Nullable Map<String, String> txt) {
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(serviceName);
        info.setServiceType(type);
        info.setPort(port);
        if (txt != null) {
            for (Map.Entry<String, String> e : txt.entrySet()) {
                info.setAttribute(e.getKey(), e.getValue());
            }
        }
        NsdManager.RegistrationListener l = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo s) {
                AppLog.i(TAG, type + " registered: " + s.getServiceName());
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo s, int code) {
                AppLog.w(TAG, type + " registration failed: " + code);
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo s) {
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo s, int code) {
            }
        };
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l);
        registrations.add(l);
    }

    /** Close tapped: drop the iPhone by restarting the receiver; it stays available. */
    private void endMirroring() {
        window.hide();
        work.post(() -> {
            stopServer();
            startServer();
        });
    }

    // ---- floating window and the car ----

    private void mirrorStarted() {
        mirroring = true;
        if (!OverlayCoordinator.canShowOverlay(this)) {
            Notice.makeText(this, R.string.airplay_need_overlay, Toast.LENGTH_LONG).show();
            return;
        }
        window.show();
        window.setPlaying(false);
        watchCar();
        setState(getString(R.string.airplay_state_mirroring));
        Notice.makeText(this, R.string.airplay_started, Toast.LENGTH_SHORT).show();
    }

    private void mirrorStopped() {
        mirroring = false;
        window.hide();
        stopWatchingCar();
        setState(connections.get() > 0 ? getString(R.string.airplay_state_connected)
                : getString(R.string.airplay_state_waiting, name));
    }

    private final Telemetry.Listener carListener = readings -> main.post(this::checkCar);

    private void watchCar() {
        if (watchingCar) {
            return;
        }
        watchingCar = true;
        Telemetry.get().acquire(this, TELEMETRY_USER);
        Telemetry.get().addListener(carListener);
        checkCar();
    }

    private void stopWatchingCar() {
        if (!watchingCar) {
            return;
        }
        watchingCar = false;
        Telemetry.get().removeListener(carListener);
        Telemetry.get().release(TELEMETRY_USER);
    }

    private void checkCar() {
        if (!mirroring || window == null) {
            return;
        }
        Readings r = Telemetry.get().readings();
        window.setBlocked(DrivingGate.blocked(r.text(Signal.GEAR), Telemetry.get().latest().speedKmh));
    }

    // ---- RaopCallbackHandler (native threads) ----

    @Override
    public void onVideoData(byte[] data, long ntpTimeNs, boolean isH265) {
        if (isH265) {
            return;
        }
        decoder.feed(data, ntpTimeNs);
        if (firstFrame) {
            firstFrame = false;
            main.post(() -> window.setPlaying(true));
        }
    }

    @Override
    public void onVideoSize(float srcW, float srcH, float w, float h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        decoder.setSize((int) w, (int) h);
        main.post(() -> window.setVideoSize((int) w, (int) h));
        AppLog.i(TAG, "video " + srcW + "x" + srcH + " -> " + w + "x" + h);
    }

    @Override
    public void onMirrorRunning(boolean running) {
        firstFrame = running;
        if (!running) {
            decoder.release();
        }
        main.post(running ? this::mirrorStarted : this::mirrorStopped);
    }

    @Override
    public void onAudioFormat(int ct, int spf, boolean usingScreen) {
        if (handle == 0) {
            return;
        }
        NativeBridge.nativeServerAudioStart(handle);
        NativeBridge.nativeServerAudioFormat(handle, ct, spf);
    }

    @Override
    public void onAudioTeardown() {
    }

    @Override
    public void onConnectionInit() {
        connections.incrementAndGet();
        main.post(() -> {
            if (!mirroring) {
                setState(getString(R.string.airplay_state_connected));
            }
        });
    }

    @Override
    public void onConnectionDestroy() {
        if (connections.updateAndGet(n -> Math.max(0, n - 1)) == 0) {
            if (handle != 0) {
                NativeBridge.nativeServerAudioStop(handle);
            }
            main.post(() -> {
                if (mirroring) {
                    mirrorStopped();
                } else {
                    setState(getString(R.string.airplay_state_waiting, name));
                }
            });
        }
    }

    @Override
    public void onVolumeChange(float volume) {
        // the car's own volume knob stays in charge
    }

    @Override
    public float onClientVolume() {
        return 0f;
    }

    @Override
    public void onConnectionReset(int reason) {
        AppLog.i(TAG, "connection reset " + reason);
    }

    @Override
    public void onDisplayPin(String pin) {
    }

    @Override
    public void onMetadata(byte[] data) {
    }

    @Override
    public void onCoverArt(byte[] data) {
    }

    @Override
    public void onProgress(long start, long curr, long end) {
    }

    @Override
    public void onDacpId(String dacpId, String activeRemote) {
    }

    @Override
    public void onVideoPlay(String location, float startPositionSeconds) {
    }

    @Override
    public void onVideoScrub(float positionSeconds) {
    }

    @Override
    public void onVideoRate(float rate) {
    }

    @Override
    public void onVideoStop() {
    }

    @Override
    public void onVideoSessionPoll() {
    }

    @Override
    public void onLog(String message) {
        AppLog.d(TAG, message);
    }

    // ---- helpers ----

    private void setState(String text) {
        state = text;
    }

    private static int parse(@Nullable String value) {
        try {
            return value == null ? 0 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Landscape, at most 1920×1080 so the car's decoder keeps up. */
    private int[] displaySize() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = Math.max(dm.widthPixels, dm.heightPixels);
        int h = Math.min(dm.widthPixels, dm.heightPixels);
        if (w > 1920) {
            h = Math.round(h * 1920f / w);
            w = 1920;
        }
        if (h > 1080) {
            w = Math.round(w * 1080f / h);
            h = 1080;
        }
        return new int[] {w & ~1, h & ~1};
    }

    /** The Wi-Fi MAC when Android shows it, else a random one kept for this install. */
    private byte[] hwAddr() {
        try {
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String n = iface.getName();
                if (n.startsWith("wlan") || n.startsWith("eth")) {
                    byte[] mac = iface.getHardwareAddress();
                    if (usable(mac)) {
                        return mac;
                    }
                }
            }
        } catch (Exception e) {
            AppLog.w(TAG, "no hardware address: " + e);
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = prefs.getString(KEY_MAC, null);
        if (saved != null && saved.length() == 12) {
            byte[] mac = new byte[6];
            for (int i = 0; i < 6; i++) {
                mac[i] = (byte) Integer.parseInt(saved.substring(i * 2, i * 2 + 2), 16);
            }
            return mac;
        }
        byte[] mac = new byte[6];
        new SecureRandom().nextBytes(mac);
        mac[0] = (byte) ((mac[0] & 0xF0) | 0x0A); // locally administered, unicast
        StringBuilder sb = new StringBuilder();
        for (byte b : mac) {
            sb.append(String.format("%02x", b));
        }
        prefs.edit().putString(KEY_MAC, sb.toString()).apply();
        return mac;
    }

    private static boolean usable(@Nullable byte[] mac) {
        if (mac == null || mac.length != 6) {
            return false;
        }
        boolean allZero = true;
        boolean placeholder = mac[0] == 0x02;
        for (int i = 0; i < 6; i++) {
            if (mac[i] != 0) {
                allZero = false;
            }
            if (i > 0 && mac[i] != 0) {
                placeholder = false;
            }
        }
        return !allZero && !placeholder;
    }

    private void promote() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, getString(R.string.airplay_title), NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            manager.createNotificationChannel(channel);
        }
        Intent page = new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_PAGE, MainActivity.PAGE_AIRPLAY);
        PendingIntent open = PendingIntent.getActivity(this, 0, page,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification note = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_app)
                .setContentTitle(getString(R.string.airplay_title))
                .setContentText(getString(R.string.airplay_notification))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, note);
        }
    }
}
