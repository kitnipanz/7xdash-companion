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

    private boolean rain(AutomationRule rule, Boolean raining) {
        AutomationWatch.Sample s = sample();
        s.rain = raining;
        return !watch.step(Collections.singletonList(rule), s).isEmpty();
    }

    private static AutomationRule darkAndRainy() {
        AutomationRule rule = rule(AutomationTrigger.DARK, 0);
        rule.also.add(new AutomationRule.Condition(AutomationTrigger.RAIN_START.key, 0));
        return rule;
    }

    private boolean weather(AutomationRule rule, Integer dayNight, Boolean raining) {
        AutomationWatch.Sample s = sample();
        s.dayNight = dayNight;
        s.rain = raining;
        return !watch.step(Collections.singletonList(rule), s).isEmpty();
    }

    @Test
    public void darkAndRainy_firesWhenTheLastComesTrue_inEitherOrder() {
        AutomationRule rule = darkAndRainy();
        weather(rule, Signal.DAY, false);
        assertFalse("dark alone", weather(rule, Signal.NIGHT, false));
        assertTrue("then rain", weather(rule, Signal.NIGHT, true));
        assertFalse("still both", weather(rule, Signal.NIGHT, true));

        AutomationWatch other = new AutomationWatch();
        AutomationRule second = darkAndRainy();
        AutomationWatch.Sample s = sample();
        s.dayNight = Signal.DAY;
        s.rain = false;
        other.step(Collections.singletonList(second), s);
        s = sample();
        s.dayNight = Signal.DAY;
        s.rain = true;
        assertTrue("rain alone", other.step(Collections.singletonList(second), s).isEmpty());
        s = sample();
        s.dayNight = Signal.NIGHT;
        s.rain = true;
        assertEquals(1, other.step(Collections.singletonList(second), s).size());
    }

    @Test
    public void allTrueWhenFirstSeen_doesNotFire() {
        assertFalse(weather(darkAndRainy(), Signal.NIGHT, true));
    }

    @Test
    public void unknownConditionHoldsItBack() {
        AutomationRule rule = darkAndRainy();
        weather(rule, Signal.DAY, null);
        assertFalse(weather(rule, Signal.NIGHT, null));
    }

    @Test
    public void firesAgainAfterOneStopped_onceTheGapHasPassed() {
        AutomationRule rule = darkAndRainy();
        weather(rule, Signal.NIGHT, false);
        assertTrue(weather(rule, Signal.NIGHT, true));
        weather(rule, Signal.NIGHT, false);
        assertFalse("too soon", weather(rule, Signal.NIGHT, true));
        weather(rule, Signal.NIGHT, false);
        now += AutomationWatch.NUMBER_GAP_MS;
        assertTrue(weather(rule, Signal.NIGHT, true));
    }

    @Test
    public void gearAndSpeed_useTheNumberMargin() {
        AutomationRule rule = rule(AutomationTrigger.GEAR_D, 0);
        rule.also.add(new AutomationRule.Condition(AutomationTrigger.SPEED_ABOVE.key, 80));
        AutomationWatch.Sample s = sample();
        s.gear = "D";
        s.speedKmh = 50f;
        assertTrue(watch.step(Collections.singletonList(rule), s).isEmpty());
        s = sample();
        s.gear = "D";
        s.speedKmh = 85f;
        assertEquals(1, watch.step(Collections.singletonList(rule), s).size());
        s = sample();
        s.gear = "D";
        s.speedKmh = 79f;
        assertTrue(watch.step(Collections.singletonList(rule), s).isEmpty());
        now += AutomationWatch.NUMBER_GAP_MS;
        s = sample();
        s.gear = "D";
        s.speedKmh = 85f;
        assertTrue("stayed within the margin", watch.step(Collections.singletonList(rule), s).isEmpty());
    }

    @Test
    public void ruleSavedWithOneCondition_stillReads() {
        List<AutomationRule> back = AutomationRule.parseAll(
                "[{\"id\":\"a\",\"enabled\":true,\"trigger\":\"dark\",\"value\":0,\"steps\":[{\"action\":\"dim\",\"choice\":\"50\"}]}]");
        assertEquals(1, back.get(0).conditions().size());
        assertEquals(AutomationTrigger.DARK, back.get(0).trigger());
    }

    @Test
    public void conditionsComeBackFromSaving() {
        List<AutomationRule> back = AutomationRule.parseAll(AutomationRule.toJson(Collections.singletonList(darkAndRainy())));
        assertEquals(2, back.get(0).conditions().size());
        assertEquals(AutomationTrigger.RAIN_START, back.get(0).conditions().get(1).trigger());
    }

    @Test
    public void rainStart_firesOnTheChange_notWhenAlreadyRaining() {
        AutomationRule start = rule(AutomationTrigger.RAIN_START, 0);
        assertFalse("already raining when first seen", rain(start, true));
        assertFalse(rain(start, null));
        assertFalse(rain(start, false));
        assertTrue(rain(start, true));
    }

    @Test
    public void rainStop_firesWhenItStops() {
        AutomationRule stop = rule(AutomationTrigger.RAIN_STOP, 0);
        rain(stop, true);
        assertTrue(rain(stop, false));
    }

    @Test
    public void rainFlickering_runsOnceInTheGap() {
        AutomationRule start = rule(AutomationTrigger.RAIN_START, 0);
        rain(start, false);
        assertTrue(rain(start, true));
        rain(start, false);
        assertFalse("too soon", rain(start, true));
        rain(start, false);
        now += AutomationWatch.NUMBER_GAP_MS;
        assertTrue(rain(start, true));
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

    // ---- time, days, places

    private boolean at(AutomationRule rule, int minute, int dayBit) {
        AutomationWatch.Sample s = sample();
        s.minuteOfDay = minute;
        s.dayBit = dayBit;
        return !watch.step(Collections.singletonList(rule), s).isEmpty();
    }

    private static final int MONDAY = AutomationClock.dayBit(java.util.Calendar.MONDAY);
    private static final int SATURDAY = AutomationClock.dayBit(java.util.Calendar.SATURDAY);

    private static AutomationRule rule(AutomationTrigger first, int value, AutomationTrigger second, int secondValue) {
        AutomationRule rule = rule(first, value);
        rule.also.add(new AutomationRule.Condition(second.key, secondValue));
        return rule;
    }

    @Test
    public void aTimeRunsOnceWhenItComes() {
        AutomationRule rule = rule(AutomationTrigger.TIME_AT, 480);
        assertFalse(at(rule, 479, MONDAY));
        now += 60_000L;
        assertTrue(at(rule, 480, MONDAY));
        now += 15_000L;
        assertFalse(at(rule, 480, MONDAY));
        now += 60_000L;
        assertFalse(at(rule, 481, MONDAY));
    }

    @Test
    public void openingTheAppAtTheTimeDoesNotRunIt() {
        assertFalse(at(rule(AutomationTrigger.TIME_AT, 480), 480, MONDAY));
    }

    @Test
    public void aRuleWithDaysWaitsForTheRightDay() {
        AutomationRule rule = rule(AutomationTrigger.TIME_AT, 480, AutomationTrigger.DAYS, AutomationClock.WEEKDAYS);
        assertFalse(at(rule, 479, SATURDAY));
        now += 60_000L;
        assertFalse(at(rule, 480, SATURDAY));
        now += 60_000L;
        assertFalse(at(rule, 481, MONDAY));
        now += 60_000L;
        assertFalse(at(rule, 479, MONDAY));
        now += 60_000L;
        assertTrue(at(rule, 480, MONDAY));
    }

    @Test
    public void aGearInsideTheHoursRuns_outsideTheyDoNot() {
        AutomationRule rule = rule(AutomationTrigger.GEAR_D, 0, AutomationTrigger.TIME_BETWEEN,
                AutomationClock.span(420, 540));
        AutomationWatch.Sample s = sample();
        s.gear = "P";
        s.minuteOfDay = 600;
        s.dayBit = MONDAY;
        assertTrue(watch.step(Collections.singletonList(rule), s).isEmpty());
        s = sample();
        s.gear = "P";
        s.minuteOfDay = 500;
        s.dayBit = MONDAY;
        assertTrue(watch.step(Collections.singletonList(rule), s).isEmpty());
        s = sample();
        s.gear = "D";
        s.minuteOfDay = 501;
        s.dayBit = MONDAY;
        assertEquals(1, watch.step(Collections.singletonList(rule), s).size());
    }

    @Test
    public void theCarComingOnAtBootCountsWithOtherConditions() {
        AutomationRule rule = rule(AutomationTrigger.CAR_ON, 0, AutomationTrigger.DAYS, AutomationClock.WEEKDAYS);
        AutomationWatch.Sample s = sample();
        s.ignition = Signal.IGNITION_ON;
        s.justBooted = true;
        s.minuteOfDay = 480;
        s.dayBit = MONDAY;
        assertEquals(1, watch.step(Collections.singletonList(rule), s).size());
    }

    private static final com.kooo.evcam.nav.SavedPlaces.Place HOME = home();

    private static com.kooo.evcam.nav.SavedPlaces.Place home() {
        return com.kooo.evcam.nav.SavedPlaces.add(new java.util.ArrayList<>(), "Home", 13.7, 100.5);
    }

    /** Metres north of home. */
    private boolean near(AutomationRule rule, Double metres) {
        AutomationWatch.Sample s = sample();
        s.lat = metres == null ? null : 13.7 + metres / 111_195.0;
        s.lng = metres == null ? null : 100.5;
        s.places = Collections.singletonMap(HOME.id, HOME);
        now += 60_000L;
        return !watch.step(Collections.singletonList(rule), s).isEmpty();
    }

    @Test
    public void arrivingRunsOnce_wanderAtTheEdgeDoesNotRepeatIt() {
        AutomationRule rule = rule(AutomationTrigger.PLACE_ARRIVE, HOME.id);
        assertFalse(near(rule, 2_000.0));
        assertTrue(near(rule, 100.0));
        assertFalse(near(rule, 200.0));
        assertFalse(near(rule, 100.0));
        assertFalse(near(rule, 2_000.0));
        assertTrue(near(rule, 100.0));
    }

    @Test
    public void leavingRunsWhenClearOfThePlace() {
        AutomationRule rule = rule(AutomationTrigger.PLACE_LEAVE, HOME.id);
        assertFalse(near(rule, 50.0));
        assertFalse(near(rule, 200.0));
        assertTrue(near(rule, 300.0));
    }

    @Test
    public void startingInsideAPlaceIsNotArriving() {
        assertFalse(near(rule(AutomationTrigger.PLACE_ARRIVE, HOME.id), 50.0));
    }

    @Test
    public void noFixOrADeletedPlaceNeverRuns() {
        AutomationRule rule = rule(AutomationTrigger.PLACE_ARRIVE, HOME.id);
        assertFalse(near(rule, null));
        AutomationRule gone = rule(AutomationTrigger.PLACE_ARRIVE, 99);
        assertFalse(near(gone, 2_000.0));
        assertFalse(near(gone, 50.0));
    }
}
