package com.kooo.evcam.obd;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.kooo.evcam.input.StickerHub;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One timed search for Bluetooth LE adapters. They do not pair, so they never show in the paired
 * list. The sticker links drop for the search: this head unit hears no adverts while one is up.
 * Main thread only.
 */
final class ObdBleScanner {

    interface Listener {
        void onFound();

        /** {@code errorCode} is 0 for a clean finish. */
        void onDone(int errorCode);
    }

    private static final long SETTLE_MS = 1_000L;
    private static final long SCAN_MS = 10_000L;

    private final Context context;
    private final BluetoothAdapter radio;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, String> found = new LinkedHashMap<>();
    private final Runnable begin = this::begin;
    private final Runnable finish = () -> finish(0);
    private BluetoothLeScanner scanner;
    private boolean running;

    private final ScanCallback callback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            add(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) {
                add(result);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            main.post(() -> finish(errorCode));
        }
    };

    ObdBleScanner(Context context, BluetoothAdapter radio, Listener listener) {
        this.context = context.getApplicationContext();
        this.radio = radio;
        this.listener = listener;
    }

    void start() {
        running = true;
        StickerHub.holdForScan(context, true);
        main.postDelayed(begin, SETTLE_MS);
    }

    boolean isRunning() {
        return running;
    }

    /** Address to name, most likely adapters first. */
    List<Map.Entry<String, String>> found() {
        List<Map.Entry<String, String>> likely = new ArrayList<>();
        List<Map.Entry<String, String>> others = new ArrayList<>();
        for (Map.Entry<String, String> entry : found.entrySet()) {
            (BleUart.looksLikeObd(entry.getValue()) ? likely : others).add(entry);
        }
        likely.addAll(others);
        return likely;
    }

    void stop() {
        if (running) {
            finish(-1);
        }
    }

    private void begin() {
        scanner = radio.getBluetoothLeScanner();
        if (scanner == null) {
            finish(-2);
            return;
        }
        try {
            scanner.startScan(null, new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build(), callback);
        } catch (SecurityException e) {
            finish(-2);
            return;
        }
        main.postDelayed(finish, SCAN_MS);
    }

    private void add(ScanResult result) {
        ScanRecord record = result.getScanRecord();
        String advertised = record == null ? null : record.getDeviceName();
        BluetoothDevice device = result.getDevice();
        String address = device.getAddress();
        main.post(() -> {
            // No name to choose it by; a real adapter always has one
            if (running && advertised != null && !advertised.isEmpty() && !found.containsKey(address)) {
                found.put(address, advertised);
                listener.onFound();
            }
        });
    }

    private void finish(int errorCode) {
        if (!running) {
            return;
        }
        running = false;
        main.removeCallbacks(begin);
        main.removeCallbacks(finish);
        if (scanner != null) {
            try {
                scanner.stopScan(callback);
            } catch (RuntimeException e) {
                // Bluetooth went off, or the permission
            }
            scanner = null;
        }
        StickerHub.holdForScan(context, false);
        listener.onDone(errorCode);
    }
}
