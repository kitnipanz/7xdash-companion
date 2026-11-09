package com.kooo.evcam.obd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ElmSessionTest {

    private static ElmSession session(FakeElm elm) {
        return new ElmSession(elm.input(), elm.output(), line -> { });
    }

    @Test
    public void readsUpToThePrompt() throws IOException {
        FakeElm elm = new FakeElm().reply("ATRV", "12.6V");
        assertEquals("12.6V\r\r", session(elm).command("ATRV", 1000));
    }

    @Test
    public void silenceIsATimeout() throws IOException {
        FakeElm elm = new FakeElm().reply("ATRV", null);
        assertNull(session(elm).command("ATRV", 20));
    }

    /** The promise on the home card: the app cannot clear codes or write to the car. */
    @Test
    public void onlyReadCommandsPass() {
        for (String ok : new String[]{"ATZ", "ATD", "ATRV", "ATDPN", "ATSP7", "ATSHD01635", "ATMA", "ATCSM1", "ATH1", "ATAL",
                "ATCRA1EC6AE80", "0100", "010D", "03", "07", "224801", "22491B",
                "224802", "224945", "22496D", "2248FF", "224900", "22DD01"}) {
            assertTrue(ok, ElmSession.allowed(ok));
        }
        for (String no : new String[]{"04", "0104 ", "08", "0902", "2E0101", "3101", "10", "11", "ATCSM0",
                "ATMR00", "ATMT00", "ATWS", "ATPP", "ATSH7E0", "AT", "", "22F190", "224A00", "2247FF", "22DD02", "22D901", "2248", "2248010", "2E4801", "2F4801", "3101FF00",
                "27", "1003", "1101", "ATSHD01636", "ATCRA", "ATFCSH7E0"}) {
            assertFalse(no, ElmSession.allowed(no));
        }
    }

    @Test
    public void monitorDeliversLinesUntilTheAdapterStops() throws IOException {
        FakeElm elm = new FakeElm().reply("ATMA", "1A0 0102\r1A1 0304\rBUFFER FULL");
        List<String> heard = new ArrayList<>();
        session(elm).monitor(1000, heard::add);
        assertEquals(Arrays.asList("1A0 0102", "1A1 0304", "BUFFER FULL"), heard);
    }

    @Test
    public void monitorEndsItsWindowWithAByte() throws IOException {
        FakeElm elm = new FakeElm().reply("ATMA", null);
        session(elm).monitor(30, line -> { });
        assertEquals(Arrays.asList("ATMA", ""), elm.received);
    }

    @Test(expected = IOException.class)
    public void monitorThatWillNotStopIsAnError() throws IOException {
        FakeElm elm = new FakeElm().reply("ATMA", null).reply("", null);
        session(elm).monitor(1, line -> { });
    }

    @Test(expected = IllegalArgumentException.class)
    public void aRefusedCommandNeverReachesTheWire() throws IOException {
        session(new FakeElm()).command("04", 10);
    }
}
