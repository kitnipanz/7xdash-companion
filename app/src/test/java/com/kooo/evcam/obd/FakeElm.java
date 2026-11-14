package com.kooo.evcam.obd;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/** An ELM327 on a script: command to reply, "?" for anything else. A null reply stays silent. */
final class FakeElm implements ObdLink {

    final Map<String, String> replies = Collections.synchronizedMap(new HashMap<>());
    final List<String> received = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Queue<Integer> toRead = new ArrayDeque<>();
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    volatile boolean failOpen;
    volatile boolean closed;

    private final InputStream in = new InputStream() {
        @Override
        public int available() {
            synchronized (toRead) {
                return toRead.size();
            }
        }

        @Override
        public int read() {
            synchronized (toRead) {
                Integer next = toRead.poll();
                return next == null ? -1 : next;
            }
        }
    };

    private final OutputStream out = new OutputStream() {
        @Override
        public void write(int b) {
            if (b != '\r') {
                line.write(b);
                return;
            }
            String command = new String(line.toByteArray(), StandardCharsets.US_ASCII);
            line.reset();
            received.add(command);
            String reply = replies.containsKey(command) ? replies.get(command)
                    : command.isEmpty() ? "STOPPED" : "?";
            if (reply != null) {
                synchronized (toRead) {
                    for (byte each : (reply + "\r\r>").getBytes(StandardCharsets.ISO_8859_1)) {
                        toRead.add(each & 0xFF);
                    }
                }
            }
        }
    };

    FakeElm reply(String command, String reply) {
        replies.put(command, reply);
        return this;
    }

    /** A car on CAN that has speed, the battery level, and nothing else. */
    static FakeElm car() {
        return new FakeElm()
                .reply("ATZ", "\r\rELM327 v1.5")
                .reply("ATE0", "OK").reply("ATL0", "OK").reply("ATS0", "OK").reply("ATH0", "OK")
                .reply("ATAT1", "OK").reply("ATSP0", "OK")
                .reply("ATRV", "12.6V")
                // PIDs 0D (speed) and 20 (next block), then 5B (battery) in the block from 40
                .reply("0100", "SEARCHING...\r41 00 00 08 00 01")
                .reply("0120", "41 20 00 00 00 01")
                .reply("0140", "41 40 00 00 00 20")
                .reply("ATDPN", "A6")
                .reply("ATDP", "AUTO, ISO 15765-4 (CAN 11/500)")
                .reply("010D", "41 0D 2A")
                .reply("015B", "41 5B FF");
    }

    @Override
    public void open() throws IOException {
        if (failOpen) {
            throw new IOException("refused");
        }
    }

    @Override
    public InputStream input() {
        return in;
    }

    @Override
    public OutputStream output() {
        return out;
    }

    @Override
    public void close() {
        closed = true;
    }
}
