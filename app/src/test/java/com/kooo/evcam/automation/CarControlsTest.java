package com.kooo.evcam.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class CarControlsTest {

    @Test
    public void everyOfferedLevelIsAccepted() {
        for (int level : AutomationAction.TAILGATE_LEVELS) {
            assertEquals(Integer.valueOf(level), CarControls.tailgatePercent(Integer.toString(level)));
        }
    }

    @Test
    public void outOfRangeOrBadChoiceIsRefused() {
        assertNull(CarControls.tailgatePercent("49"));
        assertNull(CarControls.tailgatePercent("101"));
        assertNull(CarControls.tailgatePercent("open"));
        assertNull(CarControls.tailgatePercent(null));
    }

    @Test
    public void storedKeyRoundTrips() {
        assertEquals(AutomationAction.TAILGATE_HEIGHT, AutomationAction.fromKey("tailgate_height"));
    }

    @Test
    public void sentryOnLeavesOnAndArmedAlone() {
        org.junit.Assert.assertFalse(CarControls.sentryNeedsSend(CarControls.SENTRY_ON, true));
        org.junit.Assert.assertFalse(CarControls.sentryNeedsSend(CarControls.SENTRY_ARMED, true));
        org.junit.Assert.assertTrue(CarControls.sentryNeedsSend(CarControls.SENTRY_OFF, true));
        org.junit.Assert.assertTrue(CarControls.sentryNeedsSend(null, true));
    }

    @Test
    public void sentryOffSendsUnlessAlreadyOff() {
        org.junit.Assert.assertFalse(CarControls.sentryNeedsSend(CarControls.SENTRY_OFF, false));
        org.junit.Assert.assertTrue(CarControls.sentryNeedsSend(CarControls.SENTRY_ARMED, false));
        org.junit.Assert.assertTrue(CarControls.sentryNeedsSend(null, false));
    }

    @Test
    public void sentryTurnsOnOnlyInParkOrWithNoGear() {
        org.junit.Assert.assertTrue(CarControls.sentryGearAllows("P"));
        org.junit.Assert.assertTrue(CarControls.sentryGearAllows(null));
        org.junit.Assert.assertFalse(CarControls.sentryGearAllows("D"));
        org.junit.Assert.assertFalse(CarControls.sentryGearAllows("R"));
        org.junit.Assert.assertEquals(AutomationAction.SENTRY, AutomationAction.fromKey("sentry"));
    }

    @Test
    public void lockFeedbackTakesOnlyTheCarsValues() {
        for (int value : AutomationAction.LOCK_FEEDBACK_VALUES) {
            assertEquals(Integer.valueOf(value), CarControls.lockFeedbackValue(Integer.toString(value)));
        }
        assertNull(CarControls.lockFeedbackValue("1"));
        assertNull(CarControls.lockFeedbackValue("x"));
        assertNull(CarControls.lockFeedbackValue(null));
    }
}
