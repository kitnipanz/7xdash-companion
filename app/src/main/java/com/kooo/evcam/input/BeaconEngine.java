package com.kooo.evcam.input;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads presses straight from the ZEEKR_button broadcasts, with no connection at all.
 *
 * <p>After each press the button broadcasts about once a second for ~15 s, non-connectable,
 * then goes quiet. Its data carries a press counter (cnt) that moves once per press and the
 * press kind (y: 00 single, 02 double). So a changed cnt is a press. Connecting was never needed:
 * the GATT link hung up 15 s after each press and lost the press that woke it.</p>
 *
 * <p>This radio cannot scan and connect at the same time, so {@link StickerHub} decides
 * when the scan may run (no sticker screen scanning, no setup connection, Bluetooth on).</p>
 *
 * <p>Runs in the ":ble" process ({@link BeaconService}), never in the app's own. After
 * Bluetooth went off and on, every scan this head unit started in the same process failed with
 * code 3 until the app was restarted (car log 2026-10-17). So after
 * {@link #GIVE_UP_AFTER} failures in a row this process is thrown away and a fresh one started.
 * Main thread of that process only.</p>
 */
final class BeaconEngine {

    /** What the engine reports; {@link BeaconService} forwards it to the app's process. */
    interface Out {
        void trace(String line);

        void heard(String mac, boolean heard);

        void press(String mac, byte[] frame);

        void status(int status);

        /** Scanning keeps failing in this process; start a fresh one. */
        void giveUp(String why);
    }

    static final int OFF = 0;
    static final int LISTENING = 1;
    static final int RETRYING = 2;

    /** Scan failures in a row before this process is replaced. */
    static final int GIVE_UP_AFTER = 3;
    /**
     * No advert from any device for this long is logged. The car's own module alone sends about
     * ten a second, so a gap this long means the radio itself went deaf, not the stickers.
     */
    private static final long QUIET_MS = 2_000L;

    /**
     * No advert from any button for this long while scanning: restart the scan. A button is
     * quiet between presses (it broadcasts for about 15 s after each one), so this is long.
     */
    private static final long SILENT_MS = 5L * 60 * 1000;
    private static final long CHECK_MS = 10_000L;
    /** Android refuses more than five scan starts in 30 s; stay well under. */
    private static final long MIN_RESTART_MS = 8_000L;
    private static final long RETRY_MS = 5_000L;
    private static final long REFRESH_MS = 20L * 60 * 1000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** A button not heard for this long shows as down (it broadcasts ~15 s after a press). */
    private static final long HEARD_MS = 15_000L;

    private static final Map<String, Integer> LAST_CNT = new HashMap<>();
    /** Last advert time per button; read from any thread for the status dots. */
    private static final Map<String, Long> HEARD = new java.util.concurrent.ConcurrentHashMap<>();

    private static Context app;
    private static Out out;
    /** Last advert from any device; written by the scan callback thread. */
    private static volatile long lastAnyAt;
    /** The car was in Bluetooth discovery at some poll since the last advert. */
    private static volatile boolean discoverySeen;
    private static boolean discovering;
    private static BluetoothAdapter radio;
    private static final long DISCOVERY_POLL_MS = 500L;
    /**
     * Nothing at all heard for this long: the scan went deaf without an error. Seen after the
     * car's Bluetooth settings stopped discovery: 20 s and 85 s of silence until the app was
     * restarted (car log 2026-10-17). The car's own module alone sends about ten a second.
     */
    private static final long DEAF_MS = 15_000L;
    /** While it stays deaf (car asleep), restart no more often than this. */
    private static final long DEAF_RETRY_MS = 30_000L;
    private static long lastDeafRestart;
    private static BluetoothLeScanner scanner;
    /** Saved buttons being listened for. Replaced, never mutated, so the callback can read it. */
    private static volatile Set<String> scanning = new HashSet<>();
    private static boolean wantOn;
    /** A controller or shortcut screen is open: any ZEEKR_button counts, saved or not. */
    private static volatile boolean openToNew;
    /**
     * The sticker screens took the radio. Presses made there are theirs, so the next scan
     * starts from a fresh baseline instead of replaying the last one. Not after Bluetooth off:
     * the first press after the car wakes must still count.
     */
    private static boolean rebaseline;
    private static Set<String> wanted = new HashSet<>();
    private static long startedAt;
    private static long lastAdvertAt;

    private BeaconEngine() {
    }

    static void attach(Out sink) {
        out = sink;
    }

    private static void trace(String line) {
        if (out != null) {
            out.trace(line);
        }
    }

    /**
     * Run the scan for exactly these buttons, or stop it when {@code allowed} is false.
     * Safe to call often; only a change of address set restarts the scan.
     */
    static void sync(Context context, Set<String> addresses, boolean allowed, boolean anyButton,
                     boolean screenHasRadio) {
        Context application = context == null ? null : context.getApplicationContext();
        Set<String> next = new HashSet<>(addresses == null ? new HashSet<>() : addresses);
        MAIN.post(() -> {
            app = application;
            wanted = next;
            openToNew = anyButton;
            if (screenHasRadio) {
                rebaseline = true;
            }
            wantOn = allowed && (!next.isEmpty() || anyButton);
            apply();
        });
    }

    private static int lastStatus = -1;

    /** Listening, not listening, or failed and retrying. Main thread. */
    static int status() {
        if (scanner != null) {
            return LISTENING;
        }
        if (wantOn && failures > 0) {
            return RETRYING;
        }
        return OFF;
    }

    /** Tell the float when listening starts, stops or starts failing. */
    private static void notifyStatus() {
        int now = status();
        if (now != lastStatus) {
            lastStatus = now;
            if (out != null) {
                out.status(now);
            }
        }
    }

    private static void apply() {
        applyScan();
        notifyStatus();
    }

    private static void applyScan() {
        if (!wantOn) {
            stop("off");
            return;
        }
        if (scanner != null && scanning.equals(wanted)) {
            return;
        }
        stop(null);
        start();
    }

    private static void start() {
        if (app == null) {
            return;
        }
        if (!canScan(app)) {
            retryLater("no scan permission", false);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (startedAt != 0 && now - startedAt < MIN_RESTART_MS) {
            MAIN.postDelayed(BeaconEngine::apply, MIN_RESTART_MS - (now - startedAt));
            return;
        }
        BluetoothManager manager = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        BluetoothLeScanner next = adapter == null || !adapter.isEnabled() ? null : adapter.getBluetoothLeScanner();
        if (next == null) {
            retryLater("scanner not ready", false);
            return;
        }
        // Unfiltered: an address filter matched nothing on this head unit (car log 2.11.26,
        // 30 s silent while the BLE signals screen heard both buttons). seen() keeps only ours.
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setReportDelay(0)
                .build();
        // A new callback per start: reusing one across quick stop/start cycles is a known way
        // to get SCAN_FAILED_APPLICATION_REGISTRATION_FAILED (2). Old ones are ignored.
        ScanCallback fresh = newCallback();
        try {
            next.startScan(null, settings, fresh);
        } catch (SecurityException | IllegalArgumentException | IllegalStateException e) {
            retryLater("refused: " + e.getMessage(), true);
            return;
        }
        callback = fresh;
        scanner = next;
        // Not failures = 0 here: a refused scan is reported a moment later in onScanFailed, so
        // resetting here kept every retry at #1 (car log 2026-10-17). CHECK resets it instead,
        // once the scan has stayed up.
        lastAnyAt = 0;
        discoverySeen = false;
        radio = adapter;
        scanning = new HashSet<>(wanted);
        startedAt = now;
        lastAdvertAt = now;
        if (rebaseline) {
            rebaseline = false;
            countsLoaded = true;
            LAST_CNT.clear();
        } else {
            loadCounts();
        }
        trace("beacon scan on " + scanning);
        MAIN.removeCallbacks(CHECK);
        MAIN.postDelayed(CHECK, CHECK_MS);
        MAIN.removeCallbacks(REFRESH);
        MAIN.postDelayed(REFRESH, REFRESH_MS);
        MAIN.removeCallbacks(DISCOVERY);
        MAIN.post(DISCOVERY);
    }

    private static void stop(String why) {
        MAIN.removeCallbacks(CHECK);
        MAIN.removeCallbacks(REFRESH);
        MAIN.removeCallbacks(DISCOVERY);
        MAIN.removeCallbacks(RETRY);
        BluetoothLeScanner current = scanner;
        ScanCallback old = callback;
        scanner = null;
        callback = null;
        scanning = new HashSet<>();
        if (current != null && old != null) {
            try {
                current.stopScan(old);
            } catch (SecurityException | IllegalStateException ignored) {
                // Bluetooth already off
            }
        }
        // A restart keeps the dots lit; only switching the scan off clears them.
        if ("off".equals(why)) {
            for (String mac : new ArrayList<>(HEARD.keySet())) {
                HEARD.remove(mac);
                if (out != null) {
                    out.heard(mac, false);
                }
            }
        }
        if (current != null && why != null) {
            trace("beacon scan " + why);
        }
    }

    /**
     * Android quietly downgrades an unfiltered scan that runs for 30 minutes. Start a fresh one
     * before that, keeping the press counters so no press is lost or replayed across it.
     */
    private static final Runnable REFRESH = new Runnable() {
        @Override
        public void run() {
            if (scanner == null) {
                return;
            }
            stop(null);
            start();
            notifyStatus();
        }
    };

    /**
     * Is the car searching for Bluetooth devices (classic discovery)? That search takes the
     * radio from LE scans, and its usual length (5.12 s) matches the radio quiet gaps in the
     * car log of 2026-10-17. Polled: this car does not deliver the discovery broadcasts.
     */
    private static final Runnable DISCOVERY = new Runnable() {
        @Override
        public void run() {
            BluetoothAdapter adapter = radio;
            if (scanner == null || adapter == null) {
                return;
            }
            boolean now;
            try {
                now = adapter.isDiscovering();
            } catch (SecurityException e) {
                return;
            }
            if (now) {
                discoverySeen = true;
            }
            if (now != discovering) {
                discovering = now;
                trace("car bluetooth discovery " + (now ? "started" : "stopped"));
                if (!now) {
                    // The scan often stays deaf once discovery ends; a fresh one hears again.
                    restartScan("after discovery");
                    return;
                }
            }
            long at = SystemClock.elapsedRealtime();
            long heardAt = lastAnyAt == 0 ? startedAt : lastAnyAt;
            if (!now && at - heardAt > DEAF_MS && at - lastDeafRestart > DEAF_RETRY_MS) {
                lastDeafRestart = at;
                restartScan(String.format(java.util.Locale.US, "deaf %.0fs", (at - heardAt) / 1000f));
                return;
            }
            MAIN.postDelayed(this, DISCOVERY_POLL_MS);
        }
    };

    private static void restartScan(String why) {
        trace("beacon scan restart (" + why + ")");
        stop(null);
        start();
        notifyStatus();
    }

    /** The car's scanner can go quiet without telling us; restart if nothing arrives. */
    private static final Runnable CHECK = new Runnable() {
        @Override
        public void run() {
            if (scanner == null) {
                return;
            }
            // Up for a check interval without a failure: the next failure starts a new run.
            failures = 0;
            long now = SystemClock.elapsedRealtime();
            for (Map.Entry<String, Long> e : new ArrayList<>(HEARD.entrySet())) {
                if (now - e.getValue() >= HEARD_MS) {
                    HEARD.remove(e.getKey());
                    trace("beacon " + e.getKey() + " not heard for " + HEARD_MS / 1000 + "s");
                    if (out != null) {
                        out.heard(e.getKey(), false);
                    }
                }
            }
            if (now - lastAdvertAt > SILENT_MS) {
                trace("beacon scan silent " + SILENT_MS / 1000 + "s, restarting");
                stop(null);
                start();
                notifyStatus();
                return;
            }
            MAIN.postDelayed(this, CHECK_MS);
        }
    };

    private static ScanCallback callback;
    private static int failures;

    private static final Runnable RETRY = BeaconEngine::apply;

    /**
     * Try again later, backing off 5, 10, 20 s, then every 60 s. A scan the radio refused
     * ({@code refused}) counts towards {@link #GIVE_UP_AFTER}; after that many in a row this
     * process is replaced instead.
     */
    private static void retryLater(String why, boolean refused) {
        failures++;
        if (refused && failures >= GIVE_UP_AFTER && out != null) {
            trace("beacon scan " + why + " (#" + failures + "), starting a fresh BLE process");
            MAIN.removeCallbacks(RETRY);
            out.giveUp(why);
            return;
        }
        long wait = Math.min(60_000L, RETRY_MS << Math.min(4, failures - 1));
        trace("beacon scan " + why + ", retry in " + wait / 1000 + "s (#" + failures + ")");
        MAIN.removeCallbacks(RETRY);
        MAIN.postDelayed(RETRY, wait);
        notifyStatus();
    }

    private static ScanCallback newCallback() {
        return new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                noteAdvert(this);
                // The scan is unfiltered (this car ignores filters), so every device nearby lands
                // here, the car's own module about 10 times a second. Drop anything that is not
                // one of ours right here, before any decoding or queueing on the main thread.
                if (!ours(result)) {
                    return;
                }
                ScanCallback self = this;
                MAIN.post(() -> {
                    if (callback == self) {
                        seen(result);
                    }
                });
            }

            @Override
            public void onBatchScanResults(List<ScanResult> results) {
                noteAdvert(this);
                ScanCallback self = this;
                MAIN.post(() -> {
                    if (callback != self) {
                        return;
                    }
                    for (ScanResult result : results) {
                        if (ours(result)) {
                            seen(result);
                        }
                    }
                });
            }

            @Override
            public void onScanFailed(int errorCode) {
                ScanCallback self = this;
                MAIN.post(() -> {
                    if (callback != self) {
                        return;
                    }
                    callback = null;
                    scanner = null;
                    scanning = new HashSet<>();
                    MAIN.removeCallbacks(CHECK);
                    MAIN.removeCallbacks(REFRESH);
                    retryLater("failed code=" + errorCode + " (" + failName(errorCode) + ")", true);
                });
            }
        };
    }

    /**
     * Log a gap in everything the radio hears. Tells a deaf radio (the car's Bluetooth busy
     * elsewhere) from a sticker that did not send. Scan callback thread.
     */
    private static void noteAdvert(ScanCallback from) {
        long now = SystemClock.elapsedRealtime();
        long prev = lastAnyAt;
        lastAnyAt = now;
        if (prev == 0 || now - prev < QUIET_MS) {
            discoverySeen = false;
            return;
        }
        long gap = now - prev;
        // Read here: the next advert, a moment later, clears it. Polls ran during the gap.
        boolean found = discoverySeen;
        discoverySeen = false;
        MAIN.post(() -> {
            if (callback == from) {
                trace(String.format(java.util.Locale.US, "radio quiet %.1fs discovering=%s %s",
                        gap / 1000f, found ? "yes" : "no", RadioSnapshot.describe(app, radio)));
            }
        });
    }

    /**
     * Cheap first look: a saved button by address, or, only while a shortcut screen wants to
     * capture a new one, anything with the button's data shape. Any thread.
     */
    static boolean ours(ScanResult result) {
        if (result == null || result.getDevice() == null) {
            return false;
        }
        String address;
        try {
            address = result.getDevice().getAddress();
        } catch (SecurityException e) {
            return false;
        }
        if (address != null && scanning.contains(address)) {
            return true;
        }
        if (!openToNew || result.getScanRecord() == null) {
            return false;
        }
        return StickerAdverts.fields(result.getScanRecord().getBytes()) != null;
    }

    static String failName(int code) {
        switch (code) {
            case 1: return "already started";
            case 2: return "app registration failed";
            case 3: return "internal error";
            case 4: return "feature unsupported";
            case 5: return "out of hardware resources";
            case 6: return "scanning too frequently";
            default: return "unknown";
        }
    }

    private static void seen(ScanResult result) {
        if (scanner == null || result == null || result.getDevice() == null || result.getScanRecord() == null) {
            return;
        }
        String mac;
        try {
            mac = StickerDevices.normalize(result.getDevice().getAddress());
        } catch (SecurityException e) {
            return;
        }
        boolean saved = scanning.contains(mac);
        int[] shape = StickerAdverts.fields(result.getScanRecord().getBytes());
        // An unsaved button only while a controller / shortcut screen wants to capture one;
        // its data shape is what marks it (the name is not in every advert).
        if (!saved && !(openToNew && shape != null)) {
            return;
        }
        lastAdvertAt = SystemClock.elapsedRealtime();
        failures = 0;
        if (HEARD.put(mac, lastAdvertAt) == null && out != null) {
            out.heard(mac, true);
        }
        int[] f = shape;
        if (f == null) {
            return;
        }
        int cnt = f[0];
        int y = f[1];
        Integer last = LAST_CNT.put(mac, cnt);
        if (last == null || last != cnt) {
            saveCount(mac, cnt);
        }
        if (!StickerAdverts.isNewPress(last, cnt)) {
            if (last == null) {
                trace(String.format("beacon %s baseline cnt=%02X rssi=%d", mac, cnt, result.getRssi()));
            }
            return;
        }
        int missed = ((cnt - last) & 0xFF) - 1;
        byte[] frame = StickerAdverts.frameFor(y);
        trace(String.format("beacon press %s cnt=%02X y=%02X %s rssi=%d%s", mac, cnt, y,
                y == StickerAdverts.Y_DOUBLE ? "double" : y == StickerAdverts.Y_SINGLE ? "single" : "unknown",
                result.getRssi(), missed > 0 ? " missed=" + missed : ""));
        if (frame != null && out != null) {
            out.press(mac, frame);
        }
    }

    private static final String PREFS = "sticker_beacon";
    private static boolean countsLoaded;

    /**
     * The last press counter per button survives scan restarts and app restarts. Otherwise the
     * first press after a quiet spell, or after the app starts, only set a baseline and was
     * lost (car log 2.11.27). A button only broadcasts for ~15 s after a press, so at worst a
     * press made while the app was starting runs a moment late, which is what was meant.
     */
    private static void loadCounts() {
        if (countsLoaded || app == null) {
            return;
        }
        countsLoaded = true;
        Map<String, ?> all = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getAll();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            if (e.getValue() instanceof Integer && !LAST_CNT.containsKey(e.getKey())) {
                LAST_CNT.put(e.getKey(), (Integer) e.getValue());
            }
        }
    }

    /** Write every counter now, before this process is killed (apply() might not have yet). */
    static void flushCounts() {
        if (app == null || LAST_CNT.isEmpty()) {
            return;
        }
        SharedPreferences.Editor edit = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        for (Map.Entry<String, Integer> e : LAST_CNT.entrySet()) {
            edit.putInt(e.getKey(), e.getValue());
        }
        edit.commit();
    }

    private static void saveCount(String mac, int cnt) {
        if (app != null) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(mac, cnt).apply();
        }
    }

    private static boolean canScan(Context context) {
        if (Build.VERSION.SDK_INT >= 31) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }
}
