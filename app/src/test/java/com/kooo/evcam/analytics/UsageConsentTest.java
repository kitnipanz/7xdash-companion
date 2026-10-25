package com.kooo.evcam.analytics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UsageConsentTest {

    @Test
    public void asksOnceARunUntilRemembered() {
        assertTrue(UsageConsent.shouldAsk(true, false, false));
        assertFalse(UsageConsent.shouldAsk(true, false, true));
        assertFalse(UsageConsent.shouldAsk(true, true, false));
    }

    @Test
    public void buildsWithoutKeysNeverAsk() {
        assertFalse(UsageConsent.shouldAsk(false, false, false));
    }

    @Test
    public void onlyARememberedYesSendsAtStart() {
        assertTrue(UsageConsent.sendsAtStart(true, true));
        // yes without "remember": wait for this run's answer
        assertFalse(UsageConsent.sendsAtStart(true, false));
        assertFalse(UsageConsent.sendsAtStart(false, true));
        assertFalse(UsageConsent.sendsAtStart(false, false));
    }
}
