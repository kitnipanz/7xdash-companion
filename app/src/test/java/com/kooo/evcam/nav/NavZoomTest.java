package com.kooo.evcam.nav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NavZoomTest {

    @Test
    public void slowIsCloseFastIsFar() {
        assertEquals(23.0f, NavMapPainter.zoomFor(0f, 1_500.0), 1e-4);
        assertEquals(23.0f, NavMapPainter.zoomFor(10f, 1_500.0), 1e-4);
        // slow: the closest it goes, all the way up to 25 km/h
        assertEquals(23.0f, NavMapPainter.zoomFor(20f, 1_500.0), 1e-4);
        assertEquals(23.0f, NavMapPainter.zoomFor(25f, 1_500.0), 1e-4);
        assertEquals(19.0f, NavMapPainter.zoomFor(130f, 5_000.0), 1e-4);
        float last = 99f;
        for (float kmh = 0; kmh <= 120; kmh += 10) {
            float z = NavMapPainter.zoomFor(kmh, 5_000.0);
            assertTrue("zoom goes out as the speed goes up at " + kmh, z <= last);
            last = z;
        }
    }

    @Test
    public void slowingDownBringsTheMapInClearly() {
        // most of the change is at the low end: 20 km/h is much closer than 60, 90 and 110 are close together
        float z20 = NavMapPainter.zoomFor(20f, 5_000.0);
        float z60 = NavMapPainter.zoomFor(60f, 5_000.0);
        float z90 = NavMapPainter.zoomFor(90f, 5_000.0);
        float z110 = NavMapPainter.zoomFor(110f, 5_000.0);
        assertTrue(z20 - z60 > 0.9f);
        assertTrue(z90 - z110 < 0.5f);
    }

    @Test
    public void closeInBeforeATurnEvenWhenFast() {
        assertEquals(20.0f, NavMapPainter.zoomFor(90f, 200.0), 1e-4);
        assertEquals(23.0f, NavMapPainter.zoomFor(5f, 200.0), 1e-4);
    }

    @Test
    public void withoutSpeedTheDistanceDecides() {
        assertEquals(20.0f, NavMapPainter.zoomFor(null, 200.0), 1e-4);
        assertEquals(16.5f, NavMapPainter.zoomFor(null, 1_000.0), 1e-4);
        assertEquals(15.5f, NavMapPainter.zoomFor(null, 9_000.0), 1e-4);
        assertEquals(16.5f, NavMapPainter.zoomFor(null, null), 1e-4);
    }

    @Test
    public void easingAndTurningTheShortWay() {
        assertEquals(0f, NavMapPainter.ease(0f, 1f), 1e-6);
        assertTrue(NavMapPainter.ease(1f, 1f) > 0.6f && NavMapPainter.ease(1f, 1f) < 0.65f);
        // 350 to 10 goes through north, not all the way round
        assertEquals(0f, NavMapPainter.turn(350f, 10f, 0.5f), 1e-3);
        assertEquals(355f, NavMapPainter.turn(10f, 340f, 0.5f), 1e-3);
    }

    @Test
    public void theCarPointsUpWhenTheMapFacesItsWay() {
        assertEquals(0f, NavMapPainter.screenTurn(90f, 90f), 1e-6);
        assertEquals(90f, NavMapPainter.screenTurn(90f, 0f), 1e-6);
        assertEquals(270f, NavMapPainter.screenTurn(0f, 90f), 1e-6);
    }

    @Test
    public void aimsFurtherAheadWhenZoomedOut() {
        double near = NavMapPainter.aheadMeters(13.75, 19f, 1000, NavMapPainter.CAR_AT);
        double far = NavMapPainter.aheadMeters(13.75, 16f, 1000, NavMapPainter.CAR_AT);
        assertTrue(near > 0);
        assertEquals(8.0, far / near, 1e-6);
    }

    @Test
    public void offsetGoesThatFarThatWay() {
        double[] p = Geo.offset(13.75, 100.5, 0, 1000);
        assertEquals(1000, Geo.meters(13.75, 100.5, p[0], p[1]), 1);
        assertEquals(0, Geo.bearing(13.75, 100.5, p[0], p[1]), 0.01);
        double[] e = Geo.offset(13.75, 100.5, 90, 500);
        assertEquals(90, Geo.bearing(13.75, 100.5, e[0], e[1]), 0.01);
    }

    @Test
    public void pastTheMapsClosestTheViewIsDrawnBigger() {
        assertEquals(1f, NavMapPainter.viewScale(20f, 20f), 1e-6);
        assertEquals(2f, NavMapPainter.viewScale(22f, 21f), 1e-5);
        assertEquals(NavMapPainter.CAR_AT, NavMapPainter.carAtInView(1f), 1e-6);
        // drawn twice as big around the middle: the car sits half as far below the middle in the view
        assertEquals(0.5f + (NavMapPainter.CAR_AT - 0.5f) / 2f, NavMapPainter.carAtInView(2f), 1e-6);
    }

    @Test
    public void aStandingCarIgnoresItsWanderingFixes() {
        assertTrue(Navigator.standing(0f, 20));
        assertTrue(Navigator.standing(2.9f, 20));
        assertFalse(Navigator.standing(3f, 20));
        // no speed from the car: can't tell, keep following the fixes
        assertFalse(Navigator.standing(null, 20));
        // a stuck speed signal must not freeze the car on the map
        assertFalse(Navigator.standing(0f, 60));
    }

    @Test
    public void floatingSettingsOpenBesideTheMap() {
        // room on the left
        assertEquals(1000 - 460 - 12, NavFloatSettings.place(1000, 700, 460, 2000, 12));
        // none on the left: on the right
        assertEquals(312, NavFloatSettings.place(100, 200, 460, 2000, 12));
        // none on either side: inside the screen
        assertEquals(2000 - 460 - 12, NavFloatSettings.place(300, 1700, 460, 2000, 12));
    }

    @Test
    public void hudCardTextShrinksOnlyWhenItWouldOverflow() {
        assertEquals(1f, HudTurnCard.fitScale(90, 100), 1e-6);
        assertEquals(1f, HudTurnCard.fitScale(100, 100), 1e-6);
        assertTrue(HudTurnCard.fitScale(120, 100) * 120 < 100);
        assertTrue(HudTurnCard.fitScale(120, 100) > 0.8f);
    }
}
