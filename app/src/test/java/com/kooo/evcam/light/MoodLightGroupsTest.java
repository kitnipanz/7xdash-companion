package com.kooo.evcam.light;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class MoodLightGroupsTest {

    @Test
    public void oldSetupBecomesFirstGroup() {
        GearLightRules rules = GearLightRules.defaults();
        rules.enabled = true;
        MoodLightGroups groups = MoodLightGroups.migrate("Lights",
                Arrays.asList("AA:01", "AA:02"), rules.toJson(), 0x00FF00, 40);
        assertEquals(1, groups.groups.size());
        MoodLightGroups.Group g = groups.groups.get(0);
        assertEquals("Lights", g.name);
        assertEquals(Arrays.asList("AA:01", "AA:02"), g.lights);
        assertTrue(g.rules.enabled);
        assertEquals(0x00FF00, g.color);
        assertEquals(40, g.brightness);
    }

    @Test
    public void emptyOrBadJsonGivesOneGroup() {
        assertEquals(1, MoodLightGroups.parse("", "Lights").groups.size());
        assertEquals("Lights", MoodLightGroups.parse("{oops", "Lights").groups.get(0).name);
    }

    @Test
    public void groupsKeepOwnRulesThroughJson() {
        MoodLightGroups groups = MoodLightGroups.parse("", "Lights");
        MoodLightGroups.Group foot = groups.groups.get(0);
        MoodLightGroups.Group dash = groups.add("Dashboard");
        foot.rules.enabled = true;
        dash.rules.park = null;
        dash.lights.add("BB:01");
        MoodLightGroups back = MoodLightGroups.parse(groups.toJson(), "Lights");
        assertEquals(2, back.groups.size());
        assertTrue(back.find(foot.id).rules.enabled);
        assertFalse(back.find(dash.id).rules.enabled);
        assertNull(back.find(dash.id).rules.park);
        assertEquals("Dashboard", back.owner("BB:01").name);
    }

    @Test
    public void lightIsInOneGroupOnly() {
        MoodLightGroups groups = MoodLightGroups.parse("", "Lights");
        MoodLightGroups.Group a = groups.groups.get(0);
        MoodLightGroups.Group b = groups.add("B");
        groups.moveLight("CC:01", a);
        groups.moveLight("CC:01", b);
        assertFalse(a.lights.contains("CC:01"));
        assertSame(b, groups.owner("CC:01"));
        assertEquals(1, groups.lightCount());
        // A duplicate in saved JSON goes to the first group that lists it
        a.lights.add("CC:01");
        MoodLightGroups back = MoodLightGroups.parse(groups.toJson(), "Lights");
        assertEquals(1, back.lightCount());
        assertEquals(a.id, back.owner("CC:01").id);
    }

    @Test
    public void lastGroupStays() {
        MoodLightGroups groups = MoodLightGroups.parse("", "Lights");
        assertFalse(groups.remove(groups.groups.get(0).id));
        MoodLightGroups.Group b = groups.add("B");
        assertTrue(groups.remove(b.id));
        assertEquals(1, groups.groups.size());
    }
}
