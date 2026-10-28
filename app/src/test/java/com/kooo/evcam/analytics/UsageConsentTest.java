package com.kooo.evcam.analytics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UsageConsentTest {

    private static final int NOW = UsageConsent.QUESTION;

    @Test
    public void asksOnceARunUntilRemembered() {
        assertTrue(UsageConsent.shouldAsk(true, false, false, false, 0));
        assertFalse(UsageConsent.shouldAsk(true, false, true, false, 0));
        assertFalse(UsageConsent.shouldAsk(true, true, false, true, 0));
    }

    @Test
    public void aRememberedNoIsAskedOnceMore() {
        // said no to the old question: ask the new one
        assertTrue(UsageConsent.shouldAsk(true, true, false, false, 0));
        // seen the new one (any answer, or closed): not again
        assertFalse(UsageConsent.shouldAsk(true, true, false, false, NOW));
        // a yes is never asked again
        assertFalse(UsageConsent.shouldAsk(true, true, false, true, 0));
    }

    @Test
    public void buildsWithoutKeysNeverAsk() {
        assertFalse(UsageConsent.shouldAsk(false, false, false, false, 0));
        assertFalse(UsageConsent.shouldAsk(false, true, false, false, 0));
    }

    @Test
    public void onlyARememberedYesSendsAtStart() {
        assertTrue(UsageConsent.sendsAtStart(true, true));
        assertFalse(UsageConsent.sendsAtStart(true, false));
        assertFalse(UsageConsent.sendsAtStart(false, true));
        assertFalse(UsageConsent.sendsAtStart(false, false));
    }
}
