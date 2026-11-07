package com.kooo.evcam.obd;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Build;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A Bluetooth LE adapter. These need no pairing: connect, find the serial pipe ({@link BleUart}),
 * turn on notifications. {@link #open} walks those steps one at a time on the caller's thread,
 * answering to events from the GATT callback.
 */
final class BleObdLink implements ObdLink {

    private enum Step { CONNECTED, GONE, MTU, SERVICES, SUBSCRIBED, WRITTEN }

    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int ATTEMPTS = 3;
    private static final long CONNECT_MS = 15_000L;
    private static final long STEP_MS = 10_000L;
    private static final long WRITE_MS = 2_000L;
    private static final long RETRY_PAUSE_MS = 600L;
    private static final int WRITE_BUSY_TRIES = 20;
    private static final long WRITE_BUSY_MS = 25L;
    private static final int LARGE_MTU = 247;

    private final Context app;
    private final BluetoothDevice device;
    private final BlockingQueue<Step> steps = new LinkedBlockingQueue<>();
    private final BleStream stream = new BleStream(this::send);
    private volatile BluetoothGatt gatt;
    private volatile BluetoothGattCharacteristic writer;
    private volatile boolean closed;

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                steps.add(Step.CONNECTED);
            } else {
                // 133 from the stack lands here too, and a link lost mid-run
                stream.close();
                steps.add(Step.GONE);
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            steps.add(Step.MTU);
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            steps.add(status == BluetoothGatt.GATT_SUCCESS ? Step.SERVICES : Step.GONE);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            steps.add(status == BluetoothGatt.GATT_SUCCESS ? Step.SUBSCRIBED : Step.GONE);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            steps.add(status == BluetoothGatt.GATT_SUCCESS ? Step.WRITTEN : Step.GONE);
        }

        /** Below Android 13. */
        @Override
        @SuppressWarnings("deprecation")
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            stream.received(c.getValue());
        }

        /** Android 13 and up; the platform calls only this one when it is overridden. */
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            stream.received(value);
        }
    };

    BleObdLink(Context context, BluetoothDevice device) {
        app = context.getApplicationContext();
        this.device = device;
    }

    @Override
    public void open() throws IOException {
        IOException last = new IOException("no attempt");
        for (int attempt = 0; attempt < ATTEMPTS && !closed; attempt++) {
            try {
                connectOnce();
                return;
            } catch (InterruptedIOException e) {
                throw e;
            } catch (IOException e) {
                last = e;
                closeGatt();
                pause(RETRY_PAUSE_MS);
            }
        }
        throw last;
    }

    private void connectOnce() throws IOException {
        steps.clear();
        BluetoothGatt next = device.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE);
        if (next == null) {
            throw new IOException("connectGatt refused");
        }
        gatt = next;
        await(Step.CONNECTED, CONNECT_MS);
        // A bigger packet carries more of each answer. Not all adapters agree, which is fine.
        if (next.requestMtu(LARGE_MTU)) {
            try {
                await(Step.MTU, WRITE_MS);
            } catch (IOException e) {
                if (e instanceof InterruptedIOException || steps.contains(Step.GONE)) {
                    throw e;
                }
            }
        }
        if (!next.discoverServices()) {
            throw new IOException("discovery refused");
        }
        await(Step.SERVICES, STEP_MS);
        subscribe(next, chosen(next));
    }

    private static BleUart.Choice chosen(BluetoothGatt gatt) throws IOException {
        List<BleUart.Characteristic> found = new ArrayList<>();
        for (BluetoothGattService service : gatt.getServices()) {
            for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                int properties = c.getProperties();
                found.add(new BleUart.Characteristic(service.getUuid(), c.getUuid(),
                        (properties & (BluetoothGattCharacteristic.PROPERTY_NOTIFY
                                | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0,
                        (properties & (BluetoothGattCharacteristic.PROPERTY_WRITE
                                | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0));
            }
        }
        BleUart.Choice choice = BleUart.pick(found);
        if (choice == null) {
            throw new IOException("no serial service on this device");
        }
        return choice;
    }

    @SuppressWarnings("deprecation")
    private void subscribe(BluetoothGatt g, BleUart.Choice choice) throws IOException {
        BluetoothGattService service = g.getService(choice.service);
        BluetoothGattCharacteristic notify = service.getCharacteristic(choice.notify);
        writer = service.getCharacteristic(choice.write);
        if (!g.setCharacteristicNotification(notify, true)) {
            throw new IOException("notifications refused");
        }
        BluetoothGattDescriptor cccd = notify.getDescriptor(CCCD);
        if (cccd == null) {
            return;  // Nothing to switch on: some adapters notify regardless
        }
        byte[] on = (notify.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
        boolean started;
        if (Build.VERSION.SDK_INT >= 33) {
            started = g.writeDescriptor(cccd, on) == BluetoothStatusCodes.SUCCESS;
        } else {
            cccd.setValue(on);
            started = g.writeDescriptor(cccd);
        }
        if (!started) {
            throw new IOException("could not turn on notifications");
        }
        await(Step.SUBSCRIBED, STEP_MS);
    }

    /** One packet, waiting for the stack: it takes one write at a time. Reading thread only. */
    @SuppressWarnings("deprecation")
    private void send(byte[] packet) throws IOException {
        BluetoothGatt g = gatt;
        BluetoothGattCharacteristic c = writer;
        if (g == null || c == null) {
            throw new IOException("closed");
        }
        int type = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        for (int tries = 0; tries < WRITE_BUSY_TRIES; tries++) {
            boolean started;
            if (Build.VERSION.SDK_INT >= 33) {
                started = g.writeCharacteristic(c, packet, type) == BluetoothStatusCodes.SUCCESS;
            } else {
                c.setWriteType(type);
                c.setValue(packet);
                started = g.writeCharacteristic(c);
            }
            if (started) {
                await(Step.WRITTEN, WRITE_MS);
                return;
            }
            pause(WRITE_BUSY_MS);
        }
        throw new IOException("adapter would not take a write");
    }

    private void await(Step wanted, long timeoutMs) throws IOException {
        long end = System.nanoTime() + timeoutMs * 1_000_000L;
        while (true) {
            long left = end - System.nanoTime();
            Step next;
            try {
                next = left <= 0 ? null : steps.poll(left, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("stopped");
            }
            if (next == null) {
                throw new IOException("timed out waiting for " + wanted);
            }
            if (next == wanted) {
                return;
            }
            if (next == Step.GONE) {
                throw new IOException("adapter dropped the link at " + wanted);
            }
        }
    }

    private static void pause(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("stopped");
        }
    }

    @Override
    public InputStream input() {
        return stream.input();
    }

    @Override
    public OutputStream output() {
        return stream.output();
    }

    @Override
    public void close() {
        closed = true;
        stream.close();
        steps.add(Step.GONE);
        closeGatt();
    }

    private void closeGatt() {
        BluetoothGatt current = gatt;
        gatt = null;
        writer = null;
        if (current != null) {
            try {
                current.disconnect();
                current.close();
            } catch (SecurityException e) {
                // permission taken away; the link dies with the process
            }
        }
    }
}
