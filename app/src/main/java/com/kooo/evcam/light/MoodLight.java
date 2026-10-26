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
 * (power, colour, brightness), the newest wins, one write in flight every {@link #WRITE_EVERY_MS}.</p>
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
    private static final long WRITE_EVERY_MS = 40L;

    private final Context app;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("mood-light");
    private final Handler g;
    private final Map<String, byte[]> pending = new LinkedHashMap<>();
    private final Runnable timeout = () -> fail(FAIL_TIMEOUT, 0);
    private final Runnable pump = this::pump;

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic write;
    private LightProtocol.Family family;
    private boolean pumping;

    public MoodLight(Context context, Listener listener) {
        this.app = context.getApplicationContext();
        this.listener = listener;
        thread.start();
        g = new Handler(thread.getLooper());
    }

    /** Drop any link and dial this address. */
    @Override
    public void connect(String address) {
        g.post(() -> {
            teardown();
            BluetoothManager manager = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                down(FAIL_BLUETOOTH, 0);
                return;
            }
            BluetoothDevice device;
            try {
                device = adapter.getRemoteDevice(address);
            } catch (IllegalArgumentException e) {
                AppLog.w(TAG, "bad address " + address);
                down(FAIL_GATT, -1);
                return;
            }
            main.post(listener::onLightConnecting);
            try {
                gatt = device.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE,
                        BluetoothDevice.PHY_LE_1M, g);
            } catch (SecurityException e) {
                down(FAIL_BLUETOOTH, 0);
                return;
            }
            if (gatt == null) {
                down(FAIL_GATT, -1);
                return;
            }
            AppLog.d(TAG, "dial " + address);
            g.postDelayed(timeout, CONNECT_TIMEOUT_MS);
        });
    }

    @Override
    public void power(boolean on) {
        g.post(() -> {
            if (family != null) {
                // Colour sent before the light was on is lost on some clones: power goes first.
                pending.remove("power");
                Map<String, byte[]> rest = new LinkedHashMap<>(pending);
                pending.clear();
                pending.put("power", LightProtocol.power(family, on));
                pending.putAll(rest);
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
            pending.put("color", LightProtocol.color(family, rgb, brightness));
            byte[] level = LightProtocol.brightness(family, brightness);
            if (level != null) {
                pending.put("brightness", level);
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
            if (effect == LightProtocol.Effect.OFF) {
                pending.clear();
                pending.put("power", LightProtocol.power(family, false));
                schedule();
                return;
            }
            // Every slot is rewritten; power first, as in power(), since colour sent while off is lost
            pending.clear();
            pending.put("power", LightProtocol.power(family, true));
            if (effect == LightProtocol.Effect.STATIC) {
                pending.put("color", LightProtocol.color(family, l.color, l.brightness));
            } else {
                pending.put("color", LightProtocol.effect(family, effect, l.color, l.speed));
                byte[] speed = LightProtocol.effectSpeed(family, l.speed);
                if (speed != null) {
                    pending.put("speed", speed);
                }
            }
            byte[] level = LightProtocol.brightness(family, l.brightness);
            if (level != null) {
                pending.put("brightness", level);
            }
            schedule();
        });
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
        if (gatt == null || write == null || pending.isEmpty()) {
            pumping = false;
            return;
        }
        Iterator<Map.Entry<String, byte[]>> it = pending.entrySet().iterator();
        Map.Entry<String, byte[]> next = it.next();
        if (send(next.getValue())) {
            it.remove();
        }
        g.postDelayed(pump, WRITE_EVERY_MS);
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
        pumping = false;
        pending.clear();
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
