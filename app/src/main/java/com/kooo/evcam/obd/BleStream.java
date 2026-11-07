package com.kooo.evcam.obd;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * Makes Bluetooth LE look like the serial streams the ELM327 code reads: notifications go in one
 * end, {@link #input} reads them; {@link #output} cuts what is written into packets. A lost link
 * shows up as an exception once what already arrived has been read, as a socket would.
 */
final class BleStream {

    /** Sends one packet and returns when the stack has taken it. */
    interface Sender {
        void send(byte[] packet) throws IOException;
    }

    /** Fits the smallest packet BLE allows, on any adapter: requests are short. */
    static final int PACKET = 20;

    private final ArrayDeque<byte[]> arrived = new ArrayDeque<>();
    private final Sender sender;
    private int headAt;
    private int waiting;
    private boolean closed;

    private final InputStream in = new InputStream() {
        @Override
        public int available() throws IOException {
            synchronized (arrived) {
                if (waiting == 0 && closed) {
                    throw new IOException("Bluetooth LE link lost");
                }
                return waiting;
            }
        }

        @Override
        public int read() {
            synchronized (arrived) {
                byte[] head = arrived.peek();
                if (head == null) {
                    return -1;
                }
                int value = head[headAt++] & 0xFF;
                waiting--;
                if (headAt == head.length) {
                    arrived.poll();
                    headAt = 0;
                }
                return value;
            }
        }
    };

    private final OutputStream out = new OutputStream() {
        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            for (int at = offset; at < offset + length; at += PACKET) {
                sender.send(Arrays.copyOfRange(bytes, at, Math.min(at + PACKET, offset + length)));
            }
        }
    };

    BleStream(Sender sender) {
        this.sender = sender;
    }

    InputStream input() {
        return in;
    }

    OutputStream output() {
        return out;
    }

    /** A notification arrived. Binder thread. */
    void received(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        synchronized (arrived) {
            arrived.add(bytes.clone());
            waiting += bytes.length;
        }
    }

    void close() {
        synchronized (arrived) {
            closed = true;
        }
    }
}
