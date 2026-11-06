package com.kooo.evcam.obd;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Records everything the OBD socket lets an ELM327 hear, to find out what a Zeekr 7X puts there.
 *
 * <p>After a short check at the start it only listens: the adapter's monitor mode (ATMA) with CAN
 * silent monitoring on (ATCSM1), so it does not even acknowledge frames. An adapter hears one bus
 * setup at a time, so it takes turns through the CAN setups, then stays on those that heard anything.
 * It still works when the screen is left, and stops by itself ({@link #MAX_MS},
 * {@link #MAX_BYTES}, or {@link #QUIET_MS} of silence), so it cannot run down the 12 V battery.</p>
 */
final class ObdCapture {

    enum Ended { RUNNING, BY_USER, TIME_LIMIT, SIZE_LIMIT, NOTHING_HEARD, CANNOT_OPEN, NOT_ELM, LOST, DRIVE_ERROR }

    /** What the screen shows; a fresh copy for each change. */
    static final class Progress {
        final Ended ended;
        final String protocol;
        final long frames;
        final long bytes;

        Progress(Ended ended, String protocol, long frames, long bytes) {
            this.ended = ended;
            this.protocol = protocol;
            this.frames = frames;
            this.bytes = bytes;
        }
    }

    private static final String[] SETUP = {"ATE0", "ATL0", "ATS0", "ATH1", "ATAL", "ATCSM1"};
    private static final String[] CAN_SETUPS = {"6", "7", "8", "9"};
    private static final Pattern FRAME = Pattern.compile("^[0-9A-F]{5,}$");
    private static final long SETUP_MS = 2_000L;
    private static final long CHECK_MS = 16_000L;
    private static final long RESET_MS = 6_000L;
    private static final long RESTART_PAUSE_MS = 100L;
    private static final long CONTEXT_EVERY_MS = 1_000L;

    static final long MAX_MS = 60L * 60L * 1000L;
    static final long MAX_BYTES = 200L * 1024L * 1024L;
    static final long QUIET_MS = 5L * 60L * 1000L;
    /** A Mark comes a moment after the alert: look this far back. */
    static final long MARK_BACK_MS = 8_000L;

    private final ObdLink link;
    private final ObdLog log;
    /** The car's own state in words, from the head unit, to line frames up with what happened. */
    private final Supplier<String> context;
    private final long firstWindowMs;
    private final long windowMs;
    private final long maxMs;
    private final long quietMs;
    private final long maxBytes;
    private final Thread thread;

    private volatile boolean stopped;
    private volatile Progress progress = new Progress(Ended.RUNNING, null, 0, 0);
    private final CanSniffer sniffer = new CanSniffer();
    private final AtomicInteger marks = new AtomicInteger();

    // The fields below belong to the capture thread
    private ElmSession session;
    private String protocol;
    private long frames;
    private long startedAt;
    private long heardAt;
    private long contextAt;
    private int marksWritten;

    ObdCapture(ObdLink link, ObdLog log, Supplier<String> context) {
        this(link, log, context, 10_000L, 30_000L, MAX_MS, QUIET_MS, MAX_BYTES);
    }

    ObdCapture(ObdLink link, ObdLog log, Supplier<String> context, long firstWindowMs, long windowMs,
               long maxMs, long quietMs, long maxBytes) {
        this.link = link;
        this.log = log;
        this.context = context;
        this.firstWindowMs = firstWindowMs;
        this.windowMs = windowMs;
        this.maxMs = maxMs;
        this.quietMs = quietMs;
        this.maxBytes = maxBytes;
        thread = new Thread(this::run, "obd-capture");
        thread.setDaemon(true);
    }

    void start() {
        thread.start();
    }

    /** Safe from any thread. */
    void stop() {
        stopped = true;
        thread.interrupt();
        Thread closer = new Thread(link::close, "obd-close");
        closer.setDaemon(true);
        closer.start();
    }

    Progress progress() {
        return progress;
    }

    CanSniffer sniffer() {
        return sniffer;
    }

    /** The clock {@link CanSniffer} times are on. */
    long clock() {
        return now();
    }

    /** "This is the moment": goes into the log with what just moved. Safe from any thread. */
    int mark() {
        return marks.incrementAndGet();
    }

    private void writeMarks() throws IOException {
        while (marksWritten < marks.get()) {
            marksWritten++;
            writeLine("# MARK " + marksWritten + " | car: " + context.get()
                    + " | moved: " + sniffer.movedText(now(), MARK_BACK_MS));
        }
    }

    private void run() {
        Ended ended;
        try {
            ended = capture();
        } catch (InterruptedException | InterruptedIOException e) {
            ended = Ended.BY_USER;
        } catch (IOException | RuntimeException e) {
            ended = stopped ? Ended.BY_USER : e instanceof DriveError ? Ended.DRIVE_ERROR : Ended.LOST;
            note("error: " + e);
        }
        note("# ended: " + ended + ", " + frames + " frames");
        link.close();
        log.close();
        publish(ended);
    }

    private Ended capture() throws IOException, InterruptedException {
        startedAt = now();
        heardAt = startedAt;
        try {
            link.open();
        } catch (IOException | RuntimeException e) {
            note("cannot open: " + e);
            return Ended.CANNOT_OPEN;
        }
        session = new ElmSession(link.input(), link.output(), this::note);
        if (!isElm(session.command("ATZ", RESET_MS))) {
            return Ended.NOT_ELM;
        }
        setUp();
        check();
        List<String> heard = new ArrayList<>();
        for (String setup : CAN_SETUPS) {
            long before = frames;
            Ended limit = listen(setup, firstWindowMs);
            if (limit != null) {
                return limit;
            }
            if (frames > before) {
                heard.add(setup);
            }
        }
        while (true) {
            for (String setup : heard.isEmpty() ? List.of(CAN_SETUPS) : heard) {
                Ended limit = listen(setup, windowMs);
                if (limit != null) {
                    return limit;
                }
            }
        }
    }

    private void setUp() throws IOException {
        for (String command : SETUP) {
            session.command(command, SETUP_MS);
        }
    }

    /** One look at what answers when asked, at the head of the file. */
    private void check() throws IOException {
        note("# check");
        note("# car: " + context.get());
        session.command("ATRV", SETUP_MS);
        session.command("ATSP0", SETUP_MS);
        session.command("0100", CHECK_MS);
        session.command("ATDPN", SETUP_MS);
        for (String command : ZeekrProbe.ENTER) {
            session.command(command, SETUP_MS);
        }
        for (int did : ZeekrProbe.DIDS) {
            session.command(ZeekrProbe.request(did), SETUP_MS);
        }
        session.command("ATD", SETUP_MS);
        setUp();
    }

    /** Listens on one setup for one window. Null, or why the capture must end. */
    private Ended listen(String setup, long window) throws IOException, InterruptedException {
        session.command("ATSP" + setup, SETUP_MS);
        protocol = setup;
        note("# listening on CAN setup " + setup);
        publish(Ended.RUNNING);
        long end = now() + window;
        while (now() < end) {
            Ended limit = limit();
            if (limit != null) {
                return limit;
            }
            // The adapter gives up when its buffer fills ("BUFFER FULL"): start it again
            session.monitor(Math.max(1L, end - now()), this::heard);
            writeMarks();
            publish(Ended.RUNNING);
            if (now() < end) {
                Thread.sleep(RESTART_PAUSE_MS);
            }
        }
        return limit();
    }

    private void heard(String line) throws IOException {
        long now = now();
        if (FRAME.matcher(line.replace(" ", "")).matches()) {
            frames++;
            heardAt = now;
            sniffer.frame(line, protocol, now);
        }
        writeMarks();
        if (now - contextAt >= CONTEXT_EVERY_MS) {
            contextAt = now;
            writeLine("# car: " + context.get());
        }
        writeLine(line);
        if (frames % 256 == 0) {
            publish(Ended.RUNNING);
        }
    }

    private Ended limit() {
        long now = now();
        if (now - startedAt >= maxMs) {
            return Ended.TIME_LIMIT;
        }
        if (now - heardAt >= quietMs) {
            return Ended.NOTHING_HEARD;
        }
        return log.bytes() >= maxBytes ? Ended.SIZE_LIMIT : null;
    }

    private static boolean isElm(String hello) {
        return hello != null && hello.toUpperCase(Locale.ROOT).contains("ELM");
    }

    private void note(String line) {
        try {
            writeLine(line);
        } catch (IOException e) {
            // a dead drive ends the capture at the next frame
        }
    }

    private void writeLine(String line) throws IOException {
        try {
            log.line(line);
        } catch (IOException e) {
            throw new DriveError(e);
        }
    }

    private void publish(Ended ended) {
        progress = new Progress(ended, protocol, frames, log.bytes());
    }

    private static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    /** Tells a failing USB drive from a failing adapter. */
    private static final class DriveError extends IOException {
        DriveError(IOException cause) {
            super(cause);
        }
    }
}
