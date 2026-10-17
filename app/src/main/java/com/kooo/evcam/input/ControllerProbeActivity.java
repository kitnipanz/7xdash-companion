package com.kooo.evcam.input;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shows what a paired controller or sticker actually sends, then saves that
 * press as a shortcut when an action is picked.
 */
public class ControllerProbeActivity extends AppCompatActivity implements StickerHub.Watch {

    private static final int MAX_LINES = 80;
    private static final float AXIS_STEP = 0.08f;
    private static final int ASK_BLUETOOTH = 41;

    private static final int[] AXES = {
            MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z,
            MotionEvent.AXIS_RZ, MotionEvent.AXIS_RX, MotionEvent.AXIS_RY,
            MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y,
            MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
            MotionEvent.AXIS_GAS, MotionEvent.AXIS_BRAKE,
            MotionEvent.AXIS_THROTTLE, MotionEvent.AXIS_RUDDER, MotionEvent.AXIS_WHEEL
    };

    private TextView devicesView;
    private TextView liveView;
    private TextView logView;
    private View register;
    private Shortcut pending;
    private String pendingLabel = "";
    private PressKind pressKind = PressKind.TAP;
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private final Map<Integer, float[]> lastAxes = new HashMap<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private InputManager inputs;
    private final InputManager.InputDeviceListener devices = new InputManager.InputDeviceListener() {
        @Override
        public void onInputDeviceAdded(int deviceId) {
            refreshDevices();
        }

        @Override
        public void onInputDeviceRemoved(int deviceId) {
            lastAxes.remove(deviceId);
            refreshDevices();
        }

        @Override
        public void onInputDeviceChanged(int deviceId) {
            refreshDevices();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_controller_probe);
        devicesView = findViewById(R.id.controller_devices);
        liveView = findViewById(R.id.controller_live);
        logView = findViewById(R.id.controller_log);
        register = findViewById(R.id.controller_register);
        findViewById(R.id.controller_close).setOnClickListener(v -> finish());
        findViewById(R.id.controller_clear).setOnClickListener(v -> {
            lines.clear();
            logView.setText("");
            liveView.setText(R.string.ctrl_waiting);
        });
        bindPress(R.id.controller_press_tap, PressKind.TAP);
        bindPress(R.id.controller_press_long, PressKind.LONG);
        bindPress(R.id.controller_press_double, PressKind.DOUBLE);
        bind(R.id.controller_action_record, ShortcutAction.RECORD);
        bind(R.id.controller_action_mirror, ShortcutAction.MIRROR);
        bind(R.id.controller_action_mirror_hud, ShortcutAction.MIRROR_HUD);
        bind(R.id.controller_action_both_mirrors, ShortcutAction.BOTH_MIRRORS);
        bind(R.id.controller_action_app, ShortcutAction.APP);
        bind(R.id.controller_action_dim, ShortcutAction.DIM);
        bind(R.id.controller_action_save, ShortcutAction.SAVE);
        bind(R.id.controller_action_lock_save, ShortcutAction.LOCK_SAVE);
        bind(R.id.controller_action_instant_capture, ShortcutAction.INSTANT_CAPTURE);
        bind(R.id.controller_action_hold_speak, ShortcutAction.HOLD_SPEAK);
        bind(R.id.controller_action_toggle_speak, ShortcutAction.TOGGLE_SPEAK);
        bind(R.id.controller_action_cabin, ShortcutAction.CABIN_PASSENGER);
        highlightPress();
        inputs = (InputManager) getSystemService(INPUT_SERVICE);
        if (Build.VERSION.SDK_INT >= 31
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, ASK_BLUETOOTH);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (inputs != null) {
            inputs.registerInputDeviceListener(devices, null);
        }
        StickerHub.addWatch(this);
        StickerHub.sync(this);
        refreshDevices();
    }

    @Override
    protected void onPause() {
        if (inputs != null) {
            inputs.unregisterInputDeviceListener(devices);
        }
        StickerHub.removeWatch(this);
        StickerHub.sync(this);
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == ASK_BLUETOOTH) {
            refreshDevices();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            return super.dispatchKeyEvent(event);
        }
        int action = event.getAction();
        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() > 0) {
            return true;
        }
        if (action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP) {
            String verb = getString(action == KeyEvent.ACTION_DOWN
                    ? R.string.ctrl_pressed : R.string.ctrl_released);
            String line = getString(R.string.ctrl_key,
                    clock.format(new Date()),
                    verb,
                    KeyEvent.keyCodeToString(code),
                    code,
                    event.getScanCode(),
                    deviceName(event.getDevice()));
            push(line);
            refreshDevices();
        }
        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            InputDevice device = event.getDevice();
            if (device == null || !device.isVirtual()) {
                String name = device == null || device.getName() == null ? "" : device.getName();
                Shortcut draft = new Shortcut(code, event.getScanCode(), name, "");
                offer(draft, PressKind.TAP, buttonName(draft));
                ShortcutKeys.dispatch(this, event);
            }
        }
        return true;
    }

    @Override
    public void onPacket(String address, byte[] raw) {
        StickerFrame frame = StickerFrame.parse(raw);
        String action;
        if (frame == null) {
            action = getString(R.string.ctrl_packet);
        } else {
            int label = StickerFrame.controlLabel(frame.control);
            String control = label == 0 ? String.valueOf(frame.control) : getString(label);
            action = control + " " + getString(frame.press.labelRes);
        }
        push(getString(R.string.ctrl_sticker, clock.format(new Date()), action, StickerFrame.hex(raw)));
        if (frame == null) {
            return;
        }
        String device = StickerFrame.deviceName(address);
        Shortcut draft = new Shortcut(frame.control, 0, device, "", frame.press.key);
        int label = StickerFrame.controlLabel(frame.control);
        String control = label == 0 ? String.valueOf(frame.control) : getString(label);
        offer(draft, frame.press, control + " " + StickerNames.label(this, address));
    }

    @Override
    public void onState(String address, boolean connected) {
        refreshDevices();
    }

    @Override
    public void onReady(String address) {
        refreshDevices();
    }

    @Override
    public void onFail(String address, int reason, int status) {
    }

    @Override
    public void onLog(String line) {
    }

    @Override
    public void onRssi(String address, int rssi) {
    }

    @Override
    public void onPhase(String address, int phase) {
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_MOVE) {
            return super.onGenericMotionEvent(event);
        }
        int id = event.getDeviceId();
        float[] previous = lastAxes.get(id);
        if (previous == null || previous.length != AXES.length) {
            previous = new float[AXES.length];
            lastAxes.put(id, previous);
        }
        StringBuilder changed = new StringBuilder();
        for (int i = 0; i < AXES.length; i++) {
            float value = event.getAxisValue(AXES[i]);
            if (Math.abs(value - previous[i]) < AXIS_STEP && Math.abs(value) < AXIS_STEP) {
                previous[i] = value;
                continue;
            }
            if (Math.abs(value - previous[i]) < AXIS_STEP) {
                continue;
            }
            previous[i] = value;
            if (changed.length() > 0) {
                changed.append(' ');
            }
            changed.append(MotionEvent.axisToString(AXES[i]))
                    .append('=')
                    .append(String.format(Locale.US, "%.2f", value));
        }
        if (changed.length() > 0) {
            push(getString(R.string.ctrl_axis,
                    clock.format(new Date()),
                    changed.toString(),
                    deviceName(event.getDevice())));
        }
        return true;
    }

    private void bindPress(int id, PressKind kind) {
        findViewById(id).setOnClickListener(v -> {
            pressKind = kind;
            highlightPress();
        });
    }

    private void bind(int id, ShortcutAction action) {
        findViewById(id).setOnClickListener(v -> save(action));
    }

    private void offer(Shortcut draft, PressKind kind, String label) {
        pending = draft;
        pendingLabel = label;
        pressKind = kind == null ? PressKind.TAP : kind;
        highlightPress();
        register.setVisibility(View.VISIBLE);
        liveView.setText(getString(R.string.shortcut_picked, label));
    }

    private void highlightPress() {
        paint(R.id.controller_press_tap, pressKind == PressKind.TAP);
        paint(R.id.controller_press_long, pressKind == PressKind.LONG);
        paint(R.id.controller_press_double, pressKind == PressKind.DOUBLE);
    }

    private void paint(int id, boolean on) {
        MaterialButton button = findViewById(id);
        button.setAlpha(on ? 1f : 0.45f);
    }

    private void save(ShortcutAction action) {
        if (pending == null) {
            return;
        }
        AppConfig config = new AppConfig(this);
        Shortcut next = new Shortcut(pending.keyCode, pending.scanCode, pending.deviceName,
                action.key, pressKind.key);
        config.setButtonShortcuts(ShortcutBook.write(
                ShortcutBook.put(ShortcutBook.parse(config.getButtonShortcuts()), next)));
        if (StickerFrame.isSticker(pending.deviceName)) {
            config.setStickerEnabled(true);
            config.setStickerDevices(StickerDevices.write(StickerDevices.put(
                    StickerDevices.parse(config.getStickerDevices()),
                    StickerFrame.address(pending.deviceName))));
            StickerHub.sync(this);
        } else {
            KeyCatcher.sync(this);
        }
        liveView.setText(getString(R.string.sticker_saved_action,
                pendingLabel + " " + getString(pressKind.labelRes),
                getString(action.labelRes)));
    }

    private String buttonName(Shortcut item) {
        if (item.keyCode != 0) {
            String name = KeyEvent.keyCodeToString(item.keyCode);
            String prefix = "KEYCODE_";
            if (name.startsWith(prefix)) {
                name = name.substring(prefix.length());
            }
            return name;
        }
        return getString(R.string.shortcut_scan, item.scanCode);
    }

    private void push(String line) {
        lines.addFirst(line);
        while (lines.size() > MAX_LINES) {
            lines.removeLast();
        }
        StringBuilder text = new StringBuilder();
        for (String row : lines) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(row);
        }
        logView.setText(text.toString());
        liveView.setText(line);
    }

    private void refreshDevices() {
        StringBuilder text = new StringBuilder();
        int[] ids = InputDevice.getDeviceIds();
        int shown = 0;
        for (int id : ids) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || device.isVirtual()) {
                continue;
            }
            int sources = device.getSources();
            boolean input = (sources & (InputDevice.SOURCE_GAMEPAD
                    | InputDevice.SOURCE_JOYSTICK
                    | InputDevice.SOURCE_KEYBOARD
                    | InputDevice.SOURCE_DPAD
                    | InputDevice.SOURCE_MOUSE
                    | InputDevice.SOURCE_CLASS_BUTTON)) != 0;
            if (!input) {
                continue;
            }
            if (shown > 0) {
                text.append('\n');
            }
            text.append(device.getName())
                    .append("  id=").append(id)
                    .append("  ").append(sourceNames(sources));
            shown++;
        }
        appendPaired(text, shown > 0);
        if (text.length() == 0) {
            devicesView.setText(R.string.ctrl_devices_none);
        } else {
            devicesView.setText(text.toString());
        }
    }

    private void appendPaired(StringBuilder text, boolean hadInput) {
        if (Build.VERSION.SDK_INT >= 31
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            return;
        }
        Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (SecurityException e) {
            return;
        }
        if (bonded == null || bonded.isEmpty()) {
            return;
        }
        boolean any = false;
        try {
        for (BluetoothDevice device : bonded) {
            BluetoothClass klass = device.getBluetoothClass();
            int major = klass == null ? -1 : klass.getMajorDeviceClass();
            if (major != BluetoothClass.Device.Major.PERIPHERAL
                    && major != BluetoothClass.Device.Major.UNCATEGORIZED) {
                continue;
            }
            if (!any) {
                if (hadInput || text.length() > 0) {
                    text.append("\n\n");
                }
                text.append(getString(R.string.ctrl_paired));
                any = true;
            }
            text.append('\n').append(device.getName() == null ? "?" : device.getName())
                    .append("  ").append(device.getAddress());
        }
        } catch (SecurityException ignored) {
            // Name and address need BLUETOOTH_CONNECT. The key log still works.
        }
    }

    private static String deviceName(InputDevice device) {
        return device == null ? "?" : device.getName();
    }

    static String sourceNames(int sources) {
        StringBuilder names = new StringBuilder();
        add(names, sources, InputDevice.SOURCE_GAMEPAD, "GAMEPAD");
        add(names, sources, InputDevice.SOURCE_JOYSTICK, "JOYSTICK");
        add(names, sources, InputDevice.SOURCE_KEYBOARD, "KEYBOARD");
        add(names, sources, InputDevice.SOURCE_DPAD, "DPAD");
        add(names, sources, InputDevice.SOURCE_MOUSE, "MOUSE");
        if (names.length() == 0) {
            names.append("0x").append(Integer.toHexString(sources));
        }
        return names.toString();
    }

    private static void add(StringBuilder names, int sources, int flag, String label) {
        if ((sources & flag) == flag) {
            if (names.length() > 0) {
                names.append('|');
            }
            names.append(label);
        }
    }
}
