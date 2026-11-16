package com.kooo.evcam.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MegaphoneVoiceTest {

    private static final int RATE = 48_000;
    private static final int CHUNK = 960;

    /** Runs {@code seconds} of a tone through in 20 ms chunks; returns the RMS of the last 100 ms out. */
    private static double run(MegaphoneVoice v, double hz, double amp, double seconds, int gain) {
        int total = (int) (RATE * seconds);
        double sum = 0;
        int counted = 0;
        for (int at = 0; at < total; at += CHUNK) {
            byte[] buf = new byte[CHUNK * 2];
            for (int i = 0; i < CHUNK; i++) {
                short s = (short) Math.round(amp * Math.sin(2 * Math.PI * hz * (at + i) / RATE));
                buf[2 * i] = (byte) s;
                buf[2 * i + 1] = (byte) (s >> 8);
            }
            v.process(buf, buf.length, gain);
            if (at >= total - RATE / 10) {
                for (int i = 0; i < CHUNK; i++) {
                    short s = (short) ((buf[2 * i] & 0xFF) | (buf[2 * i + 1] << 8));
                    sum += (double) s * s;
                    counted++;
                }
            }
        }
        return Math.sqrt(sum / Math.max(1, counted));
    }

    @Test
    public void voiceGoesThroughAmplified() {
        MegaphoneVoice v = new MegaphoneVoice(RATE);
        run(v, 1000, 30, 1, 20); // quiet room first, so the gate learns it
        double out = run(v, 1000, 600, 0.5, 20);
        // 600 * 20 = 12000 peak, about 8500 RMS
        assertTrue("voice " + out, out > 7000);
    }

    @Test
    public void echoAfterTheVoiceIsTurnedDown() {
        MegaphoneVoice v = new MegaphoneVoice(RATE);
        run(v, 1000, 20, 1, 20);
        run(v, 1000, 600, 0.5, 20);
        // the leaked echo: same pitch, much quieter, but above the room
        double open = 20 * 20 / Math.sqrt(2);
        double echo = run(v, 1000, 20, 0.6, 20);
        assertTrue("echo " + echo + " vs ungated " + open, echo < open * 0.15);
    }

    @Test
    public void rumbleIsCut() {
        MegaphoneVoice v = new MegaphoneVoice(RATE);
        double rumble = run(v, 40, 600, 1, 20);
        double voice = run(new MegaphoneVoice(RATE), 1000, 600, 1, 20);
        assertTrue("rumble " + rumble + " voice " + voice, rumble < voice * 0.2);
    }

    @Test
    public void softLimitNeverClips() {
        assertEquals(10000f, MegaphoneVoice.softLimit(10000f), 0f);
        assertTrue(MegaphoneVoice.softLimit(1e6f) <= 32767f);
        assertTrue(MegaphoneVoice.softLimit(-1e6f) >= -32768f);
        assertTrue(MegaphoneVoice.softLimit(30000f) > MegaphoneVoice.softLimit(26000f));
    }

    @Test
    public void zeroGainIsSilence() {
        byte[] buf = {10, 20, 30, 40};
        assertEquals(0, new MegaphoneVoice(RATE).process(buf, buf.length, 0));
        assertEquals(0, buf[0] | buf[1] | buf[2] | buf[3]);
    }
}
