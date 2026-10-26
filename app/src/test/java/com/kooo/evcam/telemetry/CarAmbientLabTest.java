package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class CarAmbientLabTest {

    @Test
    public void picksAmbientLightNamesOnly() {
        assertTrue(CarAmbientLab.looksAmbient("SETTING_FUNC_AMBIENCE_LIGHT_COLOR"));
        assertTrue(CarAmbientLab.looksAmbient("AMBIENT_LIGHT_BRIGHTNESS"));
        assertTrue(CarAmbientLab.looksAmbient("FUNC_ATMOSPHERE_LAMP"));
        assertTrue(CarAmbientLab.looksAmbient("interior_light_mode"));
        assertFalse(CarAmbientLab.looksAmbient("HIGH_BEAM"));
        assertFalse(CarAmbientLab.looksAmbient("DOOR_LOCK"));
        assertFalse(CarAmbientLab.looksAmbient(null));
    }

    @Test
    public void functionsAreLargeIdsOptionsAreSmall() {
        assertTrue(new CarAmbientLab.Constant("IVehicle", "AMBIENCE_LIGHT", 0x20150100).looksLikeFunction());
        assertFalse(new CarAmbientLab.Constant("IVehicle", "AMBIENCE_LIGHT_ON", 1).looksLikeFunction());
    }

    @Test
    public void optionsShareTheFunctionsNameAndClass() {
        CarAmbientLab.Constant mode = new CarAmbientLab.Constant("IVehicle", "SETTING_FUNC_AMBIENCE_LIGHT_MODE", 0x20150200);
        CarAmbientLab.Constant breathe = new CarAmbientLab.Constant("IVehicle", "AMBIENCE_LIGHT_MODE_BREATH", 2);
        CarAmbientLab.Constant music = new CarAmbientLab.Constant("IVehicle", "AMBIENCE_LIGHT_MODE_MUSIC", 3);
        CarAmbientLab.Constant otherClass = new CarAmbientLab.Constant("IHvac", "AMBIENCE_LIGHT_MODE_X", 4);
        CarAmbientLab.Constant otherFunction = new CarAmbientLab.Constant("IVehicle", "AMBIENCE_LIGHT_MODE_ZONE", 0x20150300);
        List<CarAmbientLab.Constant> got = CarAmbientLab.optionsFor(mode,
                Arrays.asList(mode, breathe, music, otherClass, otherFunction));
        assertEquals(Arrays.asList(breathe, music), got);
        assertEquals("AMBIENCE_LIGHT_MODE", CarAmbientLab.stem("SETTING_FUNC_AMBIENCE_LIGHT_MODE"));
    }

    @Test
    public void functionMissingFromThisHeadUnitIsNull() {
        assertNull(CarAmbientLab.functionId(CarAmbientLab.TOPZONES));
        assertNull(CarAmbientLab.functionId(CarAmbientLab.TOPZONES_COLOR));
    }

    @Test
    public void sortsLightNamesIntoKinds() {
        assertEquals(CarAmbientLab.Kind.HAZARD, CarAmbientLab.kindOf("SETTING_FUNC_HAZARD_LIGHT"));
        assertEquals(CarAmbientLab.Kind.HAZARD, CarAmbientLab.kindOf("FUNC_DOUBLE_FLASH"));
        assertEquals(CarAmbientLab.Kind.FOOT, CarAmbientLab.kindOf("SETTING_FUNC_AMBIENCE_LIGHT_FOOT_ZONE"));
        assertEquals(CarAmbientLab.Kind.CABIN, CarAmbientLab.kindOf("SETTING_FUNC_READING_LIGHT_REAR"));
        assertEquals(CarAmbientLab.Kind.CABIN, CarAmbientLab.kindOf("FUNC_DOME_LAMP"));
        assertEquals(CarAmbientLab.Kind.AMBIENT, CarAmbientLab.kindOf("SETTING_FUNC_AMBIENCE_LIGHT_MAINZONES_INTENSITY"));
        assertNull(CarAmbientLab.kindOf("DOOR_LOCK"));
        assertNull(CarAmbientLab.kindOf(null));
        assertEquals(CarAmbientLab.Kind.CABIN,
                new CarAmbientLab.Constant("ILight", "SETTING_FUNC_ROOF_LIGHT", 0x20150400).kind);
    }

    @Test
    public void hazardAndWatchedNamesOnlyInPark() {
        assertNull(CarAmbientLab.parkedBlock("P"));
        assertTrue(CarAmbientLab.parkedBlock("D").startsWith("Refused"));
        assertTrue(CarAmbientLab.parkedBlock(null).contains("unknown"));
    }

    @Test
    public void watchingListsWhatChangedWithBeforeAndNow() {
        CarAmbientLab.Constant a = new CarAmbientLab.Constant("IVehicle", "FUNC_A", 0x20000001);
        CarAmbientLab.Constant b = new CarAmbientLab.Constant("IVehicle", "FUNC_B", 0x20000002);
        CarAmbientLab.Constant c = new CarAmbientLab.Constant("IVehicle", "FUNC_C", 0x20000003);
        List<CarAmbientLab.Constant> got = CarAmbientLab.changed(Arrays.asList(a, b, c),
                new String[]{"0", "1", "threw x"}, new String[]{"0", "2", "threw x"});
        assertEquals(Arrays.asList(b), got);
        assertEquals("1", b.before);
        assertEquals("2", b.current);
        assertTrue(b.watched);
        assertFalse(a.watched);
    }
}
