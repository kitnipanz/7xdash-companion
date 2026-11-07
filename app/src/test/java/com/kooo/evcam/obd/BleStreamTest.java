package com.kooo.evcam.obd;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class BleStreamTest {

    private final List<byte[]> sent = new ArrayList<>();
    private final BleStream stream = new BleStream(sent::add);

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    public void readsNotificationsInOrderAcrossPackets() throws IOException {
        stream.received(ascii("41 "));
        stream.received(ascii("0D>"));
        assertEquals(6, stream.input().available());
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            text.append((char) stream.input().read());
        }
        assertEquals("41 0D>", text.toString());
        assertEquals(0, stream.input().available());
    }

    @Test
    public void keepsItsOwnCopy() throws IOException {
        byte[] value = ascii("A");
        stream.received(value);
        value[0] = 'B';
        assertEquals('A', stream.input().read());
    }

    @Test
    public void bytesAboveSevenBitsAreNotNegative() throws IOException {
        stream.received(new byte[]{(byte) 0xFF});
        assertEquals(0xFF, stream.input().read());
    }

    @Test
    public void shortRequestIsOnePacket() throws IOException {
        stream.output().write(ascii("010D\r"));
        assertEquals(1, sent.size());
    }

    @Test
    public void longRequestIsCutUp() throws IOException {
        stream.output().write(new byte[45]);
        assertEquals(3, sent.size());
        assertEquals(20, sent.get(0).length);
        assertEquals(5, sent.get(2).length);
    }

    @Test
    public void singleByteGoesOut() throws IOException {
        stream.output().write('\r');
        assertEquals(1, sent.size());
    }

    @Test
    public void whatArrivedBeforeTheLinkDiedCanStillBeRead() throws IOException {
        stream.received(ascii(">"));
        stream.close();
        assertEquals(1, stream.input().available());
        assertEquals('>', stream.input().read());
    }

    @Test(expected = IOException.class)
    public void aDeadLinkIsAnErrorOnceEmpty() throws IOException {
        stream.close();
        stream.input().available();
    }

    /** ElmSession on BleStream, as on a real link: the whole request and reply path. */
    @Test
    public void elmSessionWorksOverIt() throws IOException {
        BleStream[] self = new BleStream[1];
        self[0] = new BleStream(packet -> self[0].received(ascii("12.6V\r\r>")));
        assertEquals("12.6V\r\r", new ElmSession(self[0].input(), self[0].output(), line -> { })
                .command("ATRV", 1000));
    }
}
