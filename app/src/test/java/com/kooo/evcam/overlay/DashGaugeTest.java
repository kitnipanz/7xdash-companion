package com.kooo.evcam.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DashGaugeTest {

    @Test
    public void parse_defaultsForEmptyOrUnknown() {
        DashGauge[] none = DashGauge.parse("");
        assertEquals(DashGauge.THROTTLE, none[0]);
        assertEquals(DashGauge.G_FORCE, none[1]);
        assertEquals(DashGauge.BATTERY, none[2]);
        DashGauge[] mixed = DashGauge.parse("speed,nope");
        assertEquals(DashGauge.SPEED, mixed[0]);
        assertEquals(DashGauge.G_FORCE, mixed[1]);
        assertEquals(DashGauge.BATTERY, mixed[2]);
    }

    @Test
    public void writeThenParse_roundTrips() {
        DashGauge[] g = {DashGauge.BATTERY_TEMP, DashGauge.STEERING, DashGauge.RANGE};
        assertEquals("battery_temp,steering,range", DashGauge.write(g));
        DashGauge[] back = DashGauge.parse(DashGauge.write(g));
        for (int i = 0; i < 3; i++) {
            assertEquals(g[i], back[i]);
        }
    }

    @Test
    public void keysAreUnique() {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (DashGauge g : DashGauge.values()) {
            assertTrue(g.key, keys.add(g.key));
            assertTrue(g.max > g.min);
        }
    }

    @Test
    public void fraction_staysOnTheDial() {
        assertEquals(0f, DashGauge.SPEED.fraction(-5), 0f);
        assertEquals(0.5f, DashGauge.SPEED.fraction(100), 1e-6f);
        assertEquals(1f, DashGauge.SPEED.fraction(400), 0f);
        assertEquals(0.5f, DashGauge.G_FORCE.fraction(0), 1e-6f);
    }

    @Test
    public void red_upTopDownLowOrBothWays() {
        assertTrue(DashGauge.SPEED.inRed(170));
        assertFalse(DashGauge.SPEED.inRed(100));
        // battery is red when low
        assertTrue(DashGauge.BATTERY.inRed(15));
        assertFalse(DashGauge.BATTERY.inRed(60));
        // centre zero: hard braking and hard pulling both
        assertTrue(DashGauge.G_FORCE.inRed(-0.7f));
        assertTrue(DashGauge.G_FORCE.inRed(0.7f));
        assertFalse(DashGauge.G_FORCE.inRed(0.2f));
    }

    @Test
    public void format_decimalsPerGauge() {
        assertEquals("88", DashGauge.SPEED.format(87.6f));
        assertEquals("-0.25", DashGauge.G_FORCE.format(-0.25f));
    }

    @Test
    public void gForce_fromSpeedChange() {
        DashGauge.GForce g = new DashGauge.GForce();
        assertNull(g.update(0f, 0));
        // 0 → 36 km/h (10 m/s) in 1 s ≈ 1.02 g
        Float first = g.update(36f, 1000);
        assertNotNull(first);
        assertEquals(10f / 9.80665f, first, 1e-3f);
        // braking pulls it negative
        Float later = g.update(0f, 2000);
        assertTrue(later < first);
        // a long gap starts over
        assertNull(g.update(50f, 10_000));
        // a missing reading keeps the last
        assertNull(g.update(null, 10_100));
    }
}
