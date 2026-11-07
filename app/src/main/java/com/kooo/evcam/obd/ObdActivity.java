package com.kooo.evcam.obd;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.BuildConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.telemetry.BlindSpotLog;
import com.kooo.evcam.telemetry.Telemetry;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * OBD2 (beta): live values and trouble codes through an ELM327 adapter in the car's OBD socket.
 *
 * <p>Reads only while this screen is showing and only after Connect is pressed: nothing here
 * keeps an adapter awake on the 12 V battery in the background. It only ever reads
 * ({@link ElmSession}).</p>
 */
public class ObdActivity extends AppCompatActivity {

    private static final String TAG = "OBD";
    private static final String WIFI = "wifi";
    private static final String BLE = "ble:";
    private static final int ASK = 49;
    private static final int ASK_SCAN = 50;
    private static final int MAX_BLE_ROWS = 12;
    private static final int LABEL_DP = 300;
    private static final String LOG_DIR = "7xDash/odb_logs";
    private static final long TICK_MS = 1_000L;

    /** Outlives this screen on purpose: recording during a drive means leaving it. Main thread only. */
    private static ObdCapture capture;
    private static File captureFile;

    private LinearLayout list;
    private TextView status;
    private TextView connectButton;
    private View readCodesButton;
    private TextView recordButton;
    private View liveButton;
    private TextView scanButton;
    private ObdBleScanner scanner;
    private int scanError;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            if (capture != null) {
                main.postDelayed(this, TICK_MS);
            }
        }
    };
    private AppConfig config;
    private String chosen;
    private String chosenName;
    private ObdClient client;
    /** Counts runs: a stopped client's last snapshots must not touch the next one. */
    private int run;
    private ObdSnapshot last;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_obd);
        config = new AppConfig(this);
        chosen = config.getObdAdapter();
        list = findViewById(R.id.obd_list);
        status = findViewById(R.id.obd_status);
        connectButton = findViewById(R.id.obd_connect);
        readCodesButton = findViewById(R.id.obd_read_codes);
        readCodesButton.setOnClickListener(v -> {
            if (client != null) {
                client.requestCodes();
            }
        });
        findViewById(R.id.obd_back).setOnClickListener(v -> finish());
        recordButton = findViewById(R.id.obd_record);
        recordButton.setOnClickListener(v -> {
            if (capture != null && capture.progress().ended == ObdCapture.Ended.RUNNING) {
                capture.stop();
            } else if (ensurePermission()) {
                startCapture();
            }
        });
        scanButton = findViewById(R.id.obd_scan);
        scanButton.setOnClickListener(v -> {
            if (scanner != null && scanner.isRunning()) {
                scanner.stop();
            } else if (ensureScanPermission()) {
                startScan();
            }
        });
        liveButton = findViewById(R.id.obd_live_window);
        liveButton.setOnClickListener(v -> {
            if (capture != null) {
                showLiveWindow();
            }
        });
        connectButton.setOnClickListener(v -> {
            if (client != null) {
                stopClient();
                render();
            } else if (ensurePermission()) {
                connect();
            }
        });
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        tick.run();
    }

    @Override
    protected void onStop() {
        super.onStop();
        main.removeCallbacks(tick);
        stopClient();
        if (scanner != null) {
            scanner.stop();
        }
    }

    private void startScan() {
        BluetoothAdapter radio = radio();
        if (radio == null || !radio.isEnabled()) {
            scanError = -2;
            render();
            return;
        }
        scanError = 0;
        scanner = new ObdBleScanner(this, radio, new ObdBleScanner.Listener() {
            @Override
            public void onFound() {
                render();
            }

            @Override
            public void onDone(int errorCode) {
                scanError = errorCode > 0 || errorCode == -2 ? errorCode : 0;
                render();
            }
        });
        scanner.start();
        render();
    }

    private String[] scanPermissions() {
        return Build.VERSION.SDK_INT >= 31
                ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean ensureScanPermission() {
        List<String> missing = new ArrayList<>();
        for (String permission : scanPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        if (missing.isEmpty()) {
            return true;
        }
        requestPermissions(missing.toArray(new String[0]), ASK_SCAN);
        return false;
    }

    private void rememberAdapter() {
        config.setObdAdapter(chosen);
        if (chosen != null && chosen.startsWith(BLE) && chosenName != null) {
            config.setObdAdapterName(chosenName);
        }
    }

    private void showLiveWindow() {
        if (!ObdSnifferWindow.show(this, capture)) {
            startActivity(new android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName())));
        }
    }

    private void startCapture() {
        ObdLink link = linkFor(chosen);
        if (link == null) {
            status.setText(R.string.obd_pick_adapter);
            return;
        }
        File root = StorageHelper.getExternalSdCardRoot(this);
        File dir = root == null ? null : new File(root, LOG_DIR);
        if (dir == null || !(dir.isDirectory() || dir.mkdirs())) {
            status.setText(R.string.obd_record_no_usb);
            return;
        }
        ObdLog log;
        try {
            Date started = new Date();
            log = new ObdLog(new File(dir, ObdLog.fileName(started)));
            log.line("# 7xDash " + BuildConfig.VERSION_NAME + " OBD capture, " + started);
        } catch (IOException e) {
            AppLog.w(TAG, "cannot start log: " + e);
            status.setText(R.string.obd_record_no_usb);
            return;
        }
        rememberAdapter();
        captureFile = log.file;
        capture = new ObdCapture(link, log, () -> BlindSpotLog.context(Telemetry.get().latest()));
        capture.start();
        showLiveWindow();
        main.removeCallbacks(tick);
        tick.run();
    }

    private void stopClient() {
        run++;
        last = null;
        if (client != null) {
            client.stop();
            client = null;
        }
    }

    private void connect() {
        ObdLink link = linkFor(chosen);
        if (link == null) {
            status.setText(R.string.obd_pick_adapter);
            return;
        }
        rememberAdapter();
        last = null;
        final int thisRun = ++run;
        client = new ObdClient(link, new ObdClient.Listener() {
            @Override
            public void onSnapshot(ObdSnapshot snapshot) {
                runOnUiThread(() -> show(thisRun, snapshot));
            }

            @Override
            public void onTrace(String line) {
                AppLog.d(TAG, line);
            }
        });
        client.start();
        render();
    }

    private ObdLink linkFor(String key) {
        if (WIFI.equals(key)) {
            return new WifiObdLink(this);
        }
        BluetoothAdapter radio = radio();
        if (key == null || radio == null) {
            return null;
        }
        if (key.startsWith(BLE)) {
            return new BleObdLink(this, radio.getRemoteDevice(key.substring(BLE.length())));
        }
        for (BluetoothDevice device : bonded(radio)) {
            if (key.equals(device.getAddress())) {
                return new BluetoothObdLink(radio, device);
            }
        }
        return null;
    }

    private BluetoothAdapter radio() {
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        return manager == null ? null : manager.getAdapter();
    }

    /** Paired devices that can carry a serial link; Bluetooth LE-only ones cannot. */
    private List<BluetoothDevice> bonded(BluetoothAdapter radio) {
        List<BluetoothDevice> devices = new ArrayList<>();
        try {
            Set<BluetoothDevice> all = radio.getBondedDevices();
            if (all != null) {
                for (BluetoothDevice device : all) {
                    if (device.getType() != BluetoothDevice.DEVICE_TYPE_LE) {
                        devices.add(device);
                    }
                }
            }
        } catch (SecurityException e) {
            // Permission not given yet; Connect asks for it
        }
        return devices;
    }

    private boolean hasPermission() {
        return Build.VERSION.SDK_INT < 31
                || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean ensurePermission() {
        if (WIFI.equals(chosen) || hasPermission()) {
            return true;
        }
        requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, ASK);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == ASK) {
            render();
        } else if (requestCode == ASK_SCAN && ensureScanPermissionSilently()) {
            startScan();
        }
    }

    private boolean ensureScanPermissionSilently() {
        for (String permission : scanPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void show(int fromRun, ObdSnapshot snapshot) {
        if (fromRun != run) {
            return;
        }
        last = snapshot;
        if (snapshot.state == ObdSnapshot.State.STOPPED) {
            client = null;
        }
        render();
    }

    private void render() {
        ObdCapture.Progress recording = capture == null ? null : capture.progress();
        boolean running = recording != null && recording.ended == ObdCapture.Ended.RUNNING;
        boolean scanning = scanner != null && scanner.isRunning();
        scanButton.setText(scanning ? R.string.obd_scan_stop : R.string.obd_scan);
        scanButton.setVisibility(client == null && !running ? View.VISIBLE : View.GONE);
        recordButton.setText(running ? R.string.obd_record_stop : R.string.obd_record);
        recordButton.setVisibility(client == null ? View.VISIBLE : View.GONE);
        connectButton.setVisibility(running ? View.GONE : View.VISIBLE);
        liveButton.setVisibility(running && !ObdSnifferWindow.isShown() ? View.VISIBLE : View.GONE);
        connectButton.setText(client == null ? R.string.obd_connect : R.string.obd_disconnect);
        if (recording != null) {
            status.setText(recordingText(recording));
        } else if (scanning) {
            status.setText(R.string.obd_scanning);
        } else if (scanError != 0) {
            status.setText(scanError == -2 ? getString(R.string.obd_bluetooth_off)
                    : getString(R.string.obd_scan_failed, scanError));
        } else {
            status.setText(statusText());
        }
        readCodesButton.setVisibility(last != null && last.state == ObdSnapshot.State.LIVE
                && !last.supported.isEmpty() ? View.VISIBLE : View.GONE);
        list.removeAllViews();
        if (client == null && !running) {
            renderAdapters();
        }
        if (recording != null && !running) {
            // Told once; the next render shows what a new run would
            capture = null;
        }
        if (last != null) {
            renderValues(last);
        }
    }

    private String recordingText(ObdCapture.Progress p) {
        String file = captureFile == null ? "" : captureFile.getAbsolutePath();
        String counts = getString(R.string.obd_record_counts, p.frames, p.bytes / (1024f * 1024f));
        if (p.ended == ObdCapture.Ended.RUNNING) {
            return getString(R.string.obd_recording, counts, p.protocol == null ? "—" : p.protocol, file);
        }
        return getString(endedText(p.ended)) + " " + counts + " · " + file;
    }

    private static int endedText(ObdCapture.Ended ended) {
        switch (ended) {
            case TIME_LIMIT:
                return R.string.obd_record_time_limit;
            case SIZE_LIMIT:
                return R.string.obd_record_size_limit;
            case NOTHING_HEARD:
                return R.string.obd_record_nothing;
            case CANNOT_OPEN:
                return R.string.obd_cannot_open;
            case NOT_ELM:
                return R.string.obd_not_elm;
            case LOST:
                return R.string.obd_lost;
            case DRIVE_ERROR:
                return R.string.obd_record_no_usb;
            default:
                return R.string.obd_record_done;
        }
    }

    private String statusText() {
        if (last == null) {
            return getString(client == null ? R.string.obd_idle : R.string.obd_connecting);
        }
        switch (last.state) {
            case SEARCHING:
                return getString(R.string.obd_searching);
            case NO_CAR:
                return getString(R.string.obd_no_car);
            case LIVE:
                return getString(R.string.obd_live, nonNull(last.adapter), nonNull(last.protocol));
            case CONNECTING:
                return getString(R.string.obd_connecting);
            default:
                break;
        }
        switch (last.failure) {
            case CANNOT_OPEN:
                return getString(R.string.obd_cannot_open);
            case NOT_ELM:
                return getString(R.string.obd_not_elm);
            case LOST:
                return getString(R.string.obd_lost);
            default:
                return getString(R.string.obd_idle);
        }
    }

    private static String nonNull(String text) {
        return text == null ? "" : text;
    }

    private void renderAdapters() {
        addHeader(R.string.obd_adapter);
        BluetoothAdapter radio = radio();
        if (radio != null && hasPermission()) {
            for (BluetoothDevice device : bonded(radio)) {
                String name;
                try {
                    name = device.getName();
                } catch (SecurityException e) {
                    name = null;
                }
                addChoice(name == null ? device.getAddress() : name, device.getAddress());
            }
        } else if (radio != null) {
            addNote(R.string.obd_needs_permission);
        }
        renderBleAdapters();
        addChoice(getString(R.string.obd_wifi_adapter), WIFI);
        addNote(R.string.obd_pair_hint);
        addNote(R.string.obd_record_note);
    }

    private void renderBleAdapters() {
        List<String> shown = new ArrayList<>();
        if (scanner != null) {
            for (Map.Entry<String, String> entry : scanner.found()) {
                if (shown.size() == MAX_BLE_ROWS) {
                    break;
                }
                String key = BLE + entry.getKey();
                shown.add(key);
                addBleChoice(entry.getValue(), key);
            }
        }
        // The adapter used last time, without scanning for it again
        if (chosen != null && chosen.startsWith(BLE) && !shown.contains(chosen)) {
            String name = chosenName != null ? chosenName : config.getObdAdapterName();
            addBleChoice(name == null ? chosen.substring(BLE.length()) : name, chosen);
        }
    }

    private void addBleChoice(String name, String key) {
        // Two adapters can share a name; the end of the address tells them apart
        View row = addChoice(getString(R.string.obd_ble_adapter, name, key.substring(key.length() - 5)), key);
        row.setOnClickListener(v -> {
            chosen = key;
            chosenName = name;
            render();
        });
    }

    private View addChoice(String label, String key) {
        boolean on = key.equals(chosen);
        View row = addRow(label, on ? getString(R.string.obd_selected) : "");
        row.setOnClickListener(v -> {
            chosen = key;
            render();
        });
        return row;
    }

    private void renderValues(ObdSnapshot snapshot) {
        addHeader(R.string.obd_values);
        addRow(getString(R.string.obd_12v_battery),
                snapshot.volts == null ? "—" : String.format(Locale.US, "%.1f V", snapshot.volts));
        for (ObdPid pid : ObdPid.values()) {
            if (snapshot.supported.contains(pid.pid)) {
                Double value = snapshot.values.get(pid);
                addRow(getString(pid.labelRes), value == null ? "—" : pid.format(value));
            }
        }
        renderZeekr(snapshot);
        if (snapshot.state == ObdSnapshot.State.LIVE && !snapshot.supported.isEmpty()) {
            renderCodes(snapshot);
            addHeader(R.string.obd_supported);
            addText(supportedText(snapshot));
        }
    }

    private void renderZeekr(ObdSnapshot snapshot) {
        if (snapshot.zeekr.isEmpty()) {
            return;
        }
        addHeader(R.string.obd_zeekr);
        for (Map.Entry<Integer, ZeekrProbe.Result> entry : snapshot.zeekr.entrySet()) {
            addRow(ZeekrProbe.request(entry.getKey()), zeekrText(entry.getValue()));
        }
        addNote(R.string.obd_zeekr_note);
    }

    private String zeekrText(ZeekrProbe.Result result) {
        if (result.hex != null) {
            return result.hex;
        }
        if (result.refusal < 0) {
            return getString(R.string.obd_zeekr_none);
        }
        String code = String.format(Locale.ROOT, "%02X", result.refusal);
        return getString(result.refusal == 0x33 ? R.string.obd_zeekr_locked : R.string.obd_zeekr_refused, code);
    }

    private void renderCodes(ObdSnapshot snapshot) {
        addHeader(R.string.obd_codes);
        addRow(getString(R.string.obd_codes_stored), codesText(snapshot.storedCodes));
        addRow(getString(R.string.obd_codes_pending), codesText(snapshot.pendingCodes));
        addNote(R.string.obd_read_only);
    }

    private String codesText(List<String> codes) {
        if (codes == null) {
            return "—";
        }
        return codes.isEmpty() ? getString(R.string.obd_codes_none) : String.join(" ", codes);
    }

    /** Every PID the car says it has, known or not: for working out what else to read. */
    private static String supportedText(ObdSnapshot snapshot) {
        StringBuilder text = new StringBuilder();
        for (int pid : snapshot.supported) {
            text.append(String.format(Locale.ROOT, "%02X ", pid));
        }
        return text.toString().trim();
    }

    private void addHeader(int textRes) {
        TextView header = text(R.style.TextAppearance_Cam_Caption, R.color.text_secondary);
        header.setText(textRes);
        header.setPadding(dp(16), dp(16), dp(16), dp(4));
        list.addView(header);
    }

    private void addNote(int textRes) {
        addText(getString(textRes));
    }

    private void addText(String content) {
        TextView note = text(R.style.TextAppearance_Cam_Caption, R.color.text_secondary);
        note.setText(content);
        note.setPadding(dp(16), dp(8), dp(16), dp(8));
        list.addView(note);
    }

    /** One line: label on the left, value on the right. */
    private View addRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(16), 0, dp(16), 0);

        TextView name = text(R.style.TextAppearance_Cam_Body, R.color.text_primary);
        name.setText(label);
        name.setGravity(Gravity.CENTER_VERTICAL);
        name.setMinHeight(dp(56));
        // A fixed label column keeps each value next to its name on the wide screen
        row.addView(name, new LinearLayout.LayoutParams(dp(LABEL_DP), LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView shown = text(R.style.TextAppearance_Cam_Body, R.color.text_primary);
        shown.setText(value);
        row.addView(shown, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        list.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private TextView text(int appearance, int color) {
        TextView view = new TextView(this);
        view.setTextAppearance(appearance);
        view.setTextColor(ContextCompat.getColor(this, color));
        view.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
