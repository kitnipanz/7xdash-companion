package com.kooo.evcam.obd;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * One request at a time over an ELM327: write a command, read up to its '>' prompt.
 *
 * <p><b>This app only reads the car.</b> {@link #ALLOWED} is the whole list of what may go down the
 * wire: adapter setup, live data (mode 01), reading trouble codes (modes 03 and 07) and the
 * fixed identifiers of {@link ZeekrProbe} (UDS read by identifier, to one fixed address). Clearing
 * codes (mode 04) or anything that writes to a module is refused here, whoever asks. Monitor mode
 * (ATMA) sends nothing to the car; with ATCSM1 the adapter does not even acknowledge frames.</p>
 */
final class ElmSession {

    /** What the adapter hears in monitor mode, a line at a time. */
    interface Lines {
        void line(String text) throws IOException;
    }

    /** Lines of the conversation, for the log. */
    interface Trace {
        void line(String text);
    }

    private static final Pattern ALLOWED = Pattern.compile(
            "^(AT(Z|D|E0|L0|S0|H0|H1|AL|AT1|SP[0-9A-C]|DP|DPN|RV|ST64|MA|CSM1"
                    + "|SHD01635|CP1D|CRA1EC6AE80|FCSH1DD01635|FCSD300000|FCSM1)"
                    + "|01[0-9A-F]{2}|03|07|224801|22491B)$");
    private static final long POLL_MS = 5L;
    private static final long STOP_MS = 2_000L;

    private final InputStream in;
    private final OutputStream out;
    private final Trace trace;

    ElmSession(InputStream in, OutputStream out, Trace trace) {
        this.in = in;
        this.out = out;
        this.trace = trace;
    }

    static boolean allowed(String command) {
        return ALLOWED.matcher(command).matches();
    }

    /**
     * Monitor mode for up to {@code windowMs}: any byte sent ends it. Returns early when the adapter
     * stops by itself, such as on "BUFFER FULL".
     */
    void monitor(long windowMs, Lines lines) throws IOException {
        command("ATMA");
        StringBuilder line = new StringBuilder();
        long now = System.nanoTime();
        long end = now + windowMs * 1_000_000L;
        long giveUp = end + STOP_MS * 1_000_000L;
        boolean stopping = false;
        while (true) {
            if (!stopping && System.nanoTime() - end >= 0) {
                stopping = true;
                out.write('\r');
                out.flush();
            }
            if (in.available() <= 0) {
                if (System.nanoTime() - giveUp >= 0) {
                    throw new IOException("adapter did not leave monitor mode");
                }
                pause();
                continue;
            }
            int next = in.read();
            if (next < 0) {
                throw new IOException("adapter closed the link");
            }
            if (next == '>') {
                deliver(line, lines);
                return;
            }
            if (next == '\r' || next == '\n') {
                deliver(line, lines);
            } else if (next != 0) {
                line.append((char) next);
            }
        }
    }

    private static void deliver(StringBuilder line, Lines lines) throws IOException {
        String text = line.toString().trim();
        line.setLength(0);
        if (!text.isEmpty()) {
            lines.line(text);
        }
    }

    private void command(String command) throws IOException {
        if (!allowed(command)) {
            throw new IllegalArgumentException("not a read-only command: " + command);
        }
        // A late answer to an earlier request must not pass for this one
        while (in.available() > 0) {
            in.read();
        }
        out.write((command + "\r").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    /** The adapter's answer without the prompt, or null when no prompt came in time. */
    String command(String command, long timeoutMs) throws IOException {
        command(command);

        StringBuilder reply = new StringBuilder();
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (true) {
            int waiting = in.available();
            if (waiting <= 0) {
                if (System.nanoTime() - deadline >= 0) {
                    trace.line(command + " -> timeout after " + reply);
                    return null;
                }
                pause();
                continue;
            }
            for (int i = 0; i < waiting; i++) {
                int next = in.read();
                if (next < 0) {
                    throw new IOException("adapter closed the link");
                }
                if (next == '>') {
                    String text = reply.toString();
                    trace.line(command + " -> " + text.trim().replaceAll("\\s*[\\r\\n]+\\s*", " | "));
                    return text;
                }
                if (next != 0) {
                    // Some adapters send NUL bytes; the data sheet says to ignore them.
                    reply.append((char) next);
                }
            }
        }
    }

    private static void pause() throws IOException {
        try {
            Thread.sleep(POLL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("stopped");
        }
    }
}
