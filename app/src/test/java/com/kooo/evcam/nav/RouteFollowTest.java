package com.kooo.evcam.nav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Following the car along the road between fixes, and weak GPS fixes. */
public class RouteFollowTest {

    /** 1 km north from (13.70, 100.50), then 1 km east. */
    private static final double LAT0 = 13.70;
    private static final double LNG0 = 100.50;
    private static final double DLAT_1KM = 1000 / 111_195.0;
    private static final double DLNG_1KM = DLAT_1KM / Math.cos(Math.toRadians(LAT0 + DLAT_1KM));

    private static NavRoute lRoute() {
        double cornerLat = LAT0 + DLAT_1KM;
        return new NavRoute.Builder()
                .step(Maneuver.DEPART, "Head north", 1000, 100,
                        new double[]{LAT0, LNG0, LAT0 + DLAT_1KM / 2, LNG0, cornerLat, LNG0})
                .step(Maneuver.TURN_RIGHT, "Turn right", 1000, 100,
                        new double[]{cornerLat, LNG0, cornerLat, LNG0 + DLNG_1KM})
                .duration(300, 200)
                .build();
    }

    @Test
    public void pointAlongTheRoadGoesRoundTheCorner() {
        NavRoute r = lRoute();
        double[] before = r.pointAt(900);
        assertEquals(LNG0, before[1], 1e-9);
        assertEquals(0, before[2], 0.5);
        // 100 m past the corner: on the eastbound road, heading east, not 100 m further north
        double[] after = r.pointAt(1100);
        assertEquals(LAT0 + DLAT_1KM, after[0], 1e-6);
        assertEquals(100, Geo.meters(LAT0 + DLAT_1KM, LNG0, after[0], after[1]), 1);
        assertEquals(90, after[2], 0.5);
    }

    @Test
    public void pointAtIsClampedToTheRoute() {
        NavRoute r = lRoute();
        double[] start = r.pointAt(-50);
        assertEquals(LAT0, start[0], 1e-9);
        double[] end = r.pointAt(5000);
        assertEquals(r.endLat(), end[0], 1e-9);
        assertEquals(r.endLng(), end[1], 1e-9);
    }

    @Test
    public void progressSaysHowFarAlong() {
        Guidance g = new Guidance(lRoute());
        Guidance.Progress p = g.update(LAT0 + DLAT_1KM * 0.3, LNG0, 5);
        assertEquals(300, p.along, 2);
        assertEquals(1700, p.remainingMeters, 2);
    }

    @Test
    public void weakFixIsNotOffTheRoute() {
        Guidance g = new Guidance(lRoute());
        // 70 m beside the road with a 90 m accuracy circle: could well be on it
        double lng = LNG0 + 70 / (111_195.0 * Math.cos(Math.toRadians(LAT0)));
        Guidance.Progress p = null;
        for (int i = 0; i < 5; i++) {
            p = g.update(LAT0 + DLAT_1KM * 0.3, lng, 90);
        }
        assertFalse(p.offRoute);
        // the same place with a sharp fix is off the road
        g = new Guidance(lRoute());
        for (int i = 0; i < 5; i++) {
            p = g.update(LAT0 + DLAT_1KM * 0.3, lng, 5);
        }
        assertTrue(p.offRoute);
    }

    @Test
    public void veryWeakFixDoesNotHideLeavingTheRoute() {
        Guidance g = new Guidance(lRoute());
        // 300 m away: off the route even though the GPS claims 1 km accuracy
        double lng = LNG0 + 300 / (111_195.0 * Math.cos(Math.toRadians(LAT0)));
        Guidance.Progress p = null;
        for (int i = 0; i < 5; i++) {
            p = g.update(LAT0 + DLAT_1KM * 0.3, lng, 1000);
        }
        assertTrue(p.offRoute);
    }
}
