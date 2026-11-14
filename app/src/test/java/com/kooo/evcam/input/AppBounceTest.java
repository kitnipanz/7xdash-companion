package com.kooo.evcam.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AppBounceTest {

    @Test
    public void sixtyStepsHalfASecond_thirtyOpensThirtyMinimises() {
        assertEquals(60, AppBounce.STEPS);
        assertEquals(500L, AppBounce.STEP_MS);
        int opens = 0;
        for (int i = 0; i < AppBounce.STEPS; i++) {
            if (AppBounce.opensApp(i)) {
                opens++;
            }
        }
        assertEquals(30, opens);
        assertTrue("starts by opening the app", AppBounce.opensApp(0));
        assertFalse("ends on the home screen", AppBounce.opensApp(AppBounce.STEPS - 1));
    }

    @Test
    public void storedKey() {
        assertEquals(ShortcutAction.APP_BOUNCE, ShortcutAction.fromKey("app_bounce"));
    }
}
