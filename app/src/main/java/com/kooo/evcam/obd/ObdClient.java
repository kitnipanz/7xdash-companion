package com.kooo.evcam.obd;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the car through an ELM327 on one background thread: set up the adapter, find the car,
 * ask what it supports, then go round the values until {@link #stop}.
 *
 * <p>A pure electric car may have nothing on standard OBD, so the direct battery read
 * ({@link ZeekrProbe}) is tried whether or not standard OBD answered. It needs the adapter set up
 * differently: with both working, it takes a turn every {@link #ZEEKR_EVERY} rounds; with only
 * it working, the adapter stays set up for it.</p>
 *
 * <p>An answer it cannot use blanks that value. Only the adapter going silent ends the run.</p>
 */
final class ObdClient {

    interface Listener {
        void onSnapshot(ObdSnapshot snapshot);

        void onTrace(String line);
    }

    private static final String[] SETUP = {"ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0"};
    private static final Pattern VOLTS = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*V");
    private static final int MAX_SILENT = 3;
    private static final int ZEEKR_EVERY = 10;
    /** Turns without a word from the battery module before giving up on it. */
    private static final int ZEEKR_MISSES = 3;
    private static final String ZEEKR_PROTOCOL = "ISO 15765-4 (CAN 29/500)";

    private static final long RESET_MS = 6_000L;
    private static final long SETUP_MS = 2_000L;
    /** ELM327 auto protocol search takes up to about 13 s. */
    private static final long SEARCH_MS = 16_000L;
    private static final long READ_MS = 2_000L;

    /** Between rounds, so the adapter and the car's gateway are not pushed flat out. */
    private final long roundPauseMs;
    private final long noCarPauseMs;

    private final ObdLink link;
    private final Listener listener;
    private final Thread thread;
    private volatile boolean stopped;
    private volatile boolean codesWanted;

    // The fields below belong to the reading thread
    private ElmSession session;
    private ObdSnapshot.State state = ObdSnapshot.State.CONNECTING;
    private ObdSnapshot.Failure failure = ObdSnapshot.Failure.NONE;
    private String adapter;
    private String protocol;
    private boolean can = true;
    private Float volts;
    private final Map<ObdPid, Double> values = new EnumMap<>(ObdPid.class);
    private final TreeSet<Integer> supported = new TreeSet<>();
    private List<String> storedCodes;
    private List<String> pendingCodes;
    private int silent;
    private String protocolNumber;
    private final Map<Integer, ZeekrProbe.Result> zeekr = new LinkedHashMap<>();
    /** The adapter stays set up for the direct read: standard OBD found no car, this did. */
    private boolean zeekrOnly;
    private boolean zeekrGaveUp;
    private int zeekrMisses;
    private int rounds;

    ObdClient(ObdLink link, Listener listener) {
        this(link, listener, 250L, 5_000L);
    }

    ObdClient(ObdLink link, Listener listener, long roundPauseMs, long noCarPauseMs) {
        this.link = link;
        this.listener = listener;
        this.roundPauseMs = roundPauseMs;
        this.noCarPauseMs = noCarPauseMs;
        thread = new Thread(this::run, "obd-client");
        thread.setDaemon(true);
    }

    void start() {
        thread.start();
    }

    /** Safe from any thread. Closing happens off the caller's thread: it can block on Bluetooth. */
    void stop() {
        stopped = true;
        thread.interrupt();
        Thread closer = new Thread(link::close, "obd-close");
        closer.setDaemon(true);
        closer.start();
    }

    /** Read stored and pending codes after the current round. */
    void requestCodes() {
        codesWanted = true;
    }

    private void run() {
        publish();
        try {
            try {
                link.open();
            } catch (IOException | RuntimeException e) {
                fail(ObdSnapshot.Failure.CANNOT_OPEN, e);
                return;
            }
            session = new ElmSession(link.input(), link.output(), listener::onTrace);
            state = ObdSnapshot.State.SEARCHING;
            publish();
            if (!setUp()) {
                fail(ObdSnapshot.Failure.NOT_ELM, null);
                return;
            }
            while (!stopped) {
                readVolts();
                if (zeekrOnly) {
                    stayOnZeekr();
                } else {
                    if (state == ObdSnapshot.State.LIVE) {
                        readRound();
                    } else {
                        findCar();
                    }
                    zeekrTurn();
                }
                if (codesWanted && state == ObdSnapshot.State.LIVE && !zeekrOnly) {
                    codesWanted = false;
                    readCodes();
                }
                publish();
                Thread.sleep(state == ObdSnapshot.State.LIVE ? roundPauseMs : noCarPauseMs);
            }
        } catch (InterruptedException | InterruptedIOException e) {
            // stop()
        } catch (IOException | RuntimeException e) {
            if (!stopped) {
                fail(ObdSnapshot.Failure.LOST, e);
                return;
            }
        } finally {
            link.close();
        }
        state = ObdSnapshot.State.STOPPED;
        publish();
    }

    private boolean setUp() throws IOException {
        String hello = ask("ATZ", RESET_MS);
        if (hello == null || !hello.toUpperCase(Locale.ROOT).contains("ELM")) {
            hello = ask("ATZ", RESET_MS);
        }
        if (hello == null || !hello.toUpperCase(Locale.ROOT).contains("ELM")) {
            return false;
        }
        Matcher name = Pattern.compile("ELM\\S*\\s*\\S*", Pattern.CASE_INSENSITIVE).matcher(hello);
        adapter = name.find() ? name.group().trim() : "ELM327";
        for (String command : SETUP) {
            ask(command, SETUP_MS);
        }
        return true;
    }

    private void readVolts() throws IOException {
        String reply = ask("ATRV", SETUP_MS);
        Matcher matcher = reply == null ? null : VOLTS.matcher(reply);
        volts = matcher != null && matcher.find() ? Float.valueOf(matcher.group(1)) : null;
    }

    private void findCar() throws IOException {
        supported.clear();
        boolean more = true;
        for (int base = 0; more && base <= 0xE0; base += 0x20) {
            String request = String.format(Locale.ROOT, "01%02X", base);
            String reply = ask(request, base == 0 ? SEARCH_MS : READ_MS);
            ElmReply parsed = reply == null ? null : ElmReply.parse(reply);
            more = false;
            if (parsed != null && parsed.kind == ElmReply.Kind.DATA) {
                TreeSet<Integer> found = new TreeSet<>();
                for (byte[] frame : parsed.frames) {
                    ObdCodes.addSupported(frame, base, found);
                }
                // The last bit of each block says whether the next block exists
                more = found.remove(base + 0x20);
                supported.addAll(found);
            }
        }
        if (supported.isEmpty()) {
            state = ObdSnapshot.State.NO_CAR;
            return;
        }
        String number = ask("ATDPN", SETUP_MS);
        can = number == null || isCan(number);
        protocolNumber = number == null ? null : cleanProtocol(number);
        String name = ask("ATDP", SETUP_MS);
        protocol = name == null ? null : name.trim();
        state = ObdSnapshot.State.LIVE;
    }

    /** "A6" = found by the search, protocol 6. 6 to 9 and A to C are CAN. */
    static boolean isCan(String protocolNumber) {
        String clean = cleanProtocol(protocolNumber);
        if (clean.isEmpty()) {
            return true;
        }
        char c = clean.charAt(0);
        return (c >= '6' && c <= '9') || (c >= 'A' && c <= 'C');
    }

    private static String cleanProtocol(String number) {
        String clean = number.replaceAll("[\\s>]", "").toUpperCase(Locale.ROOT);
        return clean.length() > 1 && clean.charAt(0) == 'A' ? clean.substring(1) : clean;
    }

    /** One try at the battery module from standard OBD, if it is time for one. */
    private void zeekrTurn() throws IOException {
        boolean live = state == ObdSnapshot.State.LIVE;
        if (live && (zeekrGaveUp || ++rounds % ZEEKR_EVERY != 1)) {
            return;
        }
        for (String command : ZeekrProbe.ENTER) {
            ask(command, SETUP_MS);
        }
        boolean reached = readZeekr();
        if (reached && !live) {
            zeekrOnly = true;
            protocol = ZEEKR_PROTOCOL;
            state = ObdSnapshot.State.LIVE;
            return;
        }
        zeekrGaveUp = !reached && live && ++zeekrMisses >= ZEEKR_MISSES;
        leaveZeekr();
    }

    private void stayOnZeekr() throws IOException {
        if (readZeekr()) {
            zeekrMisses = 0;
        } else if (++zeekrMisses >= ZEEKR_MISSES) {
            zeekrOnly = false;
            zeekrMisses = 0;
            zeekr.clear();
            state = ObdSnapshot.State.NO_CAR;
            leaveZeekr();
        }
    }

    /** True if the module answered in any way, even with a refusal. */
    private boolean readZeekr() throws IOException {
        boolean reached = false;
        for (int did : ZeekrProbe.DIDS) {
            String reply = ask(ZeekrProbe.request(did), READ_MS);
            ElmReply parsed = reply == null ? null : ElmReply.parse(reply);
            ZeekrProbe.Result result = ZeekrProbe.read(did,
                    parsed == null ? new ArrayList<>() : parsed.frames);
            zeekr.put(did, result);
            reached |= result.reachedModule();
        }
        return reached;
    }

    /** Back to standard OBD: defaults, our setup, and the protocol found, so no new search. */
    private void leaveZeekr() throws IOException {
        ask("ATD", SETUP_MS);
        for (String command : SETUP) {
            ask(command, SETUP_MS);
        }
        if (state == ObdSnapshot.State.LIVE && protocolNumber != null && !protocolNumber.isEmpty()) {
            String command = "ATSP" + protocolNumber;
            if (ElmSession.allowed(command)) {
                ask(command, SETUP_MS);
            }
        }
    }

    private void readRound() throws IOException {
        boolean carAnswered = false;
        boolean busGone = false;
        for (ObdPid pid : ObdPid.values()) {
            if (stopped) {
                return;
            }
            if (!supported.contains(pid.pid)) {
                continue;
            }
            String reply = ask(pid.request(), READ_MS);
            ElmReply parsed = reply == null ? null : ElmReply.parse(reply);
            Double value = parsed == null ? null : pid.decode(parsed.frames);
            if (value == null) {
                values.remove(pid);
            } else {
                values.put(pid, value);
                carAnswered = true;
            }
            busGone |= parsed != null && parsed.kind == ElmReply.Kind.NO_BUS;
        }
        if (busGone && !carAnswered) {
            values.clear();
            state = ObdSnapshot.State.NO_CAR;
        }
    }

    private void readCodes() throws IOException {
        storedCodes = readCodes("03", 0x43);
        pendingCodes = readCodes("07", 0x47);
    }

    private List<String> readCodes(String request, int answerMode) throws IOException {
        String reply = ask(request, READ_MS);
        ElmReply parsed = reply == null ? null : ElmReply.parse(reply);
        if (parsed == null || parsed.kind == ElmReply.Kind.ERROR || parsed.kind == ElmReply.Kind.NO_BUS) {
            return null;
        }
        return new ArrayList<>(ObdCodes.troubleCodes(parsed.frames, answerMode, can));
    }

    /** The reply, or null on a timeout. Too many in a row means the adapter is gone. */
    private String ask(String command, long timeoutMs) throws IOException {
        String reply = session.command(command, timeoutMs);
        silent = reply == null ? silent + 1 : 0;
        if (silent >= MAX_SILENT) {
            throw new IOException("adapter stopped answering");
        }
        return reply;
    }

    private void fail(ObdSnapshot.Failure why, Exception cause) {
        listener.onTrace("failed: " + why + (cause == null ? "" : " " + cause));
        failure = why;
        state = ObdSnapshot.State.STOPPED;
        publish();
    }

    private void publish() {
        Map<ObdPid, Double> copy = new EnumMap<>(ObdPid.class);
        copy.putAll(values);
        listener.onSnapshot(new ObdSnapshot(state, failure, adapter, protocol, volts, copy,
                new ArrayList<>(supported), storedCodes, pendingCodes, new LinkedHashMap<>(zeekr)));
    }
}
