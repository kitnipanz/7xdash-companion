package com.kooo.evcam.remote;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RemoteStreamTest {

    @Test
    public void headerCarriesKeySourceSizeAndPts() {
        byte[] nal = {0, 0, 0, 1, 0x67};
        byte[] p = RemoteStream.frame(nal, true, RemoteStream.codeOf("ch3"), 1280, 720, 0x0102030405L);
        assertEquals(RemoteStream.HEADER + nal.length, p.length);
        assertEquals('V', p[0]);
        assertEquals(1, p[1]);
        assertEquals(3, p[2]);
        assertEquals(1280, ((p[3] & 0xff) << 8) | (p[4] & 0xff));
        assertEquals(720, ((p[5] & 0xff) << 8) | (p[6] & 0xff));
        long pts = 0;
        for (int i = 0; i < 8; i++) {
            pts = (pts << 8) | (p[7 + i] & 0xff);
        }
        assertEquals(0x0102030405L, pts);
        assertEquals(0x67, p[RemoteStream.HEADER + 4]);
    }

    @Test
    public void sourceCodesMatchWebOrder() {
        assertEquals(0, RemoteStream.codeOf("drive"));
        assertEquals(1, RemoteStream.codeOf("ch1"));
        assertEquals(4, RemoteStream.codeOf("ch4"));
        assertEquals(5, RemoteStream.codeOf("driver"));
        assertEquals(6, RemoteStream.codeOf("backseat"));
        assertEquals(0, RemoteStream.codeOf("bogus"));
    }

    @Test
    public void deltaFrameHasNoKeyFlag() {
        byte[] p = RemoteStream.frame(new byte[]{1}, false, (byte) 0, 2, 2, 0);
        assertEquals(0, p[1]);
    }
}
