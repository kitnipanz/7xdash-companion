package com.kooo.evcam.obd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class ObdCaptureTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File file;

    private ObdCapture capture(FakeElm elm, long windowMs, long maxMs, long quietMs, long maxBytes)
            throws IOException {
        file = folder.newFile("obd.txt");
        return new ObdCapture(elm, new ObdLog(file), () -> "D, 42 km/h", windowMs, windowMs, maxMs, quietMs, maxBytes);
    }

    private static ObdCapture.Progress finish(ObdCapture capture) throws InterruptedException {
        long end = System.currentTimeMillis() + 10_000;
        while (capture.progress().ended == ObdCapture.Ended.RUNNING) {
            assertTrue("never ended", System.currentTimeMillis() < end);
            Thread.sleep(10);
        }
        return capture.progress();
    }

    private String written() throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void writesWhatItHearsWithTheCarBesideIt() throws Exception {
        FakeElm elm = FakeElm.car().reply("ATMA", "1A0 0102\r1A1 0304");
        ObdCapture capture = capture(elm, 20, 300, 60_000, Long.MAX_VALUE);
        capture.start();
        ObdCapture.Progress done = finish(capture);

        assertEquals(ObdCapture.Ended.TIME_LIMIT, done.ended);
        assertTrue(done.frames >= 2);
        String log = written();
        assertTrue(log.contains("\t1A0 0102\n"));
        assertTrue(log.contains("# car: D, 42 km/h"));
        assertTrue(log.contains("# ended: TIME_LIMIT"));
        assertTrue(elm.closed);
    }

    @Test
    public void listensSilentlyAndTriesEveryCanSetup() throws Exception {
        FakeElm elm = FakeElm.car().reply("ATMA", null);
        ObdCapture capture = capture(elm, 5, 200, 60_000, Long.MAX_VALUE);
        capture.start();
        finish(capture);

        assertTrue(elm.received.contains("ATCSM1"));
        for (String setup : new String[]{"ATSP6", "ATSP7", "ATSP8", "ATSP9"}) {
            assertTrue(setup, elm.received.contains(setup));
        }
    }

    @Test
    public void withNothingHeardKeepsGoingRoundAllSetups() throws Exception {
        FakeElm elm = FakeElm.car().reply("ATMA", null);
        ObdCapture capture = capture(elm, 5, 300, 60_000, Long.MAX_VALUE);
        capture.start();
        finish(capture);
        assertTrue(java.util.Collections.frequency(new ArrayList<>(elm.received), "ATSP9") >= 2);
    }

    @Test
    public void aMarkGoesIntoTheLogWithTheCar() throws Exception {
        FakeElm elm = FakeElm.car().reply("ATMA", "1A0 0102");
        ObdCapture capture = capture(elm, 20, 500, 60_000, Long.MAX_VALUE);
        capture.start();
        Thread.sleep(100);
        assertEquals(1, capture.mark());
        finish(capture);
        assertTrue(written().contains("# MARK 1 | car: D, 42 km/h | moved: nothing"));
    }

    @Test
    public void framesReachTheSniffer() throws Exception {
        FakeElm elm = FakeElm.car().reply("ATMA", "1A0 0102");
        ObdCapture capture = capture(elm, 20, 200, 60_000, Long.MAX_VALUE);
        capture.start();
        finish(capture);
        assertTrue(capture.sniffer().ids() >= 1);
    }

    @Test
    public void givesUpOnSilence() throws Exception {
        FakeElm elm = FakeElm.car().reply("ATMA", null);
        ObdCapture capture = capture(elm, 10, 60_000, 50, Long.MAX_VALUE);
        capture.start();
        ObdCapture.Progress done = finish(capture);
        assertEquals(ObdCapture.Ended.NOTHING_HEARD, done.ended);
        assertEquals(0, done.frames);
    }

    @Test
    public void stopsAtTheSizeLimit() throws Exception {
        FakeElm elm = FakeElm.car().reply("ATMA", "1A0 0102");
        ObdCapture capture = capture(elm, 5, 60_000, 60_000, 1_000);
        capture.start();
        assertEquals(ObdCapture.Ended.SIZE_LIMIT, finish(capture).ended);
    }

    @Test
    public void userStop() throws Exception {
        FakeElm elm = FakeElm.car().reply("ATMA", null);
        ObdCapture capture = capture(elm, 60_000, 60_000, 60_000, Long.MAX_VALUE);
        capture.start();
        Thread.sleep(100);
        capture.stop();
        assertEquals(ObdCapture.Ended.BY_USER, finish(capture).ended);
        assertTrue(written().contains("# ended: BY_USER"));
    }

    @Test
    public void adapterThatWillNotOpen() throws Exception {
        FakeElm elm = FakeElm.car();
        elm.failOpen = true;
        ObdCapture capture = capture(elm, 5, 100, 100, Long.MAX_VALUE);
        capture.start();
        assertEquals(ObdCapture.Ended.CANNOT_OPEN, finish(capture).ended);
    }

    @Test
    public void neverAsksAnythingAfterTheStartingCheck() throws Exception {
        FakeElm elm = FakeElm.car().reply("ATMA", null);
        ObdCapture capture = capture(elm, 5, 200, 60_000, Long.MAX_VALUE);
        capture.start();
        finish(capture);
        List<String> sent = new ArrayList<>(elm.received);
        List<String> afterCheck = sent.subList(sent.indexOf("ATMA"), sent.size());
        for (String command : afterCheck) {
            assertTrue(command, command.isEmpty() || command.startsWith("AT"));
        }
        assertFalse(afterCheck.contains("0100"));
    }
}
