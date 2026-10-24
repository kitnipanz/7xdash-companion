package com.kooo.evcam.light;

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
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.input.StickerHub;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Experimental screen: find a cheap Bluetooth RGB light, connect it, and set its color.
 *
 * <p>The link is open only while this screen is: these lights take one connection at a time,
 * and holding it would lock out their own phone app.</p>
 */
public class MoodLightActivity extends AppCompatActivity implements MoodLight.Listener {

    private static final int ASK = 48;
    private static final long SCAN_MS = 10_000L;
    /** Classic inquiry first. This radio often returns an empty LE scan until discovery has run. */
    private static final long DISCOVERY_MS = 2_000L;
    private static final int OTHER_LIMIT = 12;
    private static final int[] SWATCHES = {
            0xFF0000, 0xFF6000, 0xFFC000, 0x00FF00, 0x00FFFF, 0x0000FF, 0x8000FF, 0xFF00A0, 0xFFFFFF
    };

    private static final class Seen {
        final String address;
        String name;
        Integer rssi;
        boolean light;

        Seen(String address) {
            this.address = address;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable scanDone = this::stopScan;
    private final Runnable settleDone = this::beginDiscovery;
    private final Runnable discoveryDone = this::startLeScan;
    private final Map<String, Seen> seen = new LinkedHashMap<>();
    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(intent.getAction())) {
                startLeScan();
                return;
            }
            BluetoothDevice device = deviceExtra(intent);
            short rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
            Integer value = rssi == Short.MIN_VALUE ? null : (int) rssi;
            runOnUiThread(() -> hear(device, null, value, null));
        }
    };
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            runOnUiThread(() -> addResult(result));
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            runOnUiThread(() -> {
                for (ScanResult result : results) {
                    addResult(result);
                }
            });
        }

        @Override
        public void onScanFailed(int errorCode) {
            runOnUiThread(() -> {
                stopScan();
                status.setText(getString(R.string.sticker_fail, errorCode));
            });
        }
    };

    private AppConfig config;
    private MoodLight light;
    private TextView status;
    private TextView scanButton;
    private View controls;
    private View preview;
    private SeekBar hue;
    private SeekBar brightness;
    private TextView brightnessLabel;
    private LinearLayout devices;
    private TextView empty;
    private BluetoothLeScanner scanner;
    private boolean scanning;
    private boolean discovering;
    private boolean leStarted;
    private boolean discoveryListening;
    private boolean ready;
    private int color;
    private int level;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mood_light);
        config = new AppConfig(this);
        status = findViewById(R.id.light_status);
        scanButton = findViewById(R.id.light_scan);
        controls = findViewById(R.id.light_controls);
        preview = findViewById(R.id.light_preview);
        hue = findViewById(R.id.light_hue);
        brightness = findViewById(R.id.light_brightness);
        brightnessLabel = findViewById(R.id.light_brightness_label);
        devices = findViewById(R.id.light_devices);
        empty = findViewById(R.id.light_empty);
        color = config.getMoodLightColor();
        level = config.getMoodLightBrightness();

        findViewById(R.id.light_close).setOnClickListener(v -> finish());
        scanButton.setOnClickListener(v -> {
            if (scanning) {
                stopScan();
            } else if (ensurePermission()) {
                startScan();
            }
        });
        findViewById(R.id.light_on).setOnClickListener(v -> {
            light.power(true);
            light.color(color, level);
        });
        findViewById(R.id.light_off).setOnClickListener(v -> light.power(false));
        addSwatches(findViewById(R.id.light_swatches));
        hue.setOnSeekBarChangeListener(new Slider() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    pick(LightProtocol.hue(progress));
                }
            }
        });
        brightness.setProgress(level);
        brightness.setOnSeekBarChangeListener(new Slider() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    level = progress;
                    send();
                }
            }
        });
        showColor();
    }

    @Override
    protected void onResume() {
        super.onResume();
        light = new MoodLight(this, this);
        ready = false;
        showControls();
        String saved = config.getMoodLightAddress();
        if (!saved.isEmpty() && canConnect()) {
            light.connect(saved);
        }
        render();
    }

    @Override
    protected void onPause() {
        stopScan();
        // Always give the radio back, even if the scan already ended.
        StickerHub.holdForScan(this, false);
        light.release();
        super.onPause();
    }

    // =================================================================== light

    @Override
    public void onLightConnecting() {
        status.setText(R.string.mood_light_connecting);
    }

    @Override
    public void onLightReady(LightProtocol.Family family) {
        ready = true;
        config.setMoodLightFamily(family.name());
        status.setText(getString(R.string.mood_light_ready, getString(familyLabel(family))));
        showControls();
        render();
    }

    @Override
    public void onLightDown(int reason, int code) {
        ready = false;
        showControls();
        switch (reason) {
            case MoodLight.FAIL_BLUETOOTH:
                status.setText(R.string.sticker_bluetooth_off);
                break;
            case MoodLight.FAIL_TIMEOUT:
                status.setText(R.string.mood_light_timeout);
                break;
            case MoodLight.FAIL_NOT_A_LIGHT:
                status.setText(R.string.mood_light_not_light);
                break;
            case MoodLight.FAIL_GATT:
                status.setText(getString(R.string.sticker_fail, code));
                break;
            default:
                status.setText(R.string.mood_light_down);
                break;
        }
        render();
    }

    private void addSwatches(LinearLayout row) {
        int size = getResources().getDimensionPixelSize(R.dimen.icon_button_size);
        for (int rgb : SWATCHES) {
            View swatch = new View(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.setMarginEnd(size / 4);
            swatch.setLayoutParams(params);
            swatch.setBackgroundResource(R.drawable.sticker_dot);
            swatch.setBackgroundTintList(ColorStateList.valueOf(0xFF000000 | rgb));
            swatch.setContentDescription(getString(R.string.mood_light_swatch,
                    String.format(Locale.US, "#%06X", rgb)));
            swatch.setOnClickListener(v -> pick(rgb));
            row.addView(swatch);
        }
    }

    private void pick(int rgb) {
        color = rgb;
        send();
    }

    private void send() {
        config.setMoodLightColor(color);
        config.setMoodLightBrightness(level);
        showColor();
        if (ready) {
            light.color(color, level);
        }
    }

    private void showColor() {
        preview.setBackgroundTintList(ColorStateList.valueOf(0xFF000000 | color));
        brightnessLabel.setText(getString(R.string.mood_light_brightness, level));
    }

    private void showControls() {
        controls.setAlpha(ready ? 1f : 0.4f);
        setEnabledDeep(controls, ready);
    }

    private static void setEnabledDeep(View view, boolean on) {
        view.setEnabled(on);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                setEnabledDeep(group.getChildAt(i), on);
            }
        }
    }

    private static int familyLabel(LightProtocol.Family family) {
        return family == LightProtocol.Family.TRIONES
                ? R.string.mood_light_family_triones
                : R.string.mood_light_family_elk;
    }

    private abstract static class Slider implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar bar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar bar) {
        }
    }

    // =================================================================== list

    private void render() {
        devices.removeAllViews();
        String saved = config.getMoodLightAddress();
        int shown = 0;
        if (!saved.isEmpty()) {
            Seen row = seen.get(saved);
            addRow(saved, row == null ? null : row.name, row == null ? null : row.rssi, true);
            shown++;
        }
        for (Seen row : seen.values()) {
            if (row.light && !row.address.equals(saved)) {
                addRow(row.address, row.name, row.rssi, false);
                shown++;
            }
        }
        int others = 0;
        for (Seen row : seen.values()) {
            if (row.light || row.address.equals(saved) || row.name == null || row.name.isEmpty()) {
                continue;
            }
            if (others == 0) {
                TextView header = new TextView(this);
                header.setText(R.string.mood_light_other);
                header.setTextAppearance(R.style.TextAppearance_Cam_Caption);
                header.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary));
                header.setPadding(16, 16, 16, 8);
                devices.addView(header);
            }
            addRow(row.address, row.name, row.rssi, false);
            if (++others >= OTHER_LIMIT) {
                break;
            }
        }
        empty.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
    }

    private void addRow(String address, String name, Integer rssi, boolean saved) {
        View line = LayoutInflater.from(this).inflate(R.layout.item_sticker, devices, false);
        TextView title = line.findViewById(R.id.sticker_name);
        TextView meta = line.findViewById(R.id.sticker_meta);
        TextView action = line.findViewById(R.id.sticker_action);
        View dot = line.findViewById(R.id.sticker_dot);
        title.setText(name == null || name.isEmpty() ? getString(R.string.sticker_unnamed) : name);
        String detail = rssi == null ? address : getString(R.string.sticker_device_meta, address, rssi);
        if (saved) {
            detail = detail + "  " + getString(ready ? R.string.sticker_connected : R.string.sticker_link_off);
        }
        meta.setText(detail);
        dot.setVisibility(saved ? View.VISIBLE : View.GONE);
        dot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this,
                saved && ready ? R.color.energy : R.color.text_tertiary)));
        action.setText(saved ? R.string.sticker_forget : R.string.sticker_connect);
        action.setOnClickListener(v -> {
            if (saved) {
                config.setMoodLightAddress("");
                light.release();
                light = new MoodLight(this, this);
                ready = false;
                showControls();
                status.setText(R.string.mood_light_pick);
                render();
            } else if (ensurePermission()) {
                stopScan();
                config.setMoodLightAddress(address);
                ready = false;
                showControls();
                light.connect(address);
                render();
            }
        });
        devices.addView(line);
    }

    // =================================================================== scan

    private boolean canConnect() {
        return Build.VERSION.SDK_INT < 31
                || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
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

    private void startScan() {
        BluetoothAdapter adapter = adapter();
        if (adapter == null || !adapter.isEnabled() || adapter.getBluetoothLeScanner() == null) {
            status.setText(R.string.sticker_bluetooth_off);
            return;
        }
        haltScan();
        scanning = true;
        scanButton.setText(R.string.sticker_scanning);
        status.setText(R.string.sticker_scanning);
        // This head unit hears no scan while a sticker link is up: free the radio first.
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
        try {
            scanner.startScan(null, new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0)
                    .build(), scanCallback);
        } catch (SecurityException e) {
            status.setText(R.string.sticker_need_permission);
            stopScan();
            return;
        }
        handler.postDelayed(scanDone, SCAN_MS);
    }

    private void stopScan() {
        boolean live = scanning || discovering || leStarted;
        haltScan();
        if (live) {
            StickerHub.holdForScan(this, false);
            if (!ready) {
                status.setText(R.string.mood_light_pick);
            }
        }
    }

    private void haltScan() {
        handler.removeCallbacks(settleDone);
        handler.removeCallbacks(discoveryDone);
        handler.removeCallbacks(scanDone);
        cancelClassic();
        unlistenDiscovery();
        if (scanner != null && leStarted) {
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

    private void listenDiscovery() {
        if (discoveryListening) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
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
            adapter.cancelDiscovery();
        } catch (SecurityException ignored) {
            // scan permission gone
        }
    }

    private void addResult(ScanResult result) {
        ScanRecord record = result.getScanRecord();
        String name = record == null ? null : record.getDeviceName();
        List<UUID> services = new ArrayList<>();
        if (record != null && record.getServiceUuids() != null) {
            for (ParcelUuid uuid : record.getServiceUuids()) {
                services.add(uuid.getUuid());
            }
        }
        hear(result.getDevice(), name, result.getRssi(), services);
    }

    private void hear(BluetoothDevice device, String advertised, Integer rssi, List<UUID> services) {
        if (device == null) {
            return;
        }
        String address = device.getAddress();
        Seen row = seen.get(address);
        if (row == null) {
            row = new Seen(address);
            seen.put(address, row);
        }
        String name = advertised;
        if (name == null || name.isEmpty()) {
            try {
                name = device.getName();
            } catch (SecurityException ignored) {
                // connect permission gone
            }
        }
        if (name != null && !name.isEmpty()) {
            row.name = name;
        }
        if (rssi != null) {
            row.rssi = rssi;
        }
        row.light = row.light || LightProtocol.looksLikeLight(row.name, services);
        render();
    }

    @SuppressWarnings("deprecation")
    private static BluetoothDevice deviceExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        }
        return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
    }

    private BluetoothAdapter adapter() {
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        return manager == null ? null : manager.getAdapter();
    }
}
