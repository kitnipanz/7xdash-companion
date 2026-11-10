package com.kooo.evcam.obd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ObdDecodeTest {

    private static List<byte[]> frame(int... values) {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        return Collections.singletonList(bytes);
    }

    @Test
    public void formulas() {
        assertEquals(42, ObdPid.SPEED.decode(frame(0x41, 0x0D, 42)), 1e-9);
        assertEquals(50, ObdPid.AMBIENT.decode(frame(0x41, 0x46, 90)), 1e-9);
        assertEquals(100, ObdPid.BATTERY_REMAINING.decode(frame(0x41, 0x5B, 0xFF)), 1e-9);
        assertEquals(13.5, ObdPid.MODULE_VOLTAGE.decode(frame(0x41, 0x42, 0x34, 0xBC)), 1e-9);
        assertEquals(12345.6, ObdPid.ODOMETER.decode(frame(0x41, 0xA6, 0, 1, 0xE2, 0x40)), 1e-9);
    }

    @Test
    public void bytesAboveSevenBitsAreNotNegative() {
        assertEquals(65.28, ObdPid.MODULE_VOLTAGE.decode(frame(0x41, 0x42, 0xFF, 0x00)), 1e-9);
        assertEquals(215, ObdPid.AMBIENT.decode(frame(0x41, 0x46, 0xFF)), 1e-9);
    }

    @Test
    public void ignoresAnswersToOtherQuestions() {
        assertNull(ObdPid.SPEED.decode(frame(0x41, 0x0C, 42)));
        assertNull(ObdPid.MODULE_VOLTAGE.decode(frame(0x41, 0x42, 0x1A)));
    }

    @Test
    public void noEngineValuesOnThisEv() {
        for (ObdPid pid : ObdPid.values()) {
            assertTrue(pid.name(), !Arrays.asList(0x04, 0x05, 0x0C, 0x0F, 0x11, 0x2F, 0x5C, 0x5E).contains(pid.pid));
        }
    }

    @Test
    public void zeekrAnswerIsShownRaw() {
        ZeekrProbe.Result result = ZeekrProbe.read(0x4801, frame(0x62, 0x48, 0x01, 0x12, 0xAB));
        assertEquals("12AB", result.hex);
        assertEquals(-1, result.refusal);
    }

    @Test
    public void zeekrRefusalKeepsItsCode() {
        ZeekrProbe.Result result = ZeekrProbe.read(0x4801, frame(0x7F, 0x22, 0x33));
        assertNull(result.hex);
        assertEquals(0x33, result.refusal);
        assertTrue(result.reachedModule());
    }

    @Test
    public void zeekrWaitIsNotARefusalAndOtherIdentifiersAreNotOurs() {
        assertEquals(false, ZeekrProbe.read(0x4801, frame(0x7F, 0x22, 0x78)).reachedModule());
        assertEquals(false, ZeekrProbe.read(0x4801, frame(0x62, 0x49, 0x1B, 0x01)).reachedModule());
    }

    @Test
    public void supportedBitmap() {
        List<Integer> found = new ArrayList<>();
        byte[] bytes = {0x41, 0x00, (byte) 0x80, 0x08, 0x00, 0x01};
        ObdCodes.addSupported(bytes, 0x00, found);
        assertEquals(Arrays.asList(0x01, 0x0D, 0x20), found);
    }

    @Test
    public void supportedBitmapFromALaterBlock() {
        List<Integer> found = new ArrayList<>();
        ObdCodes.addSupported(new byte[]{0x41, 0x40, 0, 0, 0, 0x20}, 0x40, found);
        assertEquals(Collections.singletonList(0x5B), found);
    }

    @Test
    public void canTroubleCodesFollowTheirCount() {
        List<byte[]> frames = frame(0x43, 2, 0x01, 0x23, 0xC1, 0x56, 0xAA, 0xAA);
        assertEquals(Arrays.asList("P0123", "U0156"),
                new ArrayList<>(ObdCodes.troubleCodes(frames, 0x43, true)));
    }

    @Test
    public void olderBusHasNoCountAndPadsWithZeros() {
        List<byte[]> frames = frame(0x43, 0x01, 0x23, 0x91, 0x56, 0, 0);
        assertEquals(Arrays.asList("P0123", "B1156"),
                new ArrayList<>(ObdCodes.troubleCodes(frames, 0x43, false)));
    }

    @Test
    public void noCodes() {
        assertTrue(ObdCodes.troubleCodes(frame(0x43, 0), 0x43, true).isEmpty());
    }

    /** Values as the EX30 sends them, from its confirmed map. */
    @Test
    public void batteryModuleValuesDecodeAsOnTheEx30() {
        assertEquals(343.12, ZeekrProbe.value(ZeekrProbe.PACK_VOLTAGE, bytes(0x86, 0x08)), 1e-9);
        assertEquals(2.7, ZeekrProbe.value(ZeekrProbe.PACK_CURRENT, bytes(0x40, 0x1B)), 1e-9);
        assertEquals(-6.1, ZeekrProbe.value(ZeekrProbe.PACK_CURRENT, bytes(0x3F, 0xC3)), 1e-9);
        assertEquals(30.0, ZeekrProbe.value(ZeekrProbe.PACK_TEMP_AVG, bytes(0x1F, 0x40)), 1e-9);
        assertEquals(31.45, ZeekrProbe.value(ZeekrProbe.PACK_TEMP_MAX, bytes(0x07, 0x1F, 0xD1)), 1e-9);
        assertEquals(93.68, ZeekrProbe.value(ZeekrProbe.STATE_OF_HEALTH, bytes(0, 0, 0x24, 0x98)), 1e-9);
        assertEquals(41_234, ZeekrProbe.value(ZeekrProbe.ODOMETER, bytes(0x00, 0xA1, 0x12)), 1e-9);
    }

    @Test
    public void shortOrUnknownBatteryAnswersHaveNoValue() {
        assertNull(ZeekrProbe.value(ZeekrProbe.PACK_VOLTAGE, bytes(0x86)));
        assertNull(ZeekrProbe.value(ZeekrProbe.STATE_OF_HEALTH, bytes(0, 0, 0x24)));
        assertNull(ZeekrProbe.value(0x4900, bytes(1, 2, 3, 4)));
        assertNull(ZeekrProbe.value(ZeekrProbe.PACK_VOLTAGE, null));
    }

    @Test
    public void packPowerFromVoltageAndCurrent() {
        ZeekrProbe.Result volts = ZeekrProbe.read(0x4801, frame(0x62, 0x48, 0x01, 0x86, 0x08));
        ZeekrProbe.Result amps = ZeekrProbe.read(0x4802, frame(0x62, 0x48, 0x02, 0x40, 0x1B));
        assertEquals(0.926, ZeekrProbe.powerKw(volts, amps), 1e-3);
        assertNull(ZeekrProbe.powerKw(volts, null));
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }
}
