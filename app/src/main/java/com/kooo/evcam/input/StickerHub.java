package com.kooo.evcam.input;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.UserExit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Smart stickers: turns a press into a shortcut and owns the Bluetooth radio for them.
 *
 * <p>ZEEKR_button stickers are never connected: they are added from their signal and their
 * presses are read from their broadcasts by {@link StickerBeacon}. A GATT link is made only to
 * set up another kind of button (the sticker screen's Connect). This class decides when the
 * broadcast scan may run (no setup link, no sticker screen holding the radio, Bluetooth on).</p>
 *
 * <p>All state lives on one thread ({@code sticker-gatt}, where GATT callbacks also land).
 * One dial at a time, always direct (see {@link StickerRecover}). Every link phase has a
 * watchdog, so a setup link is either ready or torn down.</p>
 */
public final class StickerHub {

    public static final int FAIL_BLUETOOTH = 1;
    public static final int FAIL_TIMEOUT = 2;
    public static final int FAIL_NO_SERVICE = 3;
    public static final int FAIL_GATT = 4;

    public static final int PHASE_PAIRING = 1;
    public static final int PHASE_CONNECTING = 2;
    public static final int PHASE_DISCOVERING = 3;

    /** Open while a sticker screen or the float is visible. */
    public interface Watch {
        void onState(String address, boolean connected);

        void onPacket(String address, byte[] raw);

        void onReady(String address);

        void onFail(String address, int reason, int status);

        void onLog(String line);

        void onRssi(String address, int rssi);

        void onPhase(String address, int phase);
    }

    private static final String TAG = "StickerHub";

    private enum Phase { IDLE, DIALING, DISCOVERING, SUBSCRIBING, READY }

    private static final class Link {
        final String mac;
        Phase phase = Phase.IDLE;
        BluetoothGatt gatt;
        int attempt;
        long dueAt;
        long lastDial;
        /** For the trace: when the link came up, became ready, and last carried a press. */
        long connectedAt;
        long readyAt;
        long lastPressAt;
        Runnable watchdog;
        ArrayDeque<BluetoothGattCharacteristic> subscribe;

        Link(String mac) {
            this.mac = mac;
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /**
     * The sticker thread. Replaceable: a Bluetooth call that hangs while the car's radio sleeps
     * and wakes would otherwise stall every sticker job (and the BLE tap) until the app restarts.
     */
    private static volatile HandlerThread thread = newThread();
    private static volatile Handler G = new Handler(thread.getLooper());

    private static HandlerThread newThread() {
        HandlerThread t = new HandlerThread("sticker-gatt");
        t.start();
        return t;
    }

    // ---- thread watchdog (main thread)
    private static final long WATCH_EVERY_MS = 15_000L;
    private static final long STUCK_AFTER_MS = 30_000L;
    private static volatile long threadBeat = SystemClock.elapsedRealtime();
    private static boolean pingPending;
    private static long pingSentAt;
    private static boolean watching;

    /** Main thread, every 15 s: is the sticker thread still answering? */
    private static final Runnable WATCH = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.elapsedRealtime();
            if (pingPending && now - pingSentAt > STUCK_AFTER_MS) {
                replaceThread(now - pingSentAt);
            } else {
                healIfOff(now, false);
            }
            if (!pingPending) {
                pingPending = true;
                pingSentAt = now;
                G.post(() -> {
                    threadBeat = SystemClock.elapsedRealtime();
                    MAIN.post(() -> pingPending = false);
                });
            }
            MAIN.postDelayed(this, WATCH_EVERY_MS);
        }
    };

    /** BLE off with no reason for this long: restart it as if BLE had been tapped. */
    private static final long OFF_TOO_LONG_MS = 45_000L;
    /** Never auto-restart more often than this. */
    private static final long AUTO_RESTART_EVERY_MS = 120_000L;
    private static long offSince;
    private static long lastAutoRestart;

    /**
     * Main thread. Listening is off but nothing should keep it off: stickers are saved,
     * Bluetooth is on, the app was not quit, and no sticker screen is open (those pause it on
     * purpose). After 45 s of that, or at once when the app comes back to the front, restart it.
     */
    private static void healIfOff(long now, boolean appCameBack) {
        Context context = app;
        if (context == null || StickerBeacon.status() != BEACON_OFF) {
            offSince = 0;
            return;
        }
        if (stopScan != null || UserExit.isExited(context)
                || StickerDevices.parse(new AppConfig(context).getStickerDevices()).isEmpty()) {
            offSince = 0;
            return;
        }
        BluetoothAdapter radio = adapter(context);
        if (radio == null || !radio.isEnabled()) {
            offSince = 0;
            return;
        }
        if (offSince == 0) {
            offSince = now;
        }
        boolean due = appCameBack || now - offSince >= OFF_TOO_LONG_MS;
        if (!due || now - lastAutoRestart < AUTO_RESTART_EVERY_MS) {
            return;
        }
        lastAutoRestart = now;
        StickerLog.line("auto restart: BLE off " + (now - offSince) / 1000 + "s with no reason"
                + (appCameBack ? " (app came back)" : ""));
        offSince = 0;
        restartListening(context);
    }

    private static void startWatch() {
        MAIN.post(() -> {
            if (!watching) {
                watching = true;
                MAIN.postDelayed(WATCH, WATCH_EVERY_MS);
            }
        });
    }

    /**
     * The sticker thread has not answered for {@code stuckMs}: start a fresh one. The old one is
     * told to quit (whatever it is stuck in finishes on its own); its links are dropped, not
     * closed, since closing them is what might hang again. Main thread.
     */
    private static void replaceThread(long stuckMs) {
        HandlerThread old = thread;
        thread = newThread();
        G = new Handler(thread.getLooper());
        pingPending = false;
        try {
            old.quit();
        } catch (RuntimeException ignored) {
            // already gone
        }
        StickerLog.line("sticker thread stuck " + stuckMs / 1000 + "s: replaced, listening restarts");
        G.post(() -> {
            LINKS.clear();
            UP.clear();
            PROBING.clear();
            PROBE_AT.clear();
            priority = "";
            scanHold = false;
            if (app != null) {
                BluetoothAdapter adapter = adapter(app);
                radioOff = adapter == null || !adapter.isEnabled();
            }
            lastBeaconWhy = "";
            reconcile();
        });
        StickerBeacon.forceRestart();
    }

    // ---- GATT-thread state
    private static final Map<String, Link> LINKS = new LinkedHashMap<>();
    /** Tapped Connect on the sticker screen: wanted even before it is saved. */
    private static final Set<String> PROBING = new HashSet<>();
    private static Context app;
    private static boolean scanHold;
    private static boolean radioOff;
    private static boolean receiverRegistered;
    private static String priority = "";
    private static String lastWanted = "";
    private static final Runnable PUMP = StickerHub::pump;
    private static final Runnable AUDIT = StickerHub::audit;

    // ---- read from any thread
    private static final Set<String> UP = ConcurrentHashMap.newKeySet();
    private static final List<Watch> WATCHES = new CopyOnWriteArrayList<>();
    private static volatile boolean capture;
    private static volatile boolean inFront;
    private static volatile Runnable stopScan;

    private StickerHub() {
    }

    // =================================================================== public API

    public static void addWatch(Watch watch) {
        if (watch != null && !WATCHES.contains(watch)) {
            WATCHES.add(watch);
        }
    }

    public static void removeWatch(Watch watch) {
        WATCHES.remove(watch);
    }

    public static void setCapture(boolean on) {
        capture = on;
    }

    /** The sticker screen registers this so a dial can stop its scan first. */
    public static void setScanStop(Runnable stop) {
        stopScan = stop;
    }

    /** Live GATT link, not merely a saved address. */
    public static boolean isUp(String address) {
        String mac = StickerDevices.normalize(address);
        return UP.contains(mac) || StickerBeacon.isHeard(mac);
    }

    /** Connect saved stickers, drop removed ones. Safe to call often. */
    public static void sync(Context context) {
        if (context == null) {
            return;
        }
        Context application = context.getApplicationContext();
        G.post(() -> {
            attach(application);
            reconcile();
        });
    }

    /** App came to the front or left. Front: a waiting setup connect is dialled now. */
    public static void setAppInFront(Context context, boolean on) {
        if (context == null) {
            return;
        }
        boolean entered = on && !inFront;
        boolean changed = on != inFront;
        inFront = on;
        Context application = context.getApplicationContext();
        if (entered) {
            MAIN.postDelayed(() -> healIfOff(SystemClock.elapsedRealtime(), true), 5_000L);
        }
        G.post(() -> {
            attach(application);
            if (changed) {
                trace(on ? "app front" : "app back");
            }
            for (Link link : LINKS.values()) {
                if (entered && link.phase == Phase.IDLE) {
                    link.dueAt = 0;
                }
            }
            reconcile();
        });
    }

    /** Recording float shown or hidden. */
    public static void setFloatingShown(Context context, boolean on) {
        if (context == null) {
            return;
        }
        sync(context);
    }

    /**
     * Free the radio for discovery: this head unit answers neither classic discovery nor an
     * LE scan while a sticker link is up. {@code on} drops every link; {@code off} redials.
     */
    public static void holdForScan(Context context, boolean on) {
        if (context == null) {
            return;
        }
        Context application = context.getApplicationContext();
        G.post(() -> {
            attach(application);
            if (on == scanHold) {
                return;
            }
            scanHold = on;
            scanHoldAt = SystemClock.elapsedRealtime();
            G.removeCallbacks(HOLD_EXPIRE);
            if (on) {
                G.postDelayed(HOLD_EXPIRE, HOLD_MAX_MS);
            }
            trace(on ? "scan hold: drop all links" : "scan done: redial");
            if (on) {
                for (Link link : LINKS.values()) {
                    teardown(link, false, true);
                }
                reconcile();
                return;
            }
            for (Link link : LINKS.values()) {
                link.attempt = 0;
                link.dueAt = 0;
            }
            reconcile();
        });
    }

    /** User tapped Connect on a sticker (saved or not). Dial it next. */
    public static void probe(Context context, String address) {
        if (context == null) {
            return;
        }
        Context application = context.getApplicationContext();
        String mac = StickerDevices.normalize(address);
        G.post(() -> {
            attach(application);
            if (mac.isEmpty()) {
                return;
            }
            PROBING.add(mac);
            PROBE_AT.put(mac, SystemClock.elapsedRealtime());
            priority = mac;
            trace("probe " + mac + " (user tapped Connect)");
            Link link = link(mac);
            if (link.phase != Phase.READY) {
                teardown(link, false, false);
                link.attempt = 0;
                // Let the scan page's scan wind down first.
                link.dueAt = SystemClock.elapsedRealtime() + 700;
            }
            reconcile();
        });
    }

    /** Ask the open link for a fresh signal. */
    public static void readRssi(String address) {
        String mac = StickerDevices.normalize(address);
        G.post(() -> {
            Link link = LINKS.get(mac);
            if (link == null || link.gatt == null || link.phase == Phase.DIALING) {
                return;
            }
            try {
                link.gatt.readRemoteRssi();
            } catch (SecurityException ignored) {
                // permission dropped
            }
        });
    }

    /** Forgotten by the user. Its address is already out of the saved list. */
    public static void disconnect(String address) {
        String mac = StickerDevices.normalize(address);
        G.post(() -> {
            PROBING.remove(mac);
            Link link = LINKS.remove(mac);
            trace("forget " + mac);
            if (link != null) {
                teardown(link, false, true);
            }
            pump();
        });
    }

    // =================================================================== scheduling

    private static void attach(Context application) {
        app = application;
        if (!receiverRegistered) {
            startWatch();
            StickerLog.attach(application);
            trace("---- start " + Build.MANUFACTURER + " " + Build.MODEL + " sdk=" + Build.VERSION.SDK_INT
                    + " app=" + com.kooo.evcam.BuildConfig.VERSION_NAME);
            IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) {
                application.registerReceiver(RADIO, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                application.registerReceiver(RADIO, filter);
            }
            receiverRegistered = true;
            BluetoothAdapter adapter = adapter(application);
            radioOff = adapter == null || !adapter.isEnabled();
        }
    }

    /** Bluetooth switched off: drop links quietly. Back on: redial everyone now. */
    private static final BroadcastReceiver RADIO = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            G.post(() -> {
                if (state == BluetoothAdapter.STATE_ON) {
                    radioOff = false;
                    for (Link link : LINKS.values()) {
                        link.attempt = 0;
                        link.dueAt = 0;
                    }
                    log("bluetooth on");
                    reconcile();
                } else if (state == BluetoothAdapter.STATE_TURNING_OFF
                        || state == BluetoothAdapter.STATE_OFF) {
                    if (!radioOff) {
                        log("bluetooth off");
                    }
                    radioOff = true;
                    for (Link link : LINKS.values()) {
                        teardown(link, false, true);
                    }
                    reconcile();
                }
            });
        }
    };

    /** Bring the link set in line with what is wanted, then dial whoever is due. */
    private static void reconcile() {
        if (app == null) {
            return;
        }
        Set<String> wanted = wanted();
        String now = new java.util.TreeSet<>(wanted).toString();
        if (!now.equals(lastWanted)) {
            lastWanted = now;
            trace("wanted " + now);
        }
        for (String mac : new ArrayList<>(LINKS.keySet())) {
            if (!wanted.contains(mac)) {
                teardown(LINKS.remove(mac), false, true);
            }
        }
        for (String mac : wanted) {
            link(mac);
        }
        Set<String> beacons = beaconed();
        boolean allowed = beaconAllowed();
        String why = allowed ? "listening allowed" : "listening off: " + beaconBlockers();
        if (!why.equals(lastBeaconWhy)) {
            lastBeaconWhy = why;
            trace(why + " saved=" + beacons.size());
        }
        StickerBeacon.sync(app, beacons, allowed, !WATCHES.isEmpty(),
                scanHold || !PROBING.isEmpty());
        pump();
        G.removeCallbacks(AUDIT);
        if (!wanted.isEmpty() || !beacons.isEmpty()) {
            G.postDelayed(AUDIT, StickerRecover.AUDIT_MS);
        }
    }

    /** Safety net: whatever went wrong, an idle wanted sticker gets dialled again. */
    private static void audit() {
        expireProbes();
        // This car does not reliably deliver the Bluetooth on/off broadcast (none ever reached the
        // trace), so a missed "on" must not leave every sticker parked after the car wakes up.
        BluetoothAdapter adapter = app == null ? null : adapter(app);
        boolean on = adapter != null && adapter.isEnabled();
        if (radioOff && on) {
            radioOff = false;
            for (Link link : LINKS.values()) {
                link.attempt = 0;
                link.dueAt = 0;
            }
            log("bluetooth back on (polled)");
        } else if (!radioOff && !on) {
            radioOff = true;
            log("bluetooth off (polled)");
            for (Link link : LINKS.values()) {
                teardown(link, false, true);
            }
        }
        reconcile();
    }

    private static Set<String> wanted() {
        Set<String> out = new HashSet<>();
        if (app == null || UserExit.isExited(app)) {
            return out;
        }
        // Saved stickers are read from their broadcasts (StickerBeacon), never connected.
        // A GATT link is only made to set up a button that is not a ZEEKR_button.
        out.addAll(PROBING);
        return out;
    }

    /** Saved stickers whose broadcasts should be read. */
    private static Set<String> beaconed() {
        Set<String> out = new HashSet<>();
        if (app == null || UserExit.isExited(app)) {
            return out;
        }
        out.addAll(StickerDevices.parse(new AppConfig(app).getStickerDevices()));
        return out;
    }

    /** What is keeping the broadcast listening off, for the trace. */
    private static String beaconBlockers() {
        StringBuilder out = new StringBuilder();
        if (scanHold) {
            out.append("sticker screen has the radio ")
                    .append((SystemClock.elapsedRealtime() - scanHoldAt) / 1000).append("s; ");
        }
        if (radioOff) {
            out.append("bluetooth off; ");
        }
        if (!PROBING.isEmpty()) {
            out.append("setup connect ").append(PROBING).append("; ");
        }
        for (Link link : LINKS.values()) {
            if (link.phase != Phase.IDLE) {
                out.append("link ").append(link.mac).append(' ').append(link.phase).append("; ");
            }
        }
        return out.toString();
    }

    /** A sticker screen forgot to give the radio back: take it back after this long. */
    private static final long HOLD_MAX_MS = 120_000L;
    /** A setup connect that is still going after this long is abandoned. */
    private static final long PROBE_MAX_MS = 60_000L;
    private static long scanHoldAt;
    private static String lastBeaconWhy = "";
    private static final Map<String, Long> PROBE_AT = new HashMap<>();

    private static final Runnable HOLD_EXPIRE = () -> {
        if (scanHold && SystemClock.elapsedRealtime() - scanHoldAt >= HOLD_MAX_MS) {
            scanHold = false;
            trace("scan hold expired after " + HOLD_MAX_MS / 1000 + "s: listening again");
            reconcile();
        }
    };

    /** Drop setup connects that have been running too long (audit, every 20 s). */
    private static void expireProbes() {
        long now = SystemClock.elapsedRealtime();
        for (String mac : new ArrayList<>(PROBING)) {
            Long at = PROBE_AT.get(mac);
            if (at == null || now - at >= PROBE_MAX_MS) {
                PROBING.remove(mac);
                PROBE_AT.remove(mac);
                if (mac.equals(priority)) {
                    priority = "";
                }
                trace("setup connect " + mac + " given up after " + PROBE_MAX_MS / 1000 + "s");
            }
        }
    }

    /**
     * The user tapped the BLE label: drop anything holding the radio and start listening fresh.
     * Recovers every stuck state without quitting the app.
     */
    public static void restartListening(Context context) {
        if (context == null) {
            return;
        }
        Context application = context.getApplicationContext();
        // On the main thread first, so the tap works even if the sticker thread is stuck: say
        // what state everything is in, then start listening for the saved stickers right away.
        StickerLog.attach(application);
        BluetoothAdapter radio = adapter(application);
        boolean on = radio != null && radio.isEnabled();
        boolean exited = UserExit.isExited(application);
        java.util.Set<String> saved = new HashSet<>(
                StickerDevices.parse(new AppConfig(application).getStickerDevices()));
        StickerLog.line("BLE tapped: bluetooth=" + (on ? "on" : "off") + " exited=" + exited
                + " saved=" + saved.size() + " scanHold=" + scanHold + " radioOff=" + radioOff
                + " probing=" + PROBING.size() + " links=" + LINKS.size()
                + " threadAnswered=" + (SystemClock.elapsedRealtime() - threadBeat) / 1000 + "s ago"
                + " beacon=" + StickerBeacon.status());
        if (on && !exited) {
            StickerBeacon.sync(application, saved, true, !WATCHES.isEmpty(), false);
        }
        StickerBeacon.forceRestart();
        G.post(() -> {
            attach(application);
            trace("restart listening (tapped BLE)");
            scanHold = false;
            G.removeCallbacks(HOLD_EXPIRE);
            PROBING.clear();
            PROBE_AT.clear();
            priority = "";
            for (String mac : new ArrayList<>(LINKS.keySet())) {
                teardown(LINKS.remove(mac), false, true);
            }
            BluetoothAdapter adapter = adapter(application);
            radioOff = adapter == null || !adapter.isEnabled();
            lastBeaconWhy = "";
            reconcile();
            StickerBeacon.forceRestart();
        });
    }

    /** The broadcast scan may run only when nothing else needs the radio. */
    private static boolean beaconAllowed() {
        if (scanHold || radioOff || !PROBING.isEmpty()) {
            return false;
        }
        for (Link link : LINKS.values()) {
            if (link.phase != Phase.IDLE) {
                return false;
            }
        }
        return true;
    }

    /** Dial the next due sticker if the radio is free; otherwise wake when someone is due. */
    private static void pump() {
        G.removeCallbacks(PUMP);
        if (app == null || scanHold || radioOff || !canConnect(app)) {
            return;
        }
        boolean busy = false;
        List<String> idle = new ArrayList<>();
        Map<String, Long> dueAt = new HashMap<>();
        Map<String, Long> lastDial = new HashMap<>();
        for (Link link : LINKS.values()) {
            if (link.phase == Phase.DIALING || link.phase == Phase.DISCOVERING
                    || link.phase == Phase.SUBSCRIBING) {
                busy = true;
            } else if (link.phase == Phase.IDLE) {
                idle.add(link.mac);
                dueAt.put(link.mac, link.dueAt);
                lastDial.put(link.mac, link.lastDial);
            }
        }
        long now = SystemClock.elapsedRealtime();
        String next = StickerRecover.next(idle, dueAt, lastDial, priority, busy, now);
        if (!next.isEmpty()) {
            dial(LINKS.get(next));
            return;
        }
        if (!busy) {
            long wait = StickerRecover.untilNextDue(idle, dueAt, now);
            if (wait >= 0) {
                G.postDelayed(PUMP, Math.max(50, wait));
            }
        }
    }

    // =================================================================== link lifecycle

    private static Link link(String mac) {
        Link link = LINKS.get(mac);
        if (link == null) {
            link = new Link(mac);
            LINKS.put(mac, link);
        }
        return link;
    }

    private static void dial(Link link) {
        BluetoothAdapter adapter = adapter(app);
        if (adapter == null || !adapter.isEnabled()) {
            radioOff = true;
            fail(link, FAIL_BLUETOOTH, 0);
            return;
        }
        BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(link.mac);
        } catch (IllegalArgumentException e) {
            AppLog.w(TAG, "bad address " + link.mac);
            LINKS.remove(link.mac);
            PROBING.remove(link.mac);
            return;
        }
        releaseRadio(adapter);
        link.lastDial = SystemClock.elapsedRealtime();
        try {
            // A bond stuck half-way holds the radio and connectGatt never answers.
            if (device.getBondState() == BluetoothDevice.BOND_BONDING) {
                cancelBondProcess(device);
                log("cancel stuck bond " + link.mac);
                link.dueAt = link.lastDial + 600;
                phase(link.mac, PHASE_PAIRING);
                pump();
                return;
            }
            link.phase = Phase.DIALING;
            phase(link.mac, PHASE_CONNECTING);
            link.gatt = device.connectGatt(app, false, callback(link.mac),
                    BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M, G);
        } catch (SecurityException e) {
            link.phase = Phase.IDLE;
            fail(link, FAIL_BLUETOOTH, 0);
            return;
        }
        if (link.gatt == null) {
            link.phase = Phase.IDLE;
            fail(link, FAIL_GATT, -1);
            return;
        }
        if (link.attempt < 3 || link.attempt % 20 == 0) {
            log("dial " + link.mac + " try " + (link.attempt + 1));
        } else {
            trace("dial " + link.mac + " try " + (link.attempt + 1));
        }
        arm(link, StickerRecover.CONNECT_TIMEOUT_MS, FAIL_TIMEOUT);
    }

    private static BluetoothGattCallback callback(String mac) {
        return new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                Link link = current(mac, gatt);
                if (link == null) {
                    return;
                }
                if (newState == BluetoothGatt.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    link.connectedAt = SystemClock.elapsedRealtime();
                    trace("connected " + mac + " after " + (link.connectedAt - link.lastDial) + "ms try "
                            + (link.attempt + 1) + " bond=" + bond(gatt));
                    link.phase = Phase.DISCOVERING;
                    UP.add(mac);
                    try {
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                    } catch (SecurityException ignored) {
                        // permission dropped
                    }
                    state(mac, true);
                    phase(mac, PHASE_DISCOVERING);
                    boolean started;
                    try {
                        started = gatt.discoverServices();
                    } catch (SecurityException e) {
                        started = false;
                    }
                    if (!started) {
                        fail(link, FAIL_GATT, status);
                        return;
                    }
                    arm(link, StickerRecover.DISCOVER_TIMEOUT_MS, FAIL_TIMEOUT);
                    return;
                }
                // Disconnected, or a connect that came back with an error.
                boolean wasReady = link.phase == Phase.READY;
                long now = SystemClock.elapsedRealtime();
                if (wasReady) {
                    log("drop " + mac + " status=" + status + " (" + statusName(status) + ")"
                            + " up=" + secs(now - link.readyAt)
                            + " lastPress=" + (link.lastPressAt == 0 ? "never" : secs(now - link.lastPressAt) + " ago")
                            + " app=" + (inFront ? "front" : "back"));
                } else {
                    trace("drop " + mac + " status=" + status + " (" + statusName(status) + ") in "
                            + link.phase + " newState=" + newState);
                }
                if (wasReady) {
                    // A setup link went before it was dropped on purpose: a dial again while the
                    // sticker still advertises, if it is still wanted.
                    link.attempt = 0;
                    teardown(link, StickerRecover.refreshCache(status), true);
                    link.dueAt = SystemClock.elapsedRealtime() + StickerRecover.backoffMs(0);
                    pump();
                } else {
                    fail(link, FAIL_GATT, status);
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                Link link = current(mac, gatt);
                if (link == null || link.phase != Phase.DISCOVERING) {
                    return;
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail(link, FAIL_GATT, status);
                    return;
                }
                if (SERVICES_LOGGED.add(mac)) {
                    traceServices(mac, gatt);
                }
                List<BluetoothGattCharacteristic> notifies = findNotifies(gatt);
                StringBuilder chosen = new StringBuilder();
                for (BluetoothGattCharacteristic c : notifies) {
                    chosen.append(' ').append(shortUuid(c.getUuid()));
                }
                trace("subscribe " + mac + ":" + chosen);
                trace("discovered " + mac + " in " + (SystemClock.elapsedRealtime() - link.connectedAt)
                        + "ms notify=" + notifies.size());
                if (notifies.isEmpty()) {
                    traceServices(mac, gatt);
                    teardown(link, true, true);
                    link.dueAt = SystemClock.elapsedRealtime() + StickerRecover.NO_SERVICE_MS;
                    report(mac, FAIL_NO_SERVICE, 0);
                    pump();
                    return;
                }
                link.phase = Phase.SUBSCRIBING;
                link.subscribe = new ArrayDeque<>(notifies);
                subscribeNext(link);
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                Link link = current(mac, gatt);
                if (link == null || link.phase != Phase.SUBSCRIBING) {
                    return;
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    log("cccd " + descriptor.getCharacteristic().getUuid() + " status=" + status);
                }
                subscribeNext(link);
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                packet(mac, characteristic.getUuid(), characteristic.getValue());
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic,
                                                byte[] value) {
                packet(mac, characteristic.getUuid(), value == null ? characteristic.getValue() : value);
            }

            @Override
            public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    return;
                }
                MAIN.post(() -> {
                    for (Watch watch : WATCHES) {
                        watch.onRssi(mac, rssi);
                    }
                });
            }
        };
    }

    /** The link this callback belongs to, or null for a callback from a gatt we already closed. */
    private static Link current(String mac, BluetoothGatt gatt) {
        Link link = LINKS.get(mac);
        if (link == null || link.gatt != gatt) {
            try {
                gatt.close();
            } catch (SecurityException ignored) {
                // already gone
            }
            return null;
        }
        return link;
    }

    private static void subscribeNext(Link link) {
        while (link.subscribe != null && !link.subscribe.isEmpty()) {
            BluetoothGattCharacteristic next = link.subscribe.poll();
            if (subscribe(link.gatt, next)) {
                arm(link, StickerRecover.SUBSCRIBE_TIMEOUT_MS, FAIL_TIMEOUT);
                return;
            }
        }
        ready(link);
    }

    /** @return true when a descriptor write is in flight */
    private static boolean subscribe(BluetoothGatt gatt, BluetoothGattCharacteristic notify) {
        try {
            gatt.setCharacteristicNotification(notify, true);
            BluetoothGattDescriptor cccd = notify.getDescriptor(StickerMatch.CCCD);
            if (cccd == null) {
                log("notify without cccd " + notify.getUuid());
                return false;
            }
            int props = notify.getProperties();
            boolean indicate = (props & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    && (props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0;
            byte[] value = indicate
                    ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            if (Build.VERSION.SDK_INT >= 33) {
                return gatt.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS;
            }
            cccd.setValue(value);
            return gatt.writeDescriptor(cccd);
        } catch (SecurityException e) {
            log("notify denied");
            return false;
        }
    }

    private static void ready(Link link) {
        disarm(link);
        link.phase = Phase.READY;
        link.attempt = 0;
        link.subscribe = null;
        if (link.mac.equals(priority)) {
            priority = "";
        }
        PROBING.remove(link.mac);
        PROBE_AT.remove(link.mac);
        // Setup is done: let the screen save it, then drop the link and read broadcasts.
        G.postDelayed(StickerHub::reconcile, 1500);
        link.readyAt = SystemClock.elapsedRealtime();
        link.lastPressAt = 0;
        log("ready " + link.mac + " in " + (link.readyAt - link.lastDial) + "ms bond=" + bond(link.gatt));
        String mac = link.mac;
        Context context = app;
        MAIN.post(() -> {
            if (context != null) {
                StickerArrived.show(context, mac);
            }
            for (Watch watch : WATCHES) {
                watch.onReady(mac);
            }
        });
        pump();
    }

    /** A dial, discovery or subscribe that went wrong: tear down, back off, try again. */
    private static void fail(Link link, int reason, int status) {
        // Timeouts are the sticker asleep, not a bad cache: keep the cache so reconnect is quick.
        teardown(link, StickerRecover.refreshCache(status), true);
        link.attempt++;
        link.dueAt = SystemClock.elapsedRealtime() + StickerRecover.backoffMs(link.attempt);
        // Every ~3 s while asleep; log only now and then.
        if (link.attempt <= 3 || link.attempt % 20 == 0) {
            AppLog.w(TAG, "sticker " + link.mac + " fail reason=" + reason + " status=" + status
                    + " try=" + link.attempt);
        }
        trace("fail " + link.mac + " reason=" + reasonName(reason) + " status=" + status
                + " try=" + link.attempt);
        boolean probing = PROBING.contains(link.mac);
        if (probing || StickerRecover.reportFailure(link.attempt)) {
            report(link.mac, reason, status);
        }
        // A sticker the user tapped Connect on gets three tries, then stops. Saved ones too:
        // a probe that never ended kept the broadcast listening off (BLE grey) until restart.
        if (probing && link.attempt >= 3) {
            PROBING.remove(link.mac);
            LINKS.remove(link.mac);
            if (link.mac.equals(priority)) {
                priority = "";
            }
        }
        pump();
    }

    /**
     * Close the gatt and go idle. Does not reschedule; callers set {@code dueAt} and pump.
     *
     * @param refresh clear the GATT cache first (stale cache keeps failing discovery)
     * @param notify  tell watchers the link is down
     */
    private static void teardown(Link link, boolean refresh, boolean notify) {
        if (link == null) {
            return;
        }
        disarm(link);
        boolean wasUp = UP.remove(link.mac);
        BluetoothGatt gatt = link.gatt;
        link.gatt = null;
        link.phase = Phase.IDLE;
        link.subscribe = null;
        if (gatt != null) {
            try {
                if (refresh) {
                    refreshCache(gatt);
                }
                gatt.disconnect();
                gatt.close();
            } catch (SecurityException ignored) {
                // permission dropped
            }
        }
        if (notify && (wasUp || gatt != null)) {
            state(link.mac, false);
        }
    }

    private static void arm(Link link, long ms, int reason) {
        disarm(link);
        Runnable watchdog = () -> {
            if (LINKS.get(link.mac) == link && link.watchdog != null) {
                link.watchdog = null;
                if (link.phase == Phase.DIALING) {
                    trace("timeout DIALING " + link.mac + " (asleep or out of range)");
                } else {
                    log("timeout " + link.phase + " " + link.mac);
                }
                fail(link, reason, 0);
            }
        };
        link.watchdog = watchdog;
        G.postDelayed(watchdog, ms);
    }

    private static void disarm(Link link) {
        if (link.watchdog != null) {
            G.removeCallbacks(link.watchdog);
            link.watchdog = null;
        }
    }

    // =================================================================== presses

    private static String lastPacket = "";
    private static long lastPacketAt;

    /** GATT thread. Duplicate notifications within 80 ms are one press. */
    private static void packet(String address, java.util.UUID channel, byte[] raw) {
        if (raw == null) {
            return;
        }
        if (BATTERY_LEVEL_UUID.equals(channel)) {
            // A sticker without a button channel gets every notify channel; battery is not a press.
            return;
        }
        String key = address + " " + StickerFrame.hex(raw);
        long now = SystemClock.elapsedRealtime();
        if (key.equals(lastPacket) && now - lastPacketAt < 80L) {
            return;
        }
        lastPacket = key;
        lastPacketAt = now;
        AppLog.d(TAG, key);
        Link pressed = LINKS.get(address);
        if (pressed != null) {
            pressed.lastPressAt = now;
        }
        trace("press " + key + " ch=" + shortUuid(channel));
        MAIN.post(() -> {
            for (Watch watch : WATCHES) {
                watch.onPacket(address, raw);
            }
        });
        runShortcut(address, raw);
    }

    /** Match a press against the saved shortcuts and run it. Not while a sticker screen captures. */
    private static void runShortcut(String address, byte[] raw) {
        Context context = app;
        MAIN.post(() -> {
            if (capture || context == null) {
                return;
            }
            AppConfig config = new AppConfig(context);
            if (!linkWanted(config)) {
                return;
            }
            StickerFrame frame = StickerFrame.parse(raw);
            if (frame == null) {
                return;
            }
            List<Shortcut> items = ShortcutBook.parse(config.getButtonShortcuts());
            Shortcut hit = ShortcutBook.match(items, frame.control, 0,
                    StickerFrame.deviceName(address), frame.press);
            if (hit != null) {
                ShortcutPerformer.perform(context, hit.action);
            }
        });
    }

    // =================================================================== helpers

    private static List<BluetoothGattCharacteristic> findNotifies(BluetoothGatt gatt) {
        List<BluetoothGattCharacteristic> preferred = new ArrayList<>();
        List<BluetoothGattCharacteristic> rest = new ArrayList<>();
        List<BluetoothGattService> services = gatt.getServices();
        if (services == null) {
            return preferred;
        }
        for (BluetoothGattService service : services) {
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                int props = characteristic.getProperties();
                boolean canListen = (props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                        || (props & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
                if (!canListen) {
                    continue;
                }
                if (StickerMatch.isNotify(characteristic.getUuid())) {
                    preferred.add(characteristic);
                } else {
                    rest.add(characteristic);
                }
            }
        }
        // Only the button channel when the sticker has it. Subscribing to its HID/battery
        // channels too is suspected of making it hang up on an unpaired car.
        if (!preferred.isEmpty()) {
            return preferred;
        }
        return rest;
    }

    /** A dial while the sticker screen scans or discovery runs never answers on this radio. */
    private static void releaseRadio(BluetoothAdapter adapter) {
        Runnable stop = stopScan;
        if (stop != null) {
            MAIN.post(stop);
        }
        try {
            if (adapter.isDiscovering()) {
                adapter.cancelDiscovery();
            }
        } catch (SecurityException ignored) {
            // permission dropped
        }
    }

    /** Switch on, or a sticker shortcut already saved. */
    private static boolean linkWanted(AppConfig config) {
        if (config.isStickerEnabled()) {
            return true;
        }
        for (Shortcut item : ShortcutBook.parse(config.getButtonShortcuts())) {
            if (item != null && StickerFrame.isSticker(item.deviceName)) {
                return true;
            }
        }
        return false;
    }

    private static void cancelBondProcess(BluetoothDevice device) {
        try {
            device.getClass().getMethod("cancelBondProcess").invoke(device);
        } catch (Exception ignored) {
            // hidden API missing
        }
    }

    private static void refreshCache(BluetoothGatt gatt) {
        try {
            gatt.getClass().getMethod("refresh").invoke(gatt);
        } catch (Exception ignored) {
            // hidden API missing
        }
    }

    private static final Set<String> SERVICES_LOGGED = new HashSet<>();

    /** Full GATT table once per sticker, so the trace shows what kind of device it is. */
    private static void traceServices(String mac, BluetoothGatt gatt) {
        List<BluetoothGattService> services = gatt.getServices();
        if (services == null) {
            return;
        }
        for (BluetoothGattService service : services) {
            trace("  svc " + mac + " " + shortUuid(service.getUuid()));
            for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                trace("    chr " + shortUuid(c.getUuid()) + " props=0x" + Integer.toHexString(c.getProperties()));
            }
        }
    }

    /** 16-bit form for standard UUIDs (ffd2, 2a19), full string otherwise. */
    private static String shortUuid(java.util.UUID uuid) {
        String full = uuid.toString();
        if (full.endsWith("-0000-1000-8000-00805f9b34fb") && full.startsWith("0000")) {
            return full.substring(4, 8);
        }
        return full;
    }

    private static boolean canConnect(Context context) {
        if (Build.VERSION.SDK_INT < 31) {
            return true;
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static BluetoothAdapter adapter(Context context) {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        return manager == null ? null : manager.getAdapter();
    }

    private static void state(String address, boolean connected) {
        MAIN.post(() -> {
            for (Watch watch : WATCHES) {
                watch.onState(address, connected);
            }
        });
    }

    private static void phase(String address, int which) {
        MAIN.post(() -> {
            for (Watch watch : WATCHES) {
                watch.onPhase(address, which);
            }
        });
    }

    private static void report(String address, int reason, int status) {
        MAIN.post(() -> {
            for (Watch watch : WATCHES) {
                watch.onFail(address, reason, status);
            }
        });
    }

    /** File only: too frequent for the screen, needed to read a drop back. */
    static void trace(String line) {
        StickerLog.line(line);
    }

    /** Broadcast listening state for the float's BLE label. */
    public static final int BEACON_OFF = BeaconEngine.OFF;
    public static final int BEACON_LISTENING = BeaconEngine.LISTENING;
    public static final int BEACON_RETRYING = BeaconEngine.RETRYING;

    private static final List<Runnable> BEACON_LISTENERS = new CopyOnWriteArrayList<>();

    /** Main thread. */
    public static int beaconStatus() {
        return StickerBeacon.status();
    }

    public static void addBeaconListener(Runnable listener) {
        if (listener != null && !BEACON_LISTENERS.contains(listener)) {
            BEACON_LISTENERS.add(listener);
        }
    }

    public static void removeBeaconListener(Runnable listener) {
        BEACON_LISTENERS.remove(listener);
    }

    /** Main thread, from StickerBeacon. */
    static void beaconStatusChanged() {
        for (Runnable listener : BEACON_LISTENERS) {
            listener.run();
        }
    }

    /** A press read from a broadcast. Main thread. Same path as a GATT press. */
    static void beaconPress(String address, byte[] raw) {
        for (Watch watch : WATCHES) {
            watch.onPacket(address, raw);
        }
        runShortcut(address, raw);
    }

    /** Battery level channel: a button without a button channel notifies it; it is not a press. */
    private static final java.util.UUID BATTERY_LEVEL_UUID =
            java.util.UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");

    /** A button started or stopped being heard on air. Main thread. */
    static void beaconState(String address, boolean heard) {
        for (Watch watch : WATCHES) {
            watch.onState(address, heard);
        }
    }

    private static String secs(long ms) {
        return String.format(java.util.Locale.US, "%.1fs", ms / 1000f);
    }

    private static String bond(BluetoothGatt gatt) {
        try {
            int state = gatt.getDevice().getBondState();
            return state == BluetoothDevice.BOND_BONDED ? "bonded"
                    : state == BluetoothDevice.BOND_BONDING ? "bonding" : "none";
        } catch (SecurityException e) {
            return "?";
        }
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case FAIL_BLUETOOTH: return "bluetooth";
            case FAIL_TIMEOUT: return "timeout";
            case FAIL_NO_SERVICE: return "no-service";
            default: return "gatt";
        }
    }

    /** HCI/GATT disconnect reasons, so the trace says who hung up. */
    static String statusName(int status) {
        switch (status) {
            case 0: return "ok";
            case 8: return "link timeout";
            case 19: return "sticker hung up";
            case 22: return "car hung up";
            case 34: return "LMP timeout";
            case 59: return "unacceptable params";
            case 61: return "MIC failure";
            case 62: return "failed to establish";
            case 133: return "gatt error";
            case 257: return "gatt failure";
            default: return "status " + status;
        }
    }

    private static void log(String line) {
        AppLog.d(TAG, line);
        StickerLog.line(line);
        MAIN.post(() -> {
            for (Watch watch : WATCHES) {
                watch.onLog(line);
            }
        });
    }
}
