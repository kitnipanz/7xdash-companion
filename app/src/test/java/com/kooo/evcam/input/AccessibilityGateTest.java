package com.kooo.evcam.input;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AccessibilityGateTest {
    private static final String SELF =
            "io.github.dts88.zeekrshortcut/com.kooo.evcam.KeepAliveAccessibilityService";

    @Test
    public void findsTheServiceAmongOthers() {
        assertTrue(AccessibilityGate.contains("a.b/.C:" + SELF, SELF));
        assertTrue(AccessibilityGate.contains(SELF.toUpperCase(), SELF));
    }

    @Test
    public void emptyOrOtherServicesAreNotEnabled() {
        assertFalse(AccessibilityGate.contains(null, SELF));
        assertFalse(AccessibilityGate.contains("", SELF));
        assertFalse(AccessibilityGate.contains("a.b/.C", SELF));
    }
}
