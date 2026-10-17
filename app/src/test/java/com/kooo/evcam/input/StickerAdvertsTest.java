package com.kooo.evcam.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Bytes are real ZEEKR_button adverts from the car log, 2026-10-16. */
public class StickerAdvertsTest {

    private static byte[] hex(String s) {
        String[] parts = s.trim().split(" ");
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return out;
    }

    @Test
    public void idleAdvertDecodes() {
        byte[] raw = hex("02 01 06 12 02 FF 01 00 4B 01 03 01 AA 04 00 00 0F D0 7F 81 94 B3 "
                + "0D 09 5A 45 45 4B 52 5F 62 75 74 74 6F 6E 02 0A");
        assertEquals("cnt=AA x=01 y=00", StickerAdverts.decode(raw));
    }

    @Test
    public void doublePressAdvertIsMarked() {
        byte[] raw = hex("02 01 06 12 02 FF 01 02 4B 01 03 01 B6 04 00 00 88 3E 59 D1 95 EE "
                + "0D 09 5A 45 45 4B 52 5F 62 75 74 74 6F 6E 02 0A");
        assertEquals("cnt=B6 x=01 y=02 DOUBLE", StickerAdverts.decode(raw));
    }

    @Test
    public void advertWithoutScanResponseStillDecodes() {
        byte[] raw = hex("02 01 06 12 02 FF 00 00 4B 01 03 01 99 04 00 00 B2 97 4B 67 9A 11");
        assertEquals("cnt=99 x=00 y=00", StickerAdverts.decode(raw));
    }

    @Test
    public void fieldsGiveCounterAndKind() {
        byte[] single = hex("02 01 06 12 02 FF 11 00 4B 01 03 01 BC 04 00 00 99 88 17 C3 5E 91");
        byte[] dbl = hex("02 01 06 12 02 FF 00 02 4B 01 03 01 BD 04 00 00 75 52 17 97 25 96 "
                + "0D 09 5A 45 45 4B 52 5F 62 75 74 74 6F 6E 02 0A");
        assertEquals(0xBC, StickerAdverts.fields(single)[0]);
        assertEquals(StickerAdverts.Y_SINGLE, StickerAdverts.fields(single)[1]);
        assertEquals(0xBD, StickerAdverts.fields(dbl)[0]);
        assertEquals(StickerAdverts.Y_DOUBLE, StickerAdverts.fields(dbl)[1]);
        assertEquals(null, StickerAdverts.fields(hex("02 01 06")));
    }

    @Test
    public void onlyAChangedCounterIsAPress() {
        // First advert after start is the baseline, not a press.
        assertFalse(StickerAdverts.isNewPress(null, 0xBC));
        assertFalse(StickerAdverts.isNewPress(0xBC, 0xBC));
        assertTrue(StickerAdverts.isNewPress(0xBC, 0xBD));
        // Counter wraps past FF.
        assertTrue(StickerAdverts.isNewPress(0xFF, 0x00));
    }

    @Test
    public void kindMapsToTheSamePressBytesAsGatt() {
        assertEquals(0x31, StickerAdverts.frameFor(StickerAdverts.Y_SINGLE)[0]);
        assertEquals(0x32, StickerAdverts.frameFor(StickerAdverts.Y_DOUBLE)[0]);
        assertEquals(null, StickerAdverts.frameFor(0x05));
        assertEquals(StickerFrame.parse(new byte[]{0x31}).press, PressKind.TAP);
        assertEquals(StickerFrame.parse(new byte[]{0x32}).press, PressKind.DOUBLE);
    }

    @Test
    public void otherDevicesDoNotDecode() {
        assertEquals("", StickerAdverts.decode(hex("02 01 06 03 03 D0 FF")));
        assertEquals("", StickerAdverts.decode(null));
    }

    @Test
    public void onlyTheZeekrButtonCounts() {
        assertTrue(StickerAdverts.isButton("ZEEKR_button"));
        assertTrue(StickerAdverts.isButton("zeekr_button "));
        assertFalse(StickerAdverts.isButton("Zeekr570"));
        assertFalse(StickerAdverts.isButton(null));
    }
}
