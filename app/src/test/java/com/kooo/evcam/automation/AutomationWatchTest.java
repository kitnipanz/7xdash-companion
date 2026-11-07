package com.kooo.evcam.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.telemetry.Signal;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

public class AutomationWatchTest {

    private final AutomationWatch watch = new AutomationWatch();
    private long now = 1_000_000L;

    private static AutomationRule rule(AutomationTrigger trigger, int value) {
        AutomationRule rule = new AutomationRule();
        rule.trigger = trigger.key;
        rule.value = value;
        rule.steps.add(new AutomationRule.Step(AutomationAction.DRIVE_MODE.key, "sport"));
        return rule;
    }

    private AutomationWatch.Sample sample() {
        AutomationWatch.Sample s = new AutomationWatch.Sample();
        now += 1_000L;
        s.at = now;
        return s;
    }

    private boolean gear(AutomationRule rule, String gear) {
        AutomationWatch.Sample s = sample();
        s.gear = gear;
        return !watch.step(Collections.singletonList(rule), s).isEmpty();
    }

    private boolean ignition(AutomationRule rule, Integer code, boolean booted, String gear) {
        AutomationWatch.Sample s = sample();
        s.ignition = code;
        s.justBooted = booted;
        s.gear = gear;
        return !watch.step(Collections.singletonList(rule), s).isEmpty();
    }

    private boolean speed(AutomationRule rule, Float kmh) {
        AutomationWatch.Sample s = sample();
        s.speedKmh = kmh;
        return !watch.step(Collections.singletonList(rule), s).isEmpty();
    }

    @Test
    public void shiftingIntoTheGearFires_onlyOnTheChange() {
        AutomationRule d = rule(AutomationTrigger.GEAR_D, 0);
        assertFalse("already in D when first seen", gear(d, "D"));
        assertFalse(gear(d, "D"));
        assertFalse(gear(d, "P"));
        assertTrue(gear(d, "D"));
        assertFalse(gear(d, "D"));
    }

    @Test
    public void unknownGearInBetweenIsNotAChange() {
        AutomationRule d = rule(AutomationTrigger.GEAR_D, 0);
        gear(d, "D");
        assertFalse(gear(d, null));
        assertFalse(gear(d, "D"));
    }

    @Test
    public void carOn_firesWhenItComesOn_notBetweenOnAndDriving() {
        AutomationRule on = rule(AutomationTrigger.CAR_ON, 0);
        assertFalse(ignition(on, Signal.IGNITION_ACC, false, null));
        assertTrue(ignition(on, Signal.IGNITION_ON, false, null));
        assertFalse(ignition(on, Signal.IGNITION_DRIVING, false, null));
        assertFalse(ignition(on, Signal.IGNITION_ON, false, null));
    }

    @Test
    public void carOn_firstReadingCounts_onlyRightAfterBootAndParked() {
        AutomationRule on = rule(AutomationTrigger.CAR_ON, 0);
        assertTrue(ignition(on, Signal.IGNITION_ON, true, "P"));

        AutomationWatch restarted = new AutomationWatch();
        AutomationWatch.Sample s = sample();
        s.ignition = Signal.IGNITION_ON;
        s.gear = "D";
        s.justBooted = true;
        assertTrue("restart while driving", restarted.step(Collections.singletonList(on), s).isEmpty());

        AutomationWatch late = new AutomationWatch();
        AutomationWatch.Sample t = sample();
        t.ignition = Signal.IGNITION_ON;
        t.gear = "P";
        assertTrue("app restarted long after boot", late.step(Collections.singletonList(on), t).isEmpty());
    }

    @Test
    public void carOff_firesGoingDownToAcc() {
        AutomationRule off = rule(AutomationTrigger.CAR_OFF, 0);
        assertFalse(ignition(off, Signal.IGNITION_DRIVING, false, null));
        assertTrue(ignition(off, Signal.IGNITION_ACC, false, null));
    }

    @Test
    public void darkAndLight_fireOnTheirOwnChange() {
        AutomationRule dark = rule(AutomationTrigger.DARK, 0);
        AutomationRule light = rule(AutomationTrigger.LIGHT, 0);
        List<AutomationRule> both = java.util.Arrays.asList(dark, light);
        AutomationWatch.Sample s = sample();
        s.dayNight = Signal.DAY;
        assertTrue(watch.step(both, s).isEmpty());
        s = sample();
        s.dayNight = Signal.NIGHT;
        assertEquals(Collections.singletonList(dark), watch.step(both, s));
        s = sample();
        s.dayNight = Signal.DAY;
        assertEquals(Collections.singletonList(light), watch.step(both, s));
    }

    @Test
    public void speedAbove_firesOnce_andNeedsToDropBack() {
        AutomationRule fast = rule(AutomationTrigger.SPEED_ABOVE, 80);
        assertFalse(speed(fast, 50f));
        assertTrue(speed(fast, 80f));
        assertFalse(speed(fast, 90f));
        assertFalse("within the margin", speed(fast, 78f));
        now += AutomationWatch.NUMBER_GAP_MS;
        assertFalse(speed(fast, 80f));
        assertFalse("dropped back", speed(fast, 76f));
        assertTrue(speed(fast, 81f));
    }

    @Test
    public void speedAbove_alreadyPastWhenFirstSeen_doesNotFire() {
        AutomationRule fast = rule(AutomationTrigger.SPEED_ABOVE, 80);
        assertFalse(speed(fast, 100f));
        assertFalse(speed(fast, 110f));
    }

    @Test
    public void speedBelow_firesGoingDown() {
        AutomationRule slow = rule(AutomationTrigger.SPEED_BELOW, 30);
        assertFalse(speed(slow, 60f));
        assertTrue(speed(slow, 29f));
    }

    @Test
    public void quickFlappingWaitsForTheGap_thenFiresIfStillPast() {
        AutomationRule fast = rule(AutomationTrigger.SPEED_ABOVE, 80);
        speed(fast, 50f);
        assertTrue(speed(fast, 85f));
        speed(fast, 70f);
        assertFalse("too soon after the last run", speed(fast, 85f));
        now += AutomationWatch.NUMBER_GAP_MS;
        assertTrue(speed(fast, 85f));
    }

    @Test
    public void ruleThatIsOffOrEmptyNeverFires() {
        AutomationRule off = rule(AutomationTrigger.GEAR_D, 0);
        off.enabled = false;
        gear(off, "P");
        assertFalse(gear(off, "D"));
        AutomationRule empty = rule(AutomationTrigger.GEAR_P, 0);
        empty.steps.clear();
        assertFalse(gear(empty, "P"));
    }

    @Test
    public void isOn_readsOnDrivingAndLow() {
        assertEquals(Boolean.TRUE, AutomationWatch.isOn(Signal.IGNITION_ON));
        assertEquals(Boolean.TRUE, AutomationWatch.isOn(Signal.IGNITION_DRIVING));
        assertEquals(Boolean.FALSE, AutomationWatch.isOn(Signal.IGNITION_ACC));
        assertNull(AutomationWatch.isOn(null));
        assertNull(AutomationWatch.isOn(0));
    }

    @Test
    public void driveModeValues_matchWhatTheCarShowed() {
        assertEquals(570491137, DriveMode.value("comfort").intValue());
        assertEquals(570491138, DriveMode.value("normal").intValue());
        assertEquals(570491139, DriveMode.value("sport").intValue());
        assertEquals(DriveMode.KNOWN_ID + 1, DriveMode.COMFORT);
        assertNull(DriveMode.value("eco"));
    }

    @Test
    public void savedRules_comeBack_andDamagedOnesAreDropped() {
        AutomationRule rule = rule(AutomationTrigger.SPEED_ABOVE, 100);
        List<AutomationRule> back = AutomationRule.parseAll(AutomationRule.toJson(Collections.singletonList(rule)));
        assertEquals(1, back.size());
        assertEquals(rule.id, back.get(0).id);
        assertEquals(100, back.get(0).value);
        assertEquals("sport", back.get(0).steps.get(0).choice);

        assertTrue(AutomationRule.parseAll(null).isEmpty());
        assertTrue(AutomationRule.parseAll("{not json").isEmpty());
        assertTrue(AutomationRule.parseAll("[{\"id\":\"a\",\"trigger\":\"nope\",\"steps\":[]}]").isEmpty());
    }
}
