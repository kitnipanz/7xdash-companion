package com.kooo.evcam.light;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GearLightRulesTest {

    private static GearLightRules on() {
        GearLightRules rules = GearLightRules.defaults();
        rules.enabled = true;
        return rules;
    }

    @Test
    public void offOrUnknownGearLeavesLight() {
        GearLightRules rules = GearLightRules.defaults();
        assertNull(rules.pick("P", 0f, null).look);
        assertNull(on().pick(null, 0f, null).look);
        assertNull(on().pick("N", 0f, null).key);
    }

    @Test
    public void parkAndReverse() {
        GearLightRules rules = on();
        assertEquals(GearLightRules.KEY_P, rules.pick("P", null, null).key);
        assertEquals(GearLightRules.KEY_R, rules.pick("R", 5f, null).key);
        assertFalse(rules.pick("P", null, null).wantsSpeed);
    }

    @Test
    public void driveStoppedWithHysteresis() {
        GearLightRules rules = on();
        // From P into D at 0: stopped, and stays stopped until 3 km/h
        assertEquals(GearLightRules.KEY_D_STOPPED, rules.pick("D", 0f, "P").key);
        assertEquals(GearLightRules.KEY_D_STOPPED, rules.pick("D", 2f, "D0").key);
        assertEquals(GearLightRules.KEY_D, rules.pick("D", 3f, "D0").key);
        // Moving stays moving down to 1 km/h
        assertEquals(GearLightRules.KEY_D, rules.pick("D", 1.5f, "D").key);
        assertEquals(GearLightRules.KEY_D_STOPPED, rules.pick("D", 0.5f, "D").key);
        assertTrue(rules.pick("D", 0f, "D").wantsSpeed);
    }

    @Test
    public void noStoppedRuleFallsBackToDrive() {
        GearLightRules rules = on();
        rules.driveStopped = null;
        assertEquals(GearLightRules.KEY_D, rules.pick("D", 0f, "P").key);
    }

    @Test
    public void unknownSpeedIsDrive() {
        assertEquals(GearLightRules.KEY_D, on().pick("D", null, "P").key);
    }

    @Test
    public void speedBandsWithHysteresis() {
        GearLightRules rules = on(); // bands from 80 and 120
        assertEquals(GearLightRules.KEY_D, rules.pick("D", 79f, "D").key);
        assertEquals("band:0", rules.pick("D", 80f, "D").key);
        assertEquals("band:1", rules.pick("D", 125f, "band:0").key);
        // Holds band 1 down to 117, drops at 116
        assertEquals("band:1", rules.pick("D", 118f, "band:1").key);
        assertEquals("band:0", rules.pick("D", 116f, "band:1").key);
        assertEquals("band:0", rules.pick("D", 78f, "band:0").key);
        assertEquals(GearLightRules.KEY_D, rules.pick("D", 76f, "band:0").key);
    }

    @Test
    public void jsonRoundTripSortsBands() {
        GearLightRules rules = on();
        rules.bands.add(new GearLightRules.Band(30, new LightLook(0x00FF00, 50, LightProtocol.Effect.BLINK, 20)));
        rules.reverse = null;
        GearLightRules back = GearLightRules.parse(rules.toJson());
        assertTrue(back.enabled);
        assertNull(back.reverse);
        assertEquals(30, back.bands.get(0).fromKmh);
        assertEquals(LightProtocol.Effect.BLINK, back.bands.get(0).look.effect());
        assertEquals(rules.park, back.park);
    }

    @Test
    public void badJsonGivesDefaults() {
        assertFalse(GearLightRules.parse("{not json").enabled);
        assertEquals(2, GearLightRules.parse("").bands.size());
    }

    @Test
    public void blendsBetweenSolidSpeedColors() {
        GearLightRules rules = on();
        rules.bands.clear();
        rules.bands.add(new GearLightRules.Band(100, new LightLook(0x0000FF, 100, LightProtocol.Effect.STATIC, 50)));
        rules.bands.add(new GearLightRules.Band(120, new LightLook(0xFF0000, 60, LightProtocol.Effect.STATIC, 50)));
        GearLightRules.Pick at105 = rules.pick("D", 105f, "D");
        assertEquals("band:0~5", at105.key);
        // a quarter of the way: 75 % blue, 25 % red
        assertEquals(0x4000BF, at105.look.color);
        assertEquals(90, at105.look.brightness);
        assertEquals(0xBF0040, rules.pick("D", 115f, at105.key).look.color);
        // At and past the top colour: that colour, solid
        assertEquals(0xFF0000, rules.pick("D", 120f, "band:0~19").look.color);
        assertEquals(0xFF0000, rules.pick("D", 119.9f, "band:1").look.color);
        assertEquals(0x0000FF, rules.pick("D", 100f, "D").look.color);
    }

    @Test
    public void animationsOrBlendOffDoNotMix() {
        GearLightRules rules = on(); // 80 solid, 120 blinking
        assertEquals("band:0", rules.pick("D", 100f, "D").key);
        rules.bands.get(1).look.effect = LightProtocol.Effect.STATIC.name();
        rules.blend = false;
        assertEquals("band:0", rules.pick("D", 100f, "D").key);
    }

    @Test
    public void oldSavedRulesBlend() {
        assertTrue(GearLightRules.parse("{\"enabled\":true,\"bands\":[]}").blend);
    }
}
