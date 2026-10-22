package com.kooo.evcam.audio;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class OutsideSpeakerTest {

    @Test
    public void volumeIsSquaredAndClamped() {
        assertEquals(0f, OutsideSpeaker.gain(0), 0f);
        assertEquals(0.25f, OutsideSpeaker.gain(50), 1e-6f);
        assertEquals(1f, OutsideSpeaker.gain(100), 0f);
        assertEquals(1f, OutsideSpeaker.gain(150), 0f);
        assertEquals(0f, OutsideSpeaker.gain(-5), 0f);
    }

    @Test
    public void downmixAveragesBothSides() {
        byte[] stereo = {
                (byte) 0xE8, 0x03, (byte) 0xD0, 0x07,               // 1000, 2000
                (byte) 0x00, (byte) 0x80, (byte) 0x00, (byte) 0x80, // -32768, -32768
                (byte) 0xFF, 0x7F, 0x01, (byte) 0x80,               // 32767, -32767
        };
        byte[] mono = new byte[6];
        assertEquals(6, OutsideSpeaker.downmix(stereo, stereo.length, mono));
        assertEquals(1500, sample(mono, 0));
        assertEquals(-32768, sample(mono, 1));
        assertEquals(0, sample(mono, 2));
    }

    @Test
    public void downmixDropsPartialFramesAndFitsTheOutput() {
        byte[] stereo = new byte[10];
        assertEquals(4, OutsideSpeaker.downmix(stereo, 10, new byte[8]));
        assertEquals(2, OutsideSpeaker.downmix(stereo, 10, new byte[2]));
        assertEquals(0, OutsideSpeaker.downmix(stereo, 3, new byte[8]));
        assertEquals(0, OutsideSpeaker.downmix(null, 8, new byte[8]));
    }

    private static int sample(byte[] pcm, int index) {
        return (short) ((pcm[index * 2] & 0xFF) | (pcm[index * 2 + 1] << 8));
    }
}
