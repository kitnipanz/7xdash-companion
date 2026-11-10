package com.kooo.evcam.obd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

public class ObdClientTest {

    private final BlockingQueue<ObdSnapshot> seen = new LinkedBlockingQueue<>();

    private ObdClient client(FakeElm elm) {
        return new ObdClient(elm, new ObdClient.Listener() {
            @Override
            public void onSnapshot(ObdSnapshot snapshot) {
                seen.add(snapshot);
            }

            @Override
            public void onTrace(String line) {
            }
        }, 1L, 1L);
    }

    private ObdSnapshot await(Predicate<ObdSnapshot> wanted) throws InterruptedException {
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            ObdSnapshot next = seen.poll(100, TimeUnit.MILLISECONDS);
            if (next != null && wanted.test(next)) {
                return next;
            }
        }
        throw new AssertionError("never saw it");
    }

    @Test
    public void connectsFindsTheCarAndReadsWhatItHas() throws Exception {
        FakeElm elm = FakeElm.car();
        ObdClient client = client(elm);
        client.start();
        ObdSnapshot live = await(s -> s.values.containsKey(ObdPid.BATTERY_REMAINING));
        client.stop();

        assertEquals("ELM327 v1.5", live.adapter);
        assertEquals("AUTO, ISO 15765-4 (CAN 11/500)", live.protocol);
        assertEquals(12.6f, live.volts, 1e-3);
        assertEquals(42, live.values.get(ObdPid.SPEED), 1e-9);
        assertEquals(Arrays.asList(0x0D, 0x5B), live.supported);
        assertFalse("never asks for what the car does not have", elm.received.contains("0142"));
        await(s -> s.state == ObdSnapshot.State.STOPPED);
        assertTrue(elm.closed);
    }

    @Test
    public void adapterWithoutACar() throws Exception {
        FakeElm elm = FakeElm.car().reply("0100", "SEARCHING...\rUNABLE TO CONNECT");
        ObdClient client = client(elm);
        client.start();
        ObdSnapshot none = await(s -> s.state == ObdSnapshot.State.NO_CAR);
        assertEquals(12.6f, none.volts, 1e-3);

        // Ignition on: found on a later try
        elm.reply("0100", "41 00 00 08 00 00");
        await(s -> s.state == ObdSnapshot.State.LIVE);
        client.stop();
    }

    @Test
    public void carGoingAwayIsNotTheAdapterGoingAway() throws Exception {
        FakeElm elm = FakeElm.car();
        ObdClient client = client(elm);
        client.start();
        await(s -> s.values.containsKey(ObdPid.SPEED));

        elm.reply("0100", "UNABLE TO CONNECT");
        elm.reply("010D", "UNABLE TO CONNECT");
        elm.reply("015B", "UNABLE TO CONNECT");
        ObdSnapshot none = await(s -> s.state == ObdSnapshot.State.NO_CAR);
        assertTrue(none.values.isEmpty());
        client.stop();
    }

    @Test
    public void readsStoredAndPendingCodesWhenAsked() throws Exception {
        FakeElm elm = FakeElm.car().reply("03", "43 01 01 23").reply("07", "47 00");
        ObdClient client = client(elm);
        client.start();
        await(s -> s.state == ObdSnapshot.State.LIVE);
        client.requestCodes();
        ObdSnapshot read = await(s -> s.storedCodes != null);
        client.stop();

        assertEquals(Collections.singletonList("P0123"), read.storedCodes);
        assertNotNull(read.pendingCodes);
        assertTrue(read.pendingCodes.isEmpty());
    }

    /** What the 001 does: nothing on standard OBD, but the battery module answers. */
    @Test
    public void carWithNoStandardObdStillReadsTheBatteryModule() throws Exception {
        FakeElm elm = FakeElm.car().reply("0100", "BUS INIT: ...ERROR")
                .reply("224801", "62 48 01 12 AB").reply("22491B", "7F 22 31");
        ObdClient client = client(elm);
        client.start();
        ObdSnapshot live = await(s -> s.state == ObdSnapshot.State.LIVE);
        client.stop();

        assertEquals("12AB", live.zeekr.get(0x4801).hex);
        assertEquals(0x31, live.zeekr.get(0x491B).refusal);
        assertTrue(live.supported.isEmpty());
        assertEquals(1, Collections.frequency(elm.received, "ATSHD01635"));
    }

    @Test
    public void lockedModuleIsReportedNotHidden() throws Exception {
        FakeElm elm = FakeElm.car().reply("0100", "UNABLE TO CONNECT")
                .reply("224801", "7F 22 33").reply("22491B", "7F 22 33");
        ObdClient client = client(elm);
        client.start();
        ObdSnapshot live = await(s -> s.state == ObdSnapshot.State.LIVE);
        client.stop();
        assertEquals(0x33, live.zeekr.get(0x4801).refusal);
    }

    @Test
    public void zeekrTurnReturnsToStandardObdOnTheProtocolFound() throws Exception {
        FakeElm elm = FakeElm.car().reply("224801", "62 48 01 12 AB").reply("22491B", "NO DATA");
        ObdClient client = client(elm);
        client.start();
        await(s -> s.zeekr.containsKey(0x4801) && s.values.containsKey(ObdPid.SPEED));
        client.stop();

        int enter = elm.received.indexOf("ATSHD01635");
        int reset = elm.received.indexOf("ATD");
        int back = elm.received.indexOf("ATSP6");
        assertTrue(enter >= 0 && reset > enter && back > reset);
    }

    @Test
    public void givesUpOnASilentModuleButKeepsStandardObd() throws Exception {
        FakeElm elm = FakeElm.car().reply("224801", "NO DATA").reply("22491B", "NO DATA");
        ObdClient client = client(elm);
        client.start();
        long end = System.currentTimeMillis() + 3_000;
        while (Collections.frequency(elm.received, "ATSHD01635") < 3 && System.currentTimeMillis() < end) {
            await(s -> s.state == ObdSnapshot.State.LIVE);
        }
        Thread.sleep(300);
        client.stop();
        assertEquals(3, Collections.frequency(new java.util.ArrayList<>(elm.received), "ATSHD01635"));
        assertTrue(elm.received.contains("010D"));
    }

    @Test
    public void linkThatWillNotOpen() throws Exception {
        FakeElm elm = FakeElm.car();
        elm.failOpen = true;
        client(elm).start();
        ObdSnapshot done = await(s -> s.state == ObdSnapshot.State.STOPPED);
        assertEquals(ObdSnapshot.Failure.CANNOT_OPEN, done.failure);
    }

    @Test
    public void somethingThatIsNotAnElm() throws Exception {
        FakeElm elm = new FakeElm().reply("ATZ", "hello");
        client(elm).start();
        ObdSnapshot done = await(s -> s.state == ObdSnapshot.State.STOPPED);
        assertEquals(ObdSnapshot.Failure.NOT_ELM, done.failure);
    }

    @Test
    public void silentAdapterIsLost() throws Exception {
        FakeElm elm = FakeElm.car();
        ObdClient client = client(elm);
        client.start();
        await(s -> s.state == ObdSnapshot.State.LIVE);
        elm.replies.clear();
        for (String command : new String[]{"ATRV", "010D", "015B"}) {
            elm.replies.put(command, null);
        }
        ObdSnapshot done = await(s -> s.state == ObdSnapshot.State.STOPPED);
        assertEquals(ObdSnapshot.Failure.LOST, done.failure);
    }

    @Test
    public void protocolNumbers() {
        assertTrue(ObdClient.isCan("A6"));
        assertTrue(ObdClient.isCan("7\r\r"));
        assertTrue(ObdClient.isCan("9"));
        assertFalse(ObdClient.isCan("A3"));
        assertFalse(ObdClient.isCan("5"));
    }

    @Test
    public void batteryScanKeepsWhatAnsweredAndGoesBackToStandardObd() throws Exception {
        FakeElm elm = FakeElm.car().reply("224801", "62 48 01 86 08")
                .reply("224850", "62 48 50 01 02").reply("2249A0", "7F 22 33").reply("224900", "7F 22 31");
        ObdClient client = client(elm);
        client.start();
        await(s -> s.values.containsKey(ObdPid.SPEED));
        client.requestBatteryScan();
        ObdSnapshot done = await(s -> s.scan.finished());
        await(s -> s.values.containsKey(ObdPid.SPEED) && !s.scan.running);
        client.stop();

        assertEquals(1, done.scan.number);
        assertEquals(Arrays.asList(0x4801, 0x4850, 0x49A0), new java.util.ArrayList<>(done.scan.found.keySet()));
        assertEquals("0102", done.scan.found.get(0x4850).hex);
        assertEquals(0x33, done.scan.found.get(0x49A0).refusal);
        assertTrue(elm.received.contains("2249FF"));
        assertFalse(elm.received.contains("224A00"));
        int last = elm.received.lastIndexOf("2249FF");
        assertTrue("back on standard OBD after the scan", elm.received.subList(last, elm.received.size()).contains("ATSP6"));
    }
}
