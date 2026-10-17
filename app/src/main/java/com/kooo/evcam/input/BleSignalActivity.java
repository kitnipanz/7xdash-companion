package com.kooo.evcam.input;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.SparseArray;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.kooo.evcam.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every Bluetooth LE advert this radio hears. Nothing is filtered out.
 * Scanning pauses sticker links, because this head unit stays quiet while a link is up.
 */
public class BleSignalActivity extends AppCompatActivity {

    private static final int ASK = 48;
    private static final long DISCOVERY_MS = 2_000L;
    private static final int MAX_SCAN_RETRIES = 3;
    private static final long SCAN_RETRY_MS = 1_500L;
    private int scanRetries;
    private static final int RAW_BYTES = 24;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Heard> heard = new LinkedHashMap<>();
    private final Runnable settleDone = this::beginDiscovery;
    private final Runnable discoveryDone = this::startLeScan;

    private LinearLayout list;
    private TextView status;
    private TextView scanButton;
    private BluetoothLeScanner scanner;
    private boolean scanning;
    private boolean discovering;
    private boolean leStarted;
    private boolean discoveryListening;

    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                return;
            }
            BluetoothDevice device = deviceExtra(intent);
            short rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
            Integer value = rssi == Short.MIN_VALUE ? null : (int) rssi;
            runOnUiThread(() -> hear(device, value, null));
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            StickerAdverts.seen(getApplicationContext(), result);
            runOnUiThread(() -> addResult(result));
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) {
                StickerAdverts.seen(getApplicationContext(), result);
            }
            runOnUiThread(() -> {
                for (ScanResult result : results) {
                    addResult(result);
                }
            });
        }

        @Override
        public void onScanFailed(int errorCode) {
            runOnUiThread(() -> {
                StickerLog.line("BLE signals scan failed code=" + errorCode + " ("
                        + StickerBeacon.failName(errorCode) + ") try " + (scanRetries + 1));
                haltScan();
                // Code 2 (registration failed) is usually transient on this head unit: try again
                // a few times before showing the error, and give the radio back in between.
                if (resumed && scanRetries < MAX_SCAN_RETRIES) {
                    scanRetries++;
                    status.setText(getString(R.string.sticker_fail, errorCode));
                    handler.removeCallbacks(retryScan);
                    handler.postDelayed(retryScan, SCAN_RETRY_MS);
                    return;
                }
                StickerHub.holdForScan(BleSignalActivity.this, false);
                status.setText(getString(R.string.sticker_fail, errorCode));
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ble_signal);
        list = findViewById(R.id.ble_list);
        status = findViewById(R.id.ble_status);
        scanButton = findViewById(R.id.ble_scan);
        findViewById(R.id.ble_close).setOnClickListener(v -> finish());
        scanButton.setOnClickListener(v -> {
            if (scanning) {
                stopScan();
            } else if (ensurePermission()) {
                startScan();
            }
        });
    }

    /** Retries run only while the screen is in front; leaving always gives the radio back. */
    private boolean resumed;
    private final Runnable retryScan = () -> {
        if (resumed && !isFinishing() && !scanning) {
            startScan();
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        StickerHub.setScanStop(this::stopScan);
        if (!scanning && ensurePermission()) {
            startScan();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(retryScan);
        stopScan();
        // Always: a scan that already ended (failed, between retries) left the hold set, and the
        // sticker listening stayed off (BLE grey) until the app was restarted.
        StickerHub.holdForScan(this, false);
        StickerHub.setScanStop(null);
        super.onPause();
    }

    private void startScan() {
        BluetoothAdapter adapter = adapter();
        if (adapter == null || !adapter.isEnabled()) {
            status.setText(R.string.sticker_bluetooth_off);
            return;
        }
        if (adapter.getBluetoothLeScanner() == null) {
            status.setText(R.string.sticker_bluetooth_off);
            return;
        }
        haltScan();
        scanning = true;
        scanButton.setText(R.string.ble_signal_stop);
        paintStatus();
        StickerHub.holdForScan(this, true);
        handler.postDelayed(settleDone, 700);
    }

    private void beginDiscovery() {
        if (!scanning || discovering || leStarted) {
            return;
        }
        BluetoothAdapter adapter = adapter();
        if (adapter == null || !adapter.isEnabled()) {
            status.setText(R.string.sticker_bluetooth_off);
            stopScan();
            return;
        }
        listenDiscovery();
        discovering = true;
        boolean started = false;
        try {
            adapter.cancelDiscovery();
            started = adapter.startDiscovery();
        } catch (SecurityException e) {
            status.setText(R.string.sticker_need_permission);
        }
        if (!started) {
            startLeScan();
            return;
        }
        handler.postDelayed(discoveryDone, DISCOVERY_MS);
    }

    private void startLeScan() {
        if (!scanning || leStarted) {
            return;
        }
        leStarted = true;
        discovering = false;
        handler.removeCallbacks(discoveryDone);
        cancelClassic();
        BluetoothAdapter adapter = adapter();
        BluetoothLeScanner next = adapter == null ? null : adapter.getBluetoothLeScanner();
        if (next == null) {
            status.setText(R.string.sticker_bluetooth_off);
            stopScan();
            return;
        }
        scanner = next;
        StickerAdverts.start(this);
        try {
            scanner.startScan(null, new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setReportDelay(0)
                    .build(), scanCallback);
        } catch (SecurityException e) {
            status.setText(R.string.sticker_need_permission);
            stopScan();
        }
    }

    private void stopScan() {
        boolean live = scanning || discovering || leStarted;
        haltScan();
        if (live) {
            StickerHub.holdForScan(this, false);
        }
    }

    private void haltScan() {
        handler.removeCallbacks(settleDone);
        handler.removeCallbacks(discoveryDone);
        cancelClassic();
        unlistenDiscovery();
        if (scanner != null && leStarted) {
            StickerAdverts.stop();
            try {
                scanner.stopScan(scanCallback);
            } catch (SecurityException ignored) {
                // scan permission gone
            }
        }
        scanning = false;
        discovering = false;
        leStarted = false;
        if (scanButton != null) {
            scanButton.setText(R.string.sticker_scan);
        }
    }

    private void addResult(ScanResult result) {
        if (result == null) {
            return;
        }
        scanRetries = 0;
        hear(result.getDevice(), result.getRssi(), result.getScanRecord());
    }

    private void hear(BluetoothDevice device, Integer rssi, ScanRecord record) {
        if (device == null) {
            return;
        }
        String address;
        String name;
        try {
            address = StickerDevices.normalize(device.getAddress());
            name = device.getName();
        } catch (SecurityException e) {
            status.setText(R.string.sticker_need_permission);
            return;
        }
        if (address.isEmpty()) {
            return;
        }
        Heard row = heard.get(address);
        boolean fresh = row == null;
        if (row == null) {
            row = new Heard();
            row.address = address;
            heard.put(address, row);
        }
        if (name != null && !name.isEmpty()) {
            row.name = name;
        }
        boolean rssiChanged = false;
        if (rssi != null) {
            rssiChanged = row.rssi == null || Math.abs(row.rssi - rssi) >= 4;
            row.rssi = rssi;
        }
        boolean detailChanged = false;
        if (record != null) {
            String decoded = StickerAdverts.decode(record.getBytes());
            String next = decoded.isEmpty() ? detail(record) : decoded + "\n" + detail(record);
            if (!next.isEmpty() && !next.equals(row.detail)) {
                row.detail = next;
                detailChanged = true;
            }
        }
        if (fresh || rssiChanged || detailChanged) {
            render();
        } else {
            paintStatus();
        }
    }

    private void render() {
        list.removeAllViews();
        List<Heard> rows = new ArrayList<>(heard.values());
        Collections.sort(rows, new Comparator<Heard>() {
            @Override
            public int compare(Heard a, Heard b) {
                int ar = a.rssi == null ? -200 : a.rssi;
                int br = b.rssi == null ? -200 : b.rssi;
                return br - ar;
            }
        });
        for (Heard row : rows) {
            // Only the Zeekr buttons: everything else nearby is noise for this screen.
            if (!StickerAdverts.isButton(row.name)) {
                continue;
            }
            list.addView(rowView(row));
        }
        paintStatus();
    }

    private View rowView(Heard row) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        line.setPadding(pad, dp(10), pad, dp(10));
        TextView title = new TextView(this);
        String name = row.name == null || row.name.isEmpty()
                ? getString(R.string.sticker_unnamed) : row.name;
        title.setText(name);
        title.setTextAppearance(R.style.TextAppearance_Cam_Body);
        title.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        TextView meta = new TextView(this);
        int rssi = row.rssi == null ? 0 : row.rssi;
        String line2 = row.rssi == null
                ? row.address
                : getString(R.string.ble_signal_meta, row.address, rssi);
        if (row.detail != null && !row.detail.isEmpty()) {
            line2 = line2 + "\n" + row.detail;
        }
        meta.setText(line2);
        meta.setTextAppearance(R.style.TextAppearance_Cam_Caption);
        meta.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        line.addView(title);
        line.addView(meta);
        return line;
    }

    private void paintStatus() {
        if (heard.isEmpty()) {
            status.setText(scanning ? R.string.sticker_scanning : R.string.ble_signal_empty);
            return;
        }
        status.setText(getString(R.string.ble_signal_count, heard.size()));
    }

    private static String detail(ScanRecord record) {
        StringBuilder out = new StringBuilder();
        List<ParcelUuid> uuids = record.getServiceUuids();
        if (uuids != null) {
            for (ParcelUuid id : uuids) {
                if (id == null) {
                    continue;
                }
                if (out.length() > 0) {
                    out.append(' ');
                }
                out.append(id.getUuid());
            }
        }
        SparseArray<byte[]> mfg = record.getManufacturerSpecificData();
        if (mfg != null) {
            for (int i = 0; i < mfg.size(); i++) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(String.format(Locale.US, "%04X ", mfg.keyAt(i)));
                out.append(hex(mfg.valueAt(i)));
            }
        }
        byte[] raw = record.getBytes();
        if (raw != null && raw.length > 0) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(hex(raw));
        }
        return out.toString();
    }

    private static String hex(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return "";
        }
        int n = Math.min(raw.length, RAW_BYTES);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                out.append(' ');
            }
            out.append(String.format(Locale.US, "%02X", raw[i] & 0xFF));
        }
        if (raw.length > n) {
            out.append(" …");
        }
        return out.toString();
    }

    private void listenDiscovery() {
        if (discoveryListening) {
            return;
        }
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(discoveryReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(discoveryReceiver, filter);
        }
        discoveryListening = true;
    }

    private void unlistenDiscovery() {
        if (!discoveryListening) {
            return;
        }
        try {
            unregisterReceiver(discoveryReceiver);
        } catch (IllegalArgumentException ignored) {
            // already gone
        }
        discoveryListening = false;
    }

    private void cancelClassic() {
        BluetoothAdapter adapter = adapter();
        if (adapter == null) {
            return;
        }
        try {
            if (adapter.isDiscovering()) {
                adapter.cancelDiscovery();
            }
        } catch (SecurityException ignored) {
            // scan permission gone
        }
    }

    private BluetoothAdapter adapter() {
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        return manager == null ? null : manager.getAdapter();
    }

    private static BluetoothDevice deviceExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        }
        return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
    }

    private boolean ensurePermission() {
        List<String> missing = new ArrayList<>();
        for (String permission : needed()) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        if (missing.isEmpty()) {
            return true;
        }
        requestPermissions(missing.toArray(new String[0]), ASK);
        return false;
    }

    private static String[] needed() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            };
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != ASK) {
            return;
        }
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                status.setText(R.string.sticker_need_permission);
                return;
            }
        }
        startScan();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class Heard {
        private String address;
        private String name;
        private Integer rssi;
        private String detail;
    }
}
