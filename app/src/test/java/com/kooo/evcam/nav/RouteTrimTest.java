package com.kooo.evcam.nav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.android.gms.maps.model.LatLng;

import org.junit.Test;

import java.util.List;

public class RouteTrimTest {

    private static final double LAT0 = 13.70;
    private static final double LNG0 = 100.50;
    private static final double DLAT_1KM = 1000 / 111_195.0;
    private static final double DLNG_1KM = DLAT_1KM / Math.cos(Math.toRadians(LAT0 + DLAT_1KM));

    /** 1 km north, then 1 km east. */
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
    public void theLineStartsAtTheCarAndTheDrivenPartIsGone() {
        NavRoute r = lRoute();
        Guidance g = new Guidance(r);
        // a quarter of the way up the first road
        Guidance.Progress p = g.update(LAT0 + DLAT_1KM / 4, LNG0, 5);
        List<LatLng> left = NavMapPainter.ahead(r, p.segment, p.lat, p.lng);
        assertEquals(p.lat, left.get(0).latitude, 1e-9);
        assertEquals(p.lng, left.get(0).longitude, 1e-9);
        LatLng end = left.get(left.size() - 1);
        assertEquals(r.endLat(), end.latitude, 1e-9);
        assertEquals(r.endLng(), end.longitude, 1e-9);
        // nothing behind the car: every point is at or north of it, or on the east road
        for (LatLng pt : left) {
            assertTrue(pt.latitude >= p.lat - 1e-9);
        }

        // past the corner: the whole first road is gone
        Guidance.Progress later = g.update(LAT0 + DLAT_1KM, LNG0 + DLNG_1KM / 2, 5);
        List<LatLng> rest = NavMapPainter.ahead(r, later.segment, later.lat, later.lng);
        assertTrue(rest.size() < left.size());
        for (LatLng pt : rest) {
            assertTrue(pt.longitude >= later.lng - 1e-9);
        }
    }

    @Test
    public void wholeRouteWhenNotStarted() {
        NavRoute r = lRoute();
        List<LatLng> all = NavMapPainter.ahead(r, -1, r.lat(0), r.lng(0));
        // the first point twice (where the car is, then the line's own start), then every point
        assertEquals(r.pointCount() + 1, all.size());
    }
}
