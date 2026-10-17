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
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Experimental screen: find a smart sticker the head unit will not pair, connect it,
 * and save a press as a shortcut.
 */
public class StickerActivity extends AppCompatActivity implements StickerHub.Watch {

    private static final int ASK = 47;
    private static final long SCAN_MS = 10_000L;
    /** Classic inquiry first. This radio often returns an empty LE scan until discovery has run. */
    private static final long DISCOVERY_MS = 2_000L;
    private static final int LOG_LIMIT = 30;
    private static final int OTHER_LIMIT = 12;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable scanDone = this::stopScan;
    private final Runnable settleDone = this::beginDiscovery;
    private final Runnable discoveryDone = this::startLeScan;
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
            runOnUiThread(() -> hear(device, value, false));
        }
    };
    private final Map<String, Seen> seen = new LinkedHashMap<>();
    private final ArrayDeque<String> logLines = new ArrayDeque<>();

    private AppConfig config;
    private LinearLayout devices;
    private TextView empty;
    private TextView status;
    private TextView signal;
    private TextView live;
    private TextView log;
    private String signalAddress;
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final Runnable rssiPoll = new Runnable() {
        @Override
        public void run() {
            if (signalAddress != null) {
                StickerHub.readRssi(signalAddress);
            }
            handler.postDelayed(this, 2000);
        }
    };
    private View actions;
    private TextView scanButton;
    private BluetoothLeScanner scanner;
    private boolean scanning;
    private boolean discovering;
    private boolean leStarted;
    private boolean discoveryListening;
    private String pendingAddress;
    private StickerFrame pending;

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
                haltScan();
                StickerHub.holdForScan(StickerActivity.this, false);
                status.setText(getString(R.string.sticker_fail, errorCode));
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sticker);
        config = new AppConfig(this);
        devices = findViewById(R.id.sticker_devices);
        empty = findViewById(R.id.sticker_empty);
        status = findViewById(R.id.sticker_status);
        signal = findViewById(R.id.sticker_signal);
        live = findViewById(R.id.sticker_live);
        log = findViewById(R.id.sticker_log);
        actions = findViewById(R.id.sticker_actions);
        scanButton = findViewById(R.id.sticker_scan);
        findViewById(R.id.sticker_close).setOnClickListener(v -> finish());
        findViewById(R.id.sticker_signals).setOnClickListener(v ->
                startActivity(new Intent(this, BleSignalActivity.class)));
        scanButton.setOnClickListener(v -> {
            if (scanning) {
                stopScan();
            } else if (ensurePermission()) {
                startScan();
            }
        });
        SwitchCompat use = findViewById(R.id.sticker_use);
        use.setChecked(config.isStickerEnabled());
        use.setOnCheckedChangeListener((button, on) -> {
            config.setStickerEnabled(on);
            StickerHub.sync(this);
        });
        bind(R.id.sticker_action_record, ShortcutAction.RECORD);
        bind(R.id.sticker_action_mirror, ShortcutAction.MIRROR);
        bind(R.id.sticker_action_mirror_hud, ShortcutAction.MIRROR_HUD);
        bind(R.id.sticker_action_both_mirrors, ShortcutAction.BOTH_MIRRORS);
        bind(R.id.sticker_action_app, ShortcutAction.APP);
        bind(R.id.sticker_action_dim, ShortcutAction.DIM);
        bind(R.id.sticker_action_save, ShortcutAction.SAVE);
        bind(R.id.sticker_action_lock_save, ShortcutAction.LOCK_SAVE);
        bind(R.id.sticker_action_instant_capture, ShortcutAction.INSTANT_CAPTURE);
        bind(R.id.sticker_action_hold_speak, ShortcutAction.HOLD_SPEAK);
        bind(R.id.sticker_action_toggle_speak, ShortcutAction.TOGGLE_SPEAK);
        bind(R.id.sticker_action_cabin, ShortcutAction.CABIN_PASSENGER);
    }

    @Override
    protected void onResume() {
        super.onResume();
        StickerHub.setCapture(true);
        StickerHub.setScanStop(this::stopScan);
        StickerHub.addWatch(this);
        StickerHub.sync(this);
        render();
        handler.post(rssiPoll);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(rssiPoll);
        stopScan();
        // Always give the radio back, even if the scan already ended: a forgotten hold kept the
        // sticker listening off (BLE grey) until the app was restarted.
        StickerHub.holdForScan(this, false);
        StickerHub.setCapture(false);
        StickerHub.setScanStop(null);
        StickerHub.removeWatch(this);
        StickerHub.sync(this);
        super.onPause();
    }

    private void bind(int id, ShortcutAction action) {
        findViewById(id).setOnClickListener(v -> save(action));
    }

    private void save(ShortcutAction action) {
        if (pending == null || pendingAddress == null) {
            return;
        }
        Shortcut next = new Shortcut(pending.control, 0, StickerFrame.deviceName(pendingAddress),
                action.key, pending.press.key);
        config.setButtonShortcuts(ShortcutBook.write(
                ShortcutBook.put(ShortcutBook.parse(config.getButtonShortcuts()), next)));
        config.setStickerEnabled(true);
        SwitchCompat use = findViewById(R.id.sticker_use);
        if (!use.isChecked()) {
            use.setChecked(true);
        } else {
            StickerHub.sync(this);
        }
        int label = StickerFrame.controlLabel(pending.control);
        String control = label == 0 ? String.valueOf(pending.control) : getString(label);
        status.setText(getString(R.string.sticker_saved_action,
                control + " " + getString(pending.press.labelRes),
                getString(action.labelRes)));
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
        scanButton.setText(R.string.sticker_scanning);
        status.setText(R.string.sticker_scanning);
        StickerHub.holdForScan(this, true);
        handler.postDelayed(settleDone, 700);
    }

    /** Classic inquiry, then the LE scan. Links stay down until {@link #stopScan()}. */
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
        seedBonded(adapter);
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
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
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

    private void seedBonded(BluetoothAdapter adapter) {
        Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (SecurityException e) {
            status.setText(R.string.sticker_need_permission);
            return;
        }
        if (bonded == null) {
            return;
        }
        for (BluetoothDevice device : bonded) {
            int type;
            try {
                type = device.getType();
            } catch (SecurityException e) {
                continue;
            }
            if (type != BluetoothDevice.DEVICE_TYPE_LE && type != BluetoothDevice.DEVICE_TYPE_DUAL) {
                continue;
            }
            hear(device, null, false);
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
            if (adapter.isDiscovering()) {
                adapter.cancelDiscovery();
            }
        } catch (SecurityException ignored) {
            // scan permission gone
        }
    }

    private static BluetoothDevice deviceExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        }
        return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
    }

    private void addResult(ScanResult result) {
        if (result == null) {
            return;
        }
        android.bluetooth.le.ScanRecord record = result.getScanRecord();
        boolean button = record != null && (StickerAdverts.fields(record.getBytes()) != null
                || StickerAdverts.isButton(record.getDeviceName()));
        hear(result.getDevice(), result.getRssi(), advertises(record));
        if (button) {
            markButton(result.getDevice());
        }
    }

    /** A ZEEKR_button: its presses come from its broadcast, so it is added without connecting. */
    private void markButton(BluetoothDevice device) {
        String address;
        try {
            address = StickerDevices.normalize(device.getAddress());
        } catch (SecurityException e) {
            return;
        }
        Seen row = seen.get(address);
        if (row != null && !row.button) {
            row.button = true;
            render();
        }
    }

    private void hear(BluetoothDevice device, Integer rssi, boolean service) {
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
        Seen row = seen.get(address);
        boolean fresh = row == null;
        if (row == null) {
            row = new Seen();
            row.address = address;
            row.rank = StickerMatch.RANK_OTHER;
            seen.put(address, row);
        }
        int previousRank = row.rank;
        int nextRank = Math.min(previousRank, StickerMatch.rank(name, service));
        boolean nameChanged = name != null && !name.isEmpty() && !name.equals(row.name);
        boolean rssiChanged = rssi != null && (row.rssi == null || Math.abs(row.rssi - rssi) >= 8);
        if (name != null && !name.isEmpty()) {
            row.name = name;
        }
        if (StickerAdverts.isButton(name)) {
            row.button = true;
        }
        row.rank = nextRank;
        if (rssi != null) {
            row.rssi = rssi;
        }
        if (address.equals(signalAddress) && row.rssi != null) {
            showSignal(row.rssi);
        }
        if (fresh || nameChanged || rssiChanged || nextRank != previousRank) {
            render();
        }
    }

    private static boolean advertises(ScanRecord record) {
        if (record == null) {
            return false;
        }
        List<ParcelUuid> uuids = record.getServiceUuids();
        if (uuids != null) {
            for (ParcelUuid id : uuids) {
                if (id != null && StickerMatch.isService(id.getUuid())) {
                    return true;
                }
            }
        }
        Map<ParcelUuid, byte[]> data = record.getServiceData();
        if (data != null) {
            for (ParcelUuid id : data.keySet()) {
                if (id != null && StickerMatch.isService(id.getUuid())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void render() {
        devices.removeAllViews();
        List<String> saved = StickerDevices.parse(config.getStickerDevices());
        boolean candidate = false;
        for (Seen row : seen.values()) {
            if (row.rank < StickerMatch.RANK_OTHER) {
                candidate = true;
            }
        }
        int shown = 0;
        for (String address : saved) {
            Seen row = seen.get(address);
            addRow(address, row == null ? null : row.name, row == null ? null : row.rssi, true);
            shown++;
        }
        for (Seen row : seen.values()) {
            if (saved.contains(row.address) || row.rank >= StickerMatch.RANK_OTHER) {
                continue;
            }
            addRow(row.address, row.name, row.rssi, false);
            shown++;
        }
        if (!candidate) {
            int others = 0;
            boolean header = false;
            for (Seen row : seen.values()) {
                if (saved.contains(row.address) || row.rank < StickerMatch.RANK_OTHER) {
                    continue;
                }
                if (row.name == null || row.name.isEmpty()) {
                    continue;
                }
                if (!header) {
                    TextView title = new TextView(this);
                    title.setText(R.string.sticker_other);
                    title.setTextAppearance(R.style.TextAppearance_Cam_Caption);
                    title.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_tertiary));
                    title.setPadding(dp(16), dp(12), dp(16), dp(4));
                    devices.addView(title);
                    header = true;
                }
                addRow(row.address, row.name, row.rssi, false);
                shown++;
                others++;
                if (others >= OTHER_LIMIT) {
                    break;
                }
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
        String alias = saved ? StickerNames.alias(this, address) : "";
        if (!alias.isEmpty()) {
            title.setText(alias);
        } else {
            title.setText(name == null || name.isEmpty() ? getString(R.string.sticker_unnamed) : name);
        }
        if (saved) {
            // Tap the name to give this sticker its own name.
            title.setOnClickListener(v -> rename(address));
            title.setContentDescription(getString(R.string.sticker_rename_hint_a11y, title.getText()));
        }
        String detail = rssi == null ? address : getString(R.string.sticker_device_meta, address, rssi);
        boolean up = saved && StickerHub.isUp(address);
        if (saved) {
            String mapped = mapped(address);
            if (mapped != null) {
                detail = detail + "  " + mapped;
            }
            detail = detail + "  " + getString(up ? R.string.sticker_connected : R.string.sticker_link_off);
        }
        dot.setVisibility(saved ? View.VISIBLE : View.GONE);
        dot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this,
                up ? R.color.energy : R.color.text_tertiary)));
        meta.setText(detail);
        action.setText(saved ? R.string.sticker_forget : R.string.sticker_connect);
        action.setOnClickListener(v -> {
            if (saved) {
                forget(address);
            } else if (isButton(address)) {
                addFromSignal(address);
            } else if (ensurePermission()) {
                stopScan();
                status.setText(R.string.sticker_connecting);
                noteConnect(address);
            }
        });
        devices.addView(line);
    }

    /** Name this sticker. Blank (or Clear) goes back to the address tail. */
    private void rename(String address) {
        android.widget.EditText input = com.kooo.evcam.ui.CamDialogs.input(this);
        input.setSingleLine(true);
        input.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(StickerNames.MAX_LENGTH)});
        input.setHint(R.string.sticker_rename_hint);
        input.setText(StickerNames.alias(this, address));
        input.setSelection(input.getText().length());
        com.kooo.evcam.ui.CamDialogs.show(new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(getString(R.string.sticker_rename_title,
                        StickerFrame.tail(StickerFrame.deviceName(address))))
                .setView(input)
                .setPositiveButton(R.string.sticker_rename_save, (dialog, which) -> {
                    StickerNames.setAlias(this, address, input.getText().toString());
                    render();
                })
                .setNeutralButton(R.string.sticker_rename_clear, (dialog, which) -> {
                    StickerNames.setAlias(this, address, "");
                    render();
                })
                .setNegativeButton(android.R.string.cancel, null));
    }

    private void forget(String address) {
        String deviceName = StickerFrame.deviceName(address);
        List<Shortcut> kept = new ArrayList<>();
        for (Shortcut item : ShortcutBook.parse(config.getButtonShortcuts())) {
            if (item != null && !deviceName.equals(item.deviceName)) {
                kept.add(item);
            }
        }
        config.setButtonShortcuts(ShortcutBook.write(kept));
        config.setStickerDevices(StickerDevices.write(
                StickerDevices.remove(StickerDevices.parse(config.getStickerDevices()), address)));
        StickerHub.disconnect(address);
        StickerNames.setAlias(this, address, "");
        render();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private BluetoothAdapter adapter() {
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        return manager == null ? null : manager.getAdapter();
    }

    /** Saved shortcut for this sticker, so the row shows the mapping is still stored. */
    private String mapped(String address) {
        String device = StickerFrame.deviceName(address);
        StringBuilder out = new StringBuilder();
        for (Shortcut item : ShortcutBook.parse(config.getButtonShortcuts())) {
            if (item == null || !device.equals(item.deviceName)) {
                continue;
            }
            ShortcutAction action = ShortcutAction.fromKey(item.action);
            if (action == null) {
                continue;
            }
            if (out.length() > 0) {
                out.append(" · ");
            }
            out.append(getString(item.pressKind().labelRes));
            out.append(' ');
            out.append(getString(action.labelRes));
        }
        return out.length() == 0 ? null : out.toString();
    }

    private void append(String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        logLines.addLast(line);
        while (logLines.size() > LOG_LIMIT) {
            logLines.removeFirst();
        }
        StringBuilder out = new StringBuilder();
        for (String item : logLines) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(item);
        }
        log.setText(out.toString());
    }

    @Override
    public void onState(String address, boolean up) {
        render();
    }

    private String lastPacketLine = "";

    @Override
    public void onPacket(String address, byte[] raw) {
        String hex = StickerFrame.hex(raw);
        if (!hex.equals(lastPacketLine)) {
            lastPacketLine = hex;
            append(hex);
        }
        StickerFrame frame = StickerFrame.parse(raw);
        if (frame == null) {
            return;
        }
        pendingAddress = StickerDevices.normalize(address);
        pending = frame;
        int label = StickerFrame.controlLabel(frame.control);
        String control = label == 0 ? String.valueOf(frame.control) : getString(label);
        live.setVisibility(View.VISIBLE);
        live.setText(getString(R.string.sticker_press_line, control, getString(frame.press.labelRes)));
        actions.setVisibility(View.VISIBLE);
    }

    @Override
    public void onReady(String address) {
        String mac = StickerDevices.normalize(address);
        config.setStickerDevices(StickerDevices.write(
                StickerDevices.put(StickerDevices.parse(config.getStickerDevices()), mac)));
        status.setText(R.string.sticker_waiting);
        render();
    }

    @Override
    public void onFail(String address, int reason, int code) {
        if (reason == StickerHub.FAIL_BLUETOOTH) {
            status.setText(R.string.sticker_bluetooth_off);
        } else if (reason == StickerHub.FAIL_TIMEOUT) {
            status.setText(R.string.sticker_timeout);
        } else if (reason == StickerHub.FAIL_NO_SERVICE) {
            status.setText(R.string.sticker_no_service);
        } else {
            status.setText(getString(R.string.sticker_fail, code));
        }
    }

    @Override
    public void onPhase(String address, int phase) {
        signalAddress = StickerDevices.normalize(address);
        int text;
        if (phase == StickerHub.PHASE_PAIRING) {
            text = R.string.sticker_pairing;
        } else if (phase == StickerHub.PHASE_DISCOVERING) {
            text = R.string.sticker_discovering;
        } else {
            text = R.string.sticker_connecting;
        }
        status.setText(text);
        append(clock.format(new Date()) + "  " + getString(text) + "  " + signalAddress);
        StickerHub.readRssi(signalAddress);
    }

    @Override
    public void onRssi(String address, int rssi) {
        String mac = StickerDevices.normalize(address);
        Seen row = seen.get(mac);
        Integer previous = row == null ? null : row.rssi;
        if (row != null) {
            row.rssi = rssi;
        }
        if (!mac.equals(signalAddress)) {
            return;
        }
        showSignal(rssi);
        if (previous == null || Math.abs(previous - rssi) >= 8) {
            append(clock.format(new Date()) + "  " + getString(R.string.sticker_signal, rssi));
        }
    }

    private boolean isButton(String address) {
        Seen row = seen.get(StickerDevices.normalize(address));
        return row != null && row.button;
    }

    /**
     * Save a ZEEKR_button straight away: it is read from its broadcast and never connected.
     * The next press shows up here like any other.
     */
    private void addFromSignal(String address) {
        String mac = StickerDevices.normalize(address);
        config.setStickerDevices(StickerDevices.write(
                StickerDevices.put(StickerDevices.parse(config.getStickerDevices()), mac)));
        append(clock.format(new Date()) + "  " + getString(R.string.sticker_added_signal) + "  " + mac);
        stopScan();
        status.setText(R.string.sticker_waiting);
        StickerHub.sync(this);
        render();
    }

    private void noteConnect(String address) {
        signalAddress = StickerDevices.normalize(address);
        Seen row = seen.get(signalAddress);
        Integer rssi = row == null ? null : row.rssi;
        showSignal(rssi);
        String line = clock.format(new Date()) + "  " + getString(R.string.sticker_connecting)
                + "  " + signalAddress;
        if (rssi != null) {
            line = line + "  " + getString(R.string.sticker_signal, rssi);
        }
        append(line);
        StickerHub.probe(this, address);
    }

    private void showSignal(Integer rssi) {
        if (rssi == null) {
            signal.setText(R.string.sticker_signal_none);
        } else {
            signal.setText(getString(R.string.sticker_signal, rssi));
        }
    }

    @Override
    public void onLog(String line) {
        append(line);
    }

    private static final class Seen {
        String address;
        String name;
        Integer rssi;
        int rank;
        /** ZEEKR_button by name or broadcast data: added from its signal, never connected. */
        boolean button;
    }
}
