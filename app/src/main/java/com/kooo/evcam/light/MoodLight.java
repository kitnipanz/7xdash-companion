package com.kooo.evcam.light;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.kooo.evcam.AppLog;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One GATT link to a mood light, open while its screen is.
 *
 * <p>All state lives on one thread ({@code mood-light}, where GATT callbacks also land). A drag
 * on a slider sends a packet per frame, so writes are coalesced: one pending packet per kind
 * (power, colour, brightness), the newest wins, one write in flight every {@link #WRITE_EVERY_MS}.
 * Writes go without response, so one can be lost without a word: when writes go quiet the last
 * state is sent once more ({@link #settle}).</p>
 */
public final class MoodLight implements LightLink {

    public static final int FAIL_BLUETOOTH = 1;
    public static final int FAIL_TIMEOUT = 2;
    public static final int FAIL_NOT_A_LIGHT = 3;
    public static final int FAIL_GATT = 4;

    public interface Listener {
        void onLightConnecting();

        void onLightReady(LightProtocol.Family family);

        /** @param reason one of {@code FAIL_*}, 0 for a plain disconnect */
        void onLightDown(int reason, int status);
    }

    private static final String TAG = "MoodLight";
    private static final long CONNECT_TIMEOUT_MS = 12_000L;
    /** A dial that waits for the light to advertise (autoConnect): Android keeps at it, we give it this long. */
    private static final long WAIT_TIMEOUT_MS = 60_000L;
    /**
     * Lights heard in a scan this run (the Mood light screen's, or {@link GearLight}'s background
     * one). A device from a scan carries its address type; one made from the address alone does
     * not, and after a reboot a light with a random address may never answer a dial made that way.
     */
    private static final java.util.Map<String, BluetoothDevice> HEARD =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** A scan heard this device: later dials to its address use it. */
    static void heard(BluetoothDevice device) {
        if (device != null && device.getAddress() != null) {
            HEARD.put(device.getAddress(), device);
        }
    }
    private static final long WRITE_EVERY_MS = 40L;
    /** After power: some clones bring back their last colour first, so the next packet waits. */
    private static final long AFTER_POWER_MS = 200L;
    /** Quiet this long after the last write, then the last state goes once more. */
    private static final long SETTLE_MS = 500L;

    private static final String POWER = "power";
    private static final String COLOR = "color";
    private static final String BRIGHTNESS = "brightness";
    private static final String SPEED = "speed";

    private final Context app;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("mood-light");
    private final Handler g;
    private final Map<String, byte[]> pending = new LinkedHashMap<>();
    private final Runnable timeout = () -> fail(FAIL_TIMEOUT, 0);
    private final Runnable pump = this::pump;
    private final Runnable settle = this::settle;
    /** The light's last wanted state, power first: sent once more when writes go quiet. */
    private final Map<String, byte[]> latest = new LinkedHashMap<>();

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic write;
    private LightProtocol.Family family;
    private boolean pumping;
    /** Power last sent; null when not known (just connected). */
    private Boolean lit;
    /** The last state has been sent twice; nothing to repeat until it changes. */
    private boolean settled;

    public MoodLight(Context context, Listener listener) {
        this.app = context.getApplicationContext();
        this.listener = listener;
        thread.start();
        g = new Handler(thread.getLooper());
    }

    /** Drop any link and dial this address. */
    @Override
    public void connect(String address) {
        dial(address, false);
    }

    @Override
    public void redial(String address, boolean waitForIt) {
        dial(address, waitForIt);
    }

    private void dial(String address, boolean waitForIt) {
        g.post(() -> {
            teardown();
            BluetoothManager manager = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                down(FAIL_BLUETOOTH, 0);
                return;
            }
            BluetoothDevice device = HEARD.get(address);
            try {
                if (device == null) {
                    device = adapter.getRemoteDevice(address);
                }
            } catch (IllegalArgumentException e) {
                AppLog.w(TAG, "bad address " + address);
                down(FAIL_GATT, -1);
                return;
            }
            main.post(listener::onLightConnecting);
            try {
                gatt = device.connectGatt(app, waitForIt, callback, BluetoothDevice.TRANSPORT_LE,
                        BluetoothDevice.PHY_LE_1M, g);
            } catch (SecurityException e) {
                down(FAIL_BLUETOOTH, 0);
                return;
            }
            if (gatt == null) {
                down(FAIL_GATT, -1);
                return;
            }
            AppLog.d(TAG, "dial " + address + (waitForIt ? " (waiting for it)" : "")
                    + (HEARD.containsKey(address) ? " (heard in a scan)" : ""));
            g.postDelayed(timeout, waitForIt ? WAIT_TIMEOUT_MS : CONNECT_TIMEOUT_MS);
        });
    }

    @Override
    public void power(boolean on) {
        g.post(() -> {
            if (family != null) {
                // Colour sent before the light was on is lost on some clones: power goes first.
                Map<String, byte[]> rest = new LinkedHashMap<>(pending);
                rest.remove(POWER);
                pending.clear();
                put(POWER, LightProtocol.power(family, on));
                pending.putAll(rest);
                if (!on) {
                    latest.keySet().retainAll(java.util.Collections.singleton(POWER));
                }
                schedule();
            }
        });
    }

    @Override
    public void color(int rgb, int brightness) {
        g.post(() -> {
            if (family == null) {
                return;
            }
            put(COLOR, LightProtocol.color(family, rgb, brightness));
            byte[] level = LightProtocol.brightness(family, brightness);
            if (level != null) {
                put(BRIGHTNESS, level);
            }
            schedule();
        });
    }

    /**
     * Power, colour or animation, brightness and speed in one go. Colour and animation share one
     * slot, so the newest of the two wins.
     */
    @Override
    public void show(LightLook look) {
        LightLook l = look.copy();
        g.post(() -> {
            if (family == null) {
                return;
            }
            LightProtocol.Effect effect = l.effect();
            pending.clear();
            latest.clear();
            if (effect == LightProtocol.Effect.OFF) {
                put(POWER, LightProtocol.power(family, false));
                schedule();
                return;
            }
            // Power on only when it is not on already: some clones answer power-on by bringing back
            // their last colour, which can land after (and over) the colour sent next.
            byte[] on = LightProtocol.power(family, true);
            if (!Boolean.TRUE.equals(lit)) {
                put(POWER, on);
            } else {
                // Not sent now, but repeated by settle in case the first one was lost
                latest.put(POWER, on);
            }
            if (effect == LightProtocol.Effect.STATIC) {
                put(COLOR, LightProtocol.color(family, l.color, l.brightness));
            } else {
                put(COLOR, LightProtocol.effect(family, effect, l.color, l.speed));
                byte[] speed = LightProtocol.effectSpeed(family, l.speed);
                if (speed != null) {
                    put(SPEED, speed);
                }
            }
            byte[] level = LightProtocol.brightness(family, l.brightness);
            if (level != null) {
                put(BRIGHTNESS, level);
            }
            schedule();
        });
    }

    /** Queue a packet; it is also kept as the light's last wanted state, for {@link #settle}. */
    private void put(String kind, byte[] packet) {
        pending.put(kind, packet);
        latest.put(kind, packet);
        settled = false;
        g.removeCallbacks(settle);
    }

    /** Close the link and stop the thread. The object is spent after this. */
    @Override
    public void release() {
        g.post(() -> {
            teardown();
            thread.quitSafely();
        });
    }

    // =================================================================== link

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt changed, int status, int newState) {
            if (changed != gatt) {
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                try {
                    if (!changed.discoverServices()) {
                        fail(FAIL_GATT, status);
                    }
                } catch (SecurityException e) {
                    fail(FAIL_BLUETOOTH, 0);
                }
                return;
            }
            AppLog.d(TAG, "link down status=" + status + " state=" + newState);
            fail(family == null ? FAIL_GATT : 0, status);
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt discovered, int status) {
            if (discovered != gatt) {
                return;
            }
            g.removeCallbacks(timeout);
            List<UUID> uuids = new ArrayList<>();
            List<BluetoothGattCharacteristic> all = new ArrayList<>();
            for (BluetoothGattService service : discovered.getServices()) {
                for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                    uuids.add(c.getUuid());
                    all.add(c);
                }
            }
            LightProtocol.Family found = LightProtocol.detect(uuids);
            if (found == null) {
                AppLog.d(TAG, "no light characteristic in " + uuids);
                fail(FAIL_NOT_A_LIGHT, status);
                return;
            }
            for (BluetoothGattCharacteristic c : all) {
                if (found.write.equals(c.getUuid())) {
                    write = c;
                    break;
                }
            }
            family = found;
            AppLog.d(TAG, "ready " + found);
            main.post(() -> listener.onLightReady(found));
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt written, BluetoothGattCharacteristic c, int status) {
            // Paced by WRITE_EVERY_MS, not by this callback: some clones never send it.
        }
    };

    private void schedule() {
        if (!pumping && !pending.isEmpty()) {
            pumping = true;
            g.post(pump);
        }
    }

    private void pump() {
        if (gatt == null || write == null) {
            pumping = false;
            return;
        }
        if (pending.isEmpty()) {
            pumping = false;
            if (!settled && !latest.isEmpty()) {
                g.postDelayed(settle, SETTLE_MS);
            }
            return;
        }
        Iterator<Map.Entry<String, byte[]>> it = pending.entrySet().iterator();
        Map.Entry<String, byte[]> next = it.next();
        long wait = WRITE_EVERY_MS;
        if (send(next.getValue())) {
            it.remove();
            if (POWER.equals(next.getKey())) {
                lit = java.util.Arrays.equals(next.getValue(), LightProtocol.power(family, true));
                wait = AFTER_POWER_MS;
            }
        }
        g.postDelayed(pump, wait);
    }

    /** The last state once more, in case a write was lost; once per change. */
    private void settle() {
        if (settled || gatt == null || write == null || latest.isEmpty()) {
            return;
        }
        settled = true;
        pending.putAll(latest);
        schedule();
    }

    /** @return false when the stack was busy; the packet stays queued */
    @SuppressWarnings("deprecation")
    private boolean send(byte[] packet) {
        int type = (write.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return gatt.writeCharacteristic(write, packet, type) == BluetoothGatt.GATT_SUCCESS;
            }
            write.setWriteType(type);
            write.setValue(packet);
            return gatt.writeCharacteristic(write);
        } catch (SecurityException e) {
            AppLog.w(TAG, "write denied");
            return true;
        }
    }

    private void fail(int reason, int status) {
        teardown();
        down(reason, status);
    }

    private void down(int reason, int status) {
        main.post(() -> listener.onLightDown(reason, status));
    }

    private void teardown() {
        g.removeCallbacks(timeout);
        g.removeCallbacks(pump);
        g.removeCallbacks(settle);
        pumping = false;
        pending.clear();
        latest.clear();
        settled = false;
        lit = null;
        write = null;
        family = null;
        BluetoothGatt old = gatt;
        gatt = null;
        if (old != null) {
            try {
                old.disconnect();
                old.close();
            } catch (SecurityException ignored) {
                // permission dropped
            }
        }
    }
}
