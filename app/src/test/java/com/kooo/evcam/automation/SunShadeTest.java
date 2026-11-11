package com.kooo.evcam.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SunShadeTest {

    @Test
    public void percentMapsOntoTheCarsOneToTwentySix() {
        assertEquals(1, SunShade.valueFor(0));
        assertEquals(26, SunShade.valueFor(100));
        assertEquals(7, SunShade.valueFor(25));
        assertEquals(14, SunShade.valueFor(50));
        assertEquals(20, SunShade.valueFor(75));
        assertEquals(1, SunShade.valueFor(-5));
        assertEquals(26, SunShade.valueFor(250));
    }

    @Test
    public void everyOfferedLevelIsAccepted() {
        for (int level : AutomationAction.SUNSHADE_LEVELS) {
            assertEquals(Integer.valueOf(level), SunShade.percent(Integer.toString(level)));
        }
        assertNull(SunShade.percent("101"));
        assertNull(SunShade.percent("-1"));
        assertNull(SunShade.percent("open"));
        assertNull(SunShade.percent(null));
    }

    @Test
    public void oneButtonOpensWhenClosedElseCloses() {
        assertEquals(SunShade.OPEN, SunShade.toggleTarget(1));
        assertEquals(SunShade.OPEN, SunShade.toggleTarget(null));
        assertEquals(SunShade.CLOSED, SunShade.toggleTarget(26));
        assertEquals(SunShade.CLOSED, SunShade.toggleTarget(10));
    }

    @Test
    public void storedKeyRoundTrips() {
        assertEquals(AutomationAction.SUNROOF_SHADE, AutomationAction.fromKey("sunroof_shade"));
    }
}
