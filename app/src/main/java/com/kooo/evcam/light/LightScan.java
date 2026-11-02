package com.kooo.evcam.light;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import com.kooo.evcam.AppLog;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A short background scan for saved mood lights that keep missing their dials. Filtered to their
 * addresses, so nothing else is reported and it works with the screen off. A light it hears is
 * remembered with its address type ({@link MoodLight#heard}) and dialled at once.
 *
 * <p>It does not free the radio from the sticker links the way the Mood light screen's scan does
 * (remote buttons would drop while driving), so on a head unit that hears nothing during a sticker
 * link it simply finds nothing. Main thread only.</p>
 */
final class LightScan {

    private static final String TAG = "LightScan";
    private static final long SCAN_MS = 20_000L;
    /** At most one scan this often. */
    private static final long EVERY_MS = 60_000L;

    private final Context app;
    private final Consumer<String> heard;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable stop = this::stop;
    private BluetoothLeScanner scanner;
    private long startedAt = -EVERY_MS;

    private final ScanCallback callback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            take(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult r : results) {
                take(r);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            AppLog.w(TAG, "scan failed " + errorCode);
            main.post(LightScan.this::stop);
        }
    };

    LightScan(Context context, Consumer<String> heard) {
        this.app = context.getApplicationContext();
        this.heard = heard;
    }

    /** Scan for these lights, unless one ran in the last minute or one is running. */
    void look(List<String> addresses) {
        long now = SystemClock.elapsedRealtime();
        if (addresses.isEmpty() || scanner != null || now - startedAt < EVERY_MS || !allowed()) {
            return;
        }
        BluetoothManager manager = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        BluetoothLeScanner next = adapter == null || !adapter.isEnabled() ? null : adapter.getBluetoothLeScanner();
        if (next == null) {
            return;
        }
        List<ScanFilter> filters = new ArrayList<>();
        for (String address : addresses) {
            try {
                filters.add(new ScanFilter.Builder().setDeviceAddress(address).build());
            } catch (IllegalArgumentException ignored) {
                // not an address
            }
        }
        if (filters.isEmpty()) {
            return;
        }
        try {
            next.startScan(filters, new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                    .setReportDelay(0)
                    .build(), callback);
        } catch (SecurityException | IllegalStateException e) {
            AppLog.w(TAG, "scan not started: " + e);
            return;
        }
        scanner = next;
        startedAt = now;
        AppLog.d(TAG, "looking for " + addresses);
        main.postDelayed(stop, SCAN_MS);
    }

    void stop() {
        main.removeCallbacks(stop);
        BluetoothLeScanner s = scanner;
        scanner = null;
        if (s != null) {
            try {
                s.stopScan(callback);
            } catch (SecurityException | IllegalStateException ignored) {
                // Bluetooth went off or the permission went
            }
        }
    }

    private void take(ScanResult result) {
        if (result == null || result.getDevice() == null) {
            return;
        }
        MoodLight.heard(result.getDevice());
        String address = result.getDevice().getAddress();
        main.post(() -> heard.accept(address));
    }

    private boolean allowed() {
        if (Build.VERSION.SDK_INT >= 31) {
            return ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }
}
