package com.kooo.evcam.obd;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * A paired Bluetooth Classic adapter, over the serial port profile.
 *
 * <p>Cheap adapters often have a broken service record, so this falls back from the secure socket
 * to the insecure one, then to RFCOMM channel 1 directly.</p>
 */
final class BluetoothObdLink implements ObdLink {

    private static final UUID SERIAL_PORT = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private interface SocketMaker {
        BluetoothSocket make() throws Exception;
    }

    private final BluetoothAdapter radio;
    private final BluetoothDevice device;
    private volatile BluetoothSocket socket;
    private volatile boolean closed;

    BluetoothObdLink(BluetoothAdapter radio, BluetoothDevice device) {
        this.radio = radio;
        this.device = device;
    }

    @Override
    public void open() throws IOException {
        try {
            radio.cancelDiscovery();
        } catch (SecurityException e) {
            // Needs the scan permission; connecting only gets slower during discovery.
        }
        SocketMaker[] attempts = {
                () -> device.createRfcommSocketToServiceRecord(SERIAL_PORT),
                () -> device.createInsecureRfcommSocketToServiceRecord(SERIAL_PORT),
                () -> (BluetoothSocket) device.getClass()
                        .getMethod("createRfcommSocket", int.class).invoke(device, 1)
        };
        IOException last = new IOException("no socket");
        for (SocketMaker attempt : attempts) {
            if (closed) {
                throw new IOException("closed");
            }
            try {
                BluetoothSocket next = attempt.make();
                socket = next;
                next.connect();
                return;
            } catch (IOException e) {
                last = e;
            } catch (Exception e) {
                last = new IOException(e);
            }
            closeSocket();
        }
        throw last;
    }

    @Override
    public InputStream input() throws IOException {
        return requireSocket().getInputStream();
    }

    @Override
    public OutputStream output() throws IOException {
        return requireSocket().getOutputStream();
    }

    @Override
    public void close() {
        closed = true;
        closeSocket();
    }

    private BluetoothSocket requireSocket() throws IOException {
        BluetoothSocket current = socket;
        if (current == null) {
            throw new IOException("closed");
        }
        return current;
    }

    private void closeSocket() {
        BluetoothSocket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException e) {
                // going anyway
            }
        }
    }
}
