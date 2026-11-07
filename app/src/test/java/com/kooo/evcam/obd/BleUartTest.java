package com.kooo.evcam.obd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

public class BleUartTest {

    private static UUID id(int value) {
        return new UUID(((long) value << 32) | 0x1000L, 0x800000805F9B34FBL);
    }

    private static BleUart.Characteristic c(UUID service, UUID uuid, boolean notifies, boolean writes) {
        return new BleUart.Characteristic(service, uuid, notifies, writes);
    }

    @Test
    public void separateNotifyAndWrite() {
        BleUart.Choice choice = BleUart.pick(Arrays.asList(
                c(id(0xFFF0), id(0xFFF1), true, false), c(id(0xFFF0), id(0xFFF2), false, true)));
        assertEquals(id(0xFFF1), choice.notify);
        assertEquals(id(0xFFF2), choice.write);
    }

    @Test
    public void oneCharacteristicDoesBoth() {
        BleUart.Choice choice = BleUart.pick(Collections.singletonList(c(id(0xFFE0), id(0xFFE1), true, true)));
        assertEquals(id(0xFFE1), choice.notify);
        assertEquals(id(0xFFE1), choice.write);
    }

    @Test
    public void aSeparateWriteBeatsTheNotifyOneThatAlsoWrites() {
        BleUart.Choice choice = BleUart.pick(Arrays.asList(
                c(id(0xFFF0), id(0xFFF1), true, true), c(id(0xFFF0), id(0xFFF2), false, true)));
        assertEquals(id(0xFFF2), choice.write);
    }

    @Test
    public void knownServiceBeatsAnUnknownOneFoundFirst() {
        UUID other = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
        BleUart.Choice choice = BleUart.pick(Arrays.asList(
                c(other, UUID.randomUUID(), true, true), c(id(0xFFE0), id(0xFFE1), true, true)));
        assertEquals(id(0xFFE0), choice.service);
    }

    @Test
    public void unknownServiceStillWorks() {
        UUID other = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
        assertEquals(other, BleUart.pick(Arrays.asList(
                c(other, UUID.randomUUID(), true, false), c(other, UUID.randomUUID(), false, true))).service);
    }

    @Test
    public void standardServicesAreNotAPipe() {
        assertNull(BleUart.pick(Collections.singletonList(c(id(0x180A), id(0x2A29), true, true))));
    }

    @Test
    public void bothHalvesMustBeInOneService() {
        assertNull(BleUart.pick(Arrays.asList(
                c(id(0xFFF0), id(0xFFF1), true, false), c(id(0xFFE0), id(0xFFE1), false, true))));
    }

    @Test
    public void adapterNames() {
        assertTrue(BleUart.looksLikeObd("OBDII"));
        assertTrue(BleUart.looksLikeObd("vLinker MC"));
        assertTrue(BleUart.looksLikeObd("V-LINK"));
        assertTrue(BleUart.looksLikeObd("IOS-Vlink"));
        assertTrue(BleUart.looksLikeObd("Vgate iCar Pro"));
        assertFalse(BleUart.looksLikeObd("Xiaomi Band"));
        assertFalse(BleUart.looksLikeObd(null));
    }
}
