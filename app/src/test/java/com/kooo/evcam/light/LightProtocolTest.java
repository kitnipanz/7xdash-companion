package com.kooo.evcam.light;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class LightProtocolTest {

    @Test
    public void elkPackets() {
        LightProtocol.Family elk = LightProtocol.Family.ELK;
        assertEquals("7E 00 04 F0 00 01 FF 00 EF", LightProtocol.hex(LightProtocol.power(elk, true)));
        assertEquals("7E 00 04 00 00 00 FF 00 EF", LightProtocol.hex(LightProtocol.power(elk, false)));
        assertEquals("7E 00 05 03 FF 00 80 00 EF", LightProtocol.hex(LightProtocol.color(elk, 0xFF0080, 50)));
        assertEquals("7E 00 01 32 00 00 00 00 EF", LightProtocol.hex(LightProtocol.brightness(elk, 50)));
        assertEquals("7E 00 01 64 00 00 00 00 EF", LightProtocol.hex(LightProtocol.brightness(elk, 140)));
    }

    @Test
    public void trionesScalesColorForBrightness() {
        LightProtocol.Family triones = LightProtocol.Family.TRIONES;
        assertEquals("CC 23 33", LightProtocol.hex(LightProtocol.power(triones, true)));
        assertEquals("CC 24 33", LightProtocol.hex(LightProtocol.power(triones, false)));
        assertEquals("56 FF 00 80 00 F0 AA", LightProtocol.hex(LightProtocol.color(triones, 0xFF0080, 100)));
        assertEquals("56 7F 00 40 00 F0 AA", LightProtocol.hex(LightProtocol.color(triones, 0xFF0080, 50)));
        assertNull(LightProtocol.brightness(triones, 50));
    }

    @Test
    public void detectsFamilyFromWriteCharacteristic() {
        assertEquals(LightProtocol.Family.ELK, LightProtocol.detect(Arrays.asList(
                LightProtocol.uuid16(0x2A00), LightProtocol.uuid16(0xFFF3))));
        assertEquals(LightProtocol.Family.TRIONES, LightProtocol.detect(
                Collections.singletonList(LightProtocol.uuid16(0xFFD9))));
        assertNull(LightProtocol.detect(Collections.singletonList(LightProtocol.uuid16(0x2A19))));
        assertNull(LightProtocol.detect(null));
    }

    @Test
    public void looksLikeLightByNameOrService() {
        assertTrue(LightProtocol.looksLikeLight("ELK-BLEDOM   ", null));
        assertTrue(LightProtocol.looksLikeLight("duoCo Strip", null));
        assertTrue(LightProtocol.looksLikeLight(null,
                Collections.singletonList(LightProtocol.uuid16(0xFFD5))));
        assertFalse(LightProtocol.looksLikeLight("ZEEKR_button", null));
        assertFalse(LightProtocol.looksLikeLight(null, null));
    }

    @Test
    public void hueWheel() {
        assertEquals(0xFF0000, LightProtocol.hue(0));
        assertEquals(0xFFFF00, LightProtocol.hue(60));
        assertEquals(0x00FF00, LightProtocol.hue(120));
        assertEquals(0x0000FF, LightProtocol.hue(240));
        assertEquals(0xFF0000, LightProtocol.hue(360));
        assertEquals(LightProtocol.hue(300), LightProtocol.hue(-60));
    }

    @Test
    public void familyRoundTrips() {
        for (LightProtocol.Family family : LightProtocol.Family.values()) {
            assertEquals(family, LightProtocol.parseFamily(family.name()));
        }
        assertNull(LightProtocol.parseFamily(""));
    }

    @Test
    public void elkEffects() {
        LightProtocol.Family elk = LightProtocol.Family.ELK;
        assertEquals("7E 00 03 8A 03 00 00 00 EF",
                LightProtocol.hex(LightProtocol.effect(elk, LightProtocol.Effect.FADE_ALL, 0, 50)));
        assertEquals("7E 00 03 8B 03 00 00 00 EF",
                LightProtocol.hex(LightProtocol.effect(elk, LightProtocol.Effect.BREATHE, 0xFF1010, 50)));
        assertEquals("7E 00 03 9A 03 00 00 00 EF",
                LightProtocol.hex(LightProtocol.effect(elk, LightProtocol.Effect.BLINK, 0x00C0FF, 50)));
        assertEquals("7E 00 02 46 00 00 00 00 EF", LightProtocol.hex(LightProtocol.effectSpeed(elk, 70)));
    }

    @Test
    public void trionesEffectsCarrySpeed() {
        LightProtocol.Family triones = LightProtocol.Family.TRIONES;
        assertEquals("BB 25 01 44",
                LightProtocol.hex(LightProtocol.effect(triones, LightProtocol.Effect.FADE_ALL, 0, 100)));
        assertEquals("BB 37 1F 44",
                LightProtocol.hex(LightProtocol.effect(triones, LightProtocol.Effect.BLINK, 0xFFFFFF, 0)));
        assertNull(LightProtocol.effectSpeed(triones, 50));
    }

    @Test
    public void nearestBasicColor() {
        assertEquals(LightProtocol.RED, LightProtocol.basic(0xFF2000));
        assertEquals(LightProtocol.YELLOW, LightProtocol.basic(0xFFC000));
        assertEquals(LightProtocol.MAGENTA, LightProtocol.basic(0x8000FF));
        assertEquals(LightProtocol.WHITE, LightProtocol.basic(0x000000));
    }

    @Test
    public void previewFramesFollowEffect() {
        long period = EffectSwatchView.periodMs(50);
        assertEquals(0x123456, EffectSwatchView.frame(LightProtocol.Effect.STATIC, 0x123456, 50, 999));
        // Blink: on for the first half, off for the second, in the nearest basic colour
        assertEquals(0xFF0000, EffectSwatchView.frame(LightProtocol.Effect.BLINK, 0xFF2000, 50, 0));
        assertEquals(-1, EffectSwatchView.frame(LightProtocol.Effect.BLINK, 0xFF2000, 50, period * 3 / 4));
        // Jump: one colour per cycle
        assertEquals(0x00FF00, EffectSwatchView.frame(LightProtocol.Effect.JUMP_ALL, 0, 50, period + 1));
        assertTrue(EffectSwatchView.periodMs(100) < EffectSwatchView.periodMs(0));
    }
}
