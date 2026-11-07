package com.kooo.evcam.climate;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Readings follow the factory off and on cycle in the Australian 7X notes (PR 145). */
public class RearAcRuleTest {

    private static final int LEVEL_1 = 0x10020101;
    private static final int AUTO = 0x1002010A;

    private long now = 1_000L;

    private RearAcRule.Act read(RearAcRule rule, Integer front, Integer fan, Integer rear, boolean clear) {
        now += 2_000L;
        return rule.step(new RearAcRule.Sample(front, fan, rear, clear, now));
    }

    private RearAcRule.Act running(RearAcRule rule, int rear) {
        return read(rule, 1, LEVEL_1, rear, true);
    }

    private void climateOff(RearAcRule rule) {
        read(rule, 0, 0, 0, true);
        read(rule, 0, 0, 0, true);
    }

    /** Rear was on, then the climate went off and on. Returns what the steady reading did. */
    private RearAcRule restoringRule() {
        RearAcRule rule = new RearAcRule(false);
        assertEquals(RearAcRule.Act.REMEMBER_ON, running(rule, 1));
        climateOff(rule);
        running(rule, 0);
        return rule;
    }

    @Test
    public void rearOnBeforeIsSentOnceAfterOffAndOn() {
        RearAcRule rule = restoringRule();
        assertEquals(RearAcRule.Act.RESTORE, running(rule, 0));
        assertEquals(RearAcRule.Act.NONE, running(rule, 0));
        assertEquals(RearAcRule.Act.RESTORED, running(rule, 1));
        assertEquals(RearAcRule.Act.NONE, running(rule, 1));
    }

    @Test
    public void neverSentTwiceWhenTheRearStaysOff() {
        RearAcRule rule = restoringRule();
        assertEquals(RearAcRule.Act.RESTORE, running(rule, 0));
        assertEquals(RearAcRule.Act.NONE, running(rule, 0));
        assertEquals(RearAcRule.Act.NONE, running(rule, 0));
        assertEquals(RearAcRule.Act.NOT_RESTORED, running(rule, 0));
        for (int i = 0; i < 5; i++) {
            assertEquals(RearAcRule.Act.NONE, running(rule, 0));
        }
    }

    @Test
    public void aFailedRestoreStillRestoresNextCycle() {
        RearAcRule rule = restoringRule();
        running(rule, 0);
        rule.commandRefused();
        running(rule, 0);
        climateOff(rule);
        running(rule, 0);
        assertEquals(RearAcRule.Act.RESTORE, running(rule, 0));
    }

    @Test
    public void rearOffBeforeIsLeftOff() {
        RearAcRule rule = new RearAcRule(false);
        running(rule, 0);
        climateOff(rule);
        running(rule, 0);
        for (int i = 0; i < 5; i++) {
            assertEquals(RearAcRule.Act.NONE, running(rule, 0));
        }
    }

    @Test
    public void thePersonTurningTheRearOffIsRemembered() {
        RearAcRule rule = new RearAcRule(false);
        running(rule, 1);
        assertEquals(RearAcRule.Act.NONE, running(rule, 0));
        assertEquals(RearAcRule.Act.REMEMBER_OFF, running(rule, 0));
        climateOff(rule);
        running(rule, 0);
        assertEquals(RearAcRule.Act.NONE, running(rule, 0));
    }

    @Test
    public void theRearDroppingAsTheClimateGoesOffIsNotThePerson() {
        RearAcRule rule = new RearAcRule(false);
        running(rule, 1);
        // Rear already reads off in the last reading with the front on.
        assertEquals(RearAcRule.Act.NONE, running(rule, 0));
        climateOff(rule);
        running(rule, 0);
        assertEquals(RearAcRule.Act.RESTORE, running(rule, 0));
    }

    @Test
    public void rememberedAcrossAppRestarts() {
        RearAcRule rule = new RearAcRule(true);
        running(rule, 0);
        climateOff(rule);
        running(rule, 0);
        assertEquals(RearAcRule.Act.RESTORE, running(rule, 0));
    }

    @Test
    public void rearAlreadyOnAfterTheCycleSendsNothing() {
        RearAcRule rule = new RearAcRule(true);
        running(rule, 1);
        climateOff(rule);
        running(rule, 1);
        for (int i = 0; i < 3; i++) {
            assertEquals(RearAcRule.Act.NONE, running(rule, 1));
        }
    }

    @Test
    public void waitsForClearThenGivesUpAndKeepsWanting() {
        RearAcRule rule = restoringRule();
        assertEquals(RearAcRule.Act.NONE, read(rule, 1, LEVEL_1, 0, false));
        assertEquals(RearAcRule.Act.RESTORE, running(rule, 0));

        RearAcRule late = restoringRule();
        assertEquals(RearAcRule.Act.NONE, read(late, 1, LEVEL_1, 0, false));
        now += RearAcRule.LIMIT_MS;
        assertEquals(RearAcRule.Act.GAVE_UP, read(late, 1, LEVEL_1, 0, true));
        assertEquals(RearAcRule.Act.NONE, running(late, 0));
        assertEquals(RearAcRule.Act.NONE, running(late, 0));
        climateOff(late);
        running(late, 0);
        assertEquals(RearAcRule.Act.RESTORE, running(late, 0));
    }

    @Test
    public void aFanThatKeepsChangingIsNotSteady() {
        RearAcRule rule = new RearAcRule(true);
        running(rule, 1);
        climateOff(rule);
        assertEquals(RearAcRule.Act.NONE, read(rule, 1, LEVEL_1, 0, true));
        assertEquals(RearAcRule.Act.NONE, read(rule, 1, AUTO, 0, true));
        assertEquals(RearAcRule.Act.RESTORE, read(rule, 1, AUTO, 0, true));
    }

    @Test
    public void readingsWithoutProofChangeNothing() {
        RearAcRule rule = new RearAcRule(true);
        running(rule, 1);
        // Missing data can read 0, placeholders are not a fan speed: neither is an off.
        for (int i = 0; i < 5; i++) {
            read(rule, 0, null, 0, true);
            read(rule, 0, 255, 0, true);
            read(rule, null, null, null, true);
        }
        assertEquals(RearAcRule.Act.NONE, read(rule, 1, 255, 0, true));
        assertEquals(RearAcRule.Act.NONE, running(rule, 1));
        assertEquals(RearAcRule.Act.NONE, running(rule, 1));
    }
}
