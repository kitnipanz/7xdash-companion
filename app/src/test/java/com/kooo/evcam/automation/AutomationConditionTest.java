package com.kooo.evcam.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.nav.SavedPlaces;
import com.kooo.evcam.telemetry.Signal;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AutomationConditionTest {

    private final AutomationWatch watch = new AutomationWatch();
    private long now = 1_000_000L;
    private final int monday = AutomationCondition.dayBit(Calendar.MONDAY);
    private final int saturday = AutomationCondition.dayBit(Calendar.SATURDAY);

    private static AutomationRule rule(AutomationTrigger trigger, int value) {
        AutomationRule rule = new AutomationRule();
        rule.trigger = trigger.key;
        rule.value = value;
        rule.steps.add(new AutomationRule.Step(AutomationAction.NAVIGATE.key, "1"));
        return rule;
    }

    private boolean step(AutomationRule rule, String gear, int dayBit, Integer dayNight) {
        AutomationWatch.Sample s = new AutomationWatch.Sample();
        now += 1_000L;
        s.at = now;
        s.gear = gear;
        s.dayBit = dayBit;
        s.dayNight = dayNight;
        return !watch.step(Collections.singletonList(rule), s).isEmpty();
    }

    @Test
    public void dayBits_mondayFirst() {
        assertEquals(1, AutomationCondition.dayBit(Calendar.MONDAY));
        assertEquals(1 << 4, AutomationCondition.dayBit(Calendar.FRIDAY));
        assertEquals(1 << 6, AutomationCondition.dayBit(Calendar.SUNDAY));
        assertTrue((AutomationCondition.WORKDAYS & monday) != 0);
        assertFalse((AutomationCondition.WORKDAYS & saturday) != 0);
        assertTrue((AutomationCondition.WEEKEND & saturday) != 0);
    }

    @Test
    public void workdaysOnly_runsOnAWorkday_notAtTheWeekend() {
        AutomationRule d = rule(AutomationTrigger.GEAR_D, 0);
        d.setCondition(AutomationCondition.Group.DAYS, AutomationCondition.DAYS, AutomationCondition.WORKDAYS);
        step(d, "P", saturday, null);
        assertFalse(step(d, "D", saturday, null));
        step(d, "P", monday, null);
        assertTrue(step(d, "D", monday, null));
    }

    @Test
    public void unknownState_doesNotHold() {
        AutomationRule d = rule(AutomationTrigger.GEAR_D, 0);
        d.setCondition(AutomationCondition.Group.DAYLIGHT, AutomationCondition.DARK, 0);
        step(d, "P", monday, null);
        assertFalse("no day/night reading yet", step(d, "D", monday, null));
        step(d, "P", monday, Signal.NIGHT);
        assertTrue(step(d, "D", monday, Signal.NIGHT));
    }

    @Test
    public void allConditionsMustHold() {
        AutomationRule d = rule(AutomationTrigger.GEAR_D, 0);
        d.setCondition(AutomationCondition.Group.DAYS, AutomationCondition.DAYS, AutomationCondition.WORKDAYS);
        d.setCondition(AutomationCondition.Group.DAYLIGHT, AutomationCondition.DARK, 0);
        step(d, "P", monday, Signal.NIGHT + 1);
        assertFalse("workday but light", step(d, "D", monday, Signal.NIGHT + 1));
    }

    @Test
    public void leaveHomeOnAWorkday() {
        List<SavedPlaces.Place> list = new ArrayList<>();
        SavedPlaces.Place home = SavedPlaces.add(list, "Home", 13.7, 100.5);
        Map<Integer, SavedPlaces.Place> places = new HashMap<>();
        places.put(home.id, home);
        AutomationRule leave = rule(AutomationTrigger.PLACE_LEAVE, home.id);
        leave.setCondition(AutomationCondition.Group.DAYS, AutomationCondition.DAYS, AutomationCondition.WORKDAYS);
        assertFalse(located(leave, places, 0.0, saturday));
        assertFalse("weekend", located(leave, places, 0.01, saturday));
        assertFalse(located(leave, places, 0.0, monday));
        assertTrue(located(leave, places, 0.01, monday));
    }

    @Test
    public void atPlace_holdsOnlyInside() {
        List<SavedPlaces.Place> list = new ArrayList<>();
        SavedPlaces.Place home = SavedPlaces.add(list, "Home", 13.7, 100.5);
        Map<Integer, SavedPlaces.Place> places = new HashMap<>();
        places.put(home.id, home);
        AutomationRule on = rule(AutomationTrigger.GEAR_D, 0);
        on.setCondition(AutomationCondition.Group.PLACE, AutomationCondition.AT_PLACE, home.id);
        assertFalse(located(on, places, 0.01, monday, "P"));
        assertFalse("away", located(on, places, 0.01, monday, "D"));
        assertFalse(located(on, places, 0.0, monday, "P"));
        assertTrue(located(on, places, 0.0, monday, "D"));
    }

    @Test
    public void setCondition_keepsOnePerGroup_andOldRulesReadWithNone() {
        AutomationRule r = rule(AutomationTrigger.GEAR_D, 0);
        r.setCondition(AutomationCondition.Group.RAIN, AutomationCondition.RAINING, 0);
        r.setCondition(AutomationCondition.Group.RAIN, AutomationCondition.DRY, 0);
        assertEquals(1, r.conditions.size());
        assertEquals(AutomationCondition.DRY, r.conditions.get(0).kind());
        r.setCondition(AutomationCondition.Group.RAIN, null, 0);
        assertTrue(r.conditions.isEmpty());

        List<AutomationRule> old = AutomationRule.parseAll(
                "[{\"id\":\"a\",\"enabled\":true,\"trigger\":\"gear_d\",\"value\":0,"
                        + "\"steps\":[{\"action\":\"drive_mode\",\"choice\":\"sport\"}]}]");
        assertEquals(1, old.size());
        assertTrue(old.get(0).conditions.isEmpty());
        List<AutomationRule> unknown = AutomationRule.parseAll(
                "[{\"id\":\"a\",\"trigger\":\"gear_d\",\"steps\":[],\"conditions\":[{\"key\":\"moon\",\"value\":1}]}]");
        assertTrue(unknown.get(0).conditions.isEmpty());
    }

    private boolean located(AutomationRule rule, Map<Integer, SavedPlaces.Place> places, double north, int day) {
        return located(rule, places, north, day, null);
    }

    private boolean located(AutomationRule rule, Map<Integer, SavedPlaces.Place> places, double north, int day, String gear) {
        AutomationWatch.Sample s = new AutomationWatch.Sample();
        now += 1_000L;
        s.at = now;
        s.places = places;
        s.lat = 13.7 + north;
        s.lng = 100.5;
        s.dayBit = day;
        s.gear = gear;
        return !watch.step(Collections.singletonList(rule), s).isEmpty();
    }

    private boolean stepAt(AutomationRule rule, String gear, int minute) {
        AutomationWatch.Sample s = new AutomationWatch.Sample();
        now += 1_000L;
        s.at = now;
        s.gear = gear;
        s.dayBit = monday;
        s.minute = minute;
        return !watch.step(Collections.singletonList(rule), s).isEmpty();
    }

    @Test
    public void afterSixPm_runsInTheEvening_notInTheAfternoon() {
        AutomationRule d = rule(AutomationTrigger.GEAR_D, 0);
        d.setCondition(AutomationCondition.Group.TIME, AutomationCondition.AFTER_TIME, 18 * 60);
        stepAt(d, "P", 17 * 60 + 59);
        assertFalse("17:59", stepAt(d, "D", 17 * 60 + 59));
        stepAt(d, "P", 18 * 60);
        assertTrue("18:00", stepAt(d, "D", 18 * 60));
        stepAt(d, "P", 23 * 60 + 59);
        assertTrue("23:59", stepAt(d, "D", 23 * 60 + 59));
    }

    @Test
    public void beforeNine_runsInTheMorning_notAfter() {
        AutomationRule d = rule(AutomationTrigger.GEAR_D, 0);
        d.setCondition(AutomationCondition.Group.TIME, AutomationCondition.BEFORE_TIME, 9 * 60);
        stepAt(d, "P", 8 * 60 + 59);
        assertTrue("08:59", stepAt(d, "D", 8 * 60 + 59));
        stepAt(d, "P", 9 * 60);
        assertFalse("09:00", stepAt(d, "D", 9 * 60));
    }

    @Test
    public void unknownTime_doesNotHold() {
        assertFalse(AutomationCondition.timeHolds(AutomationCondition.AFTER_TIME, 0, -1));
        assertFalse(AutomationCondition.timeHolds(AutomationCondition.BEFORE_TIME, 24 * 60, -1));
    }

    @Test
    public void timeCondition_survivesSaving() {
        AutomationRule d = rule(AutomationTrigger.CAR_ON, 0);
        d.setCondition(AutomationCondition.Group.TIME, AutomationCondition.AFTER_TIME, 18 * 60);
        AutomationRule back = AutomationRule.parseAll(AutomationRule.toJson(Collections.singletonList(d))).get(0);
        AutomationRule.Condition c = back.condition(AutomationCondition.Group.TIME);
        assertEquals(AutomationCondition.AFTER_TIME, c.kind());
        assertEquals(18 * 60, c.value);
    }

    @Test
    public void clock_is24Hour() {
        assertEquals("18:00", AutomationCondition.clock(null, 18 * 60));
        assertEquals("07:05", AutomationCondition.clock(null, 7 * 60 + 5));
        assertEquals("00:00", AutomationCondition.clock(null, 0));
    }
}
