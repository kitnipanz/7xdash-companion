package com.kooo.evcam.nav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class RouteTrafficTest {

    private static final String POINTS = "{\"latitude\":13.70,\"longitude\":100.50},"
            + "{\"latitude\":13.71,\"longitude\":100.50},{\"latitude\":13.72,\"longitude\":100.50}";

    private static String route(String street, int delay, String category) {
        return "{\"summary\":{\"travelTimeInSeconds\":600,\"noTrafficTravelTimeInSeconds\":500},"
                + "\"legs\":[{\"points\":[" + POINTS + "]}],"
                + "\"sections\":[{\"sectionType\":\"TRAVEL_MODE\",\"startPointIndex\":0,\"endPointIndex\":2},"
                + "{\"sectionType\":\"TRAFFIC\",\"startPointIndex\":1,\"endPointIndex\":2,"
                + "\"simpleCategory\":\"" + category + "\",\"magnitudeOfDelay\":" + delay + "}],"
                + "\"guidance\":{\"instructions\":["
                + "{\"pointIndex\":0,\"routeOffsetInMeters\":0,\"travelTimeInSeconds\":0,\"maneuver\":\"DEPART\","
                + "\"message\":\"Go\",\"street\":\"" + street + "\"},"
                + "{\"pointIndex\":2,\"routeOffsetInMeters\":2200,\"travelTimeInSeconds\":600,\"maneuver\":\"ARRIVE\"}]}}";
    }

    @Test
    public void trafficAlongTheRouteAndTheAlternatives() throws Exception {
        String body = "{\"routes\":[" + route("ถ. พญาไท", 3, "JAM") + "," + route("ทางพิเศษศรีรัช", 1, "JAM") + "]}";
        List<NavRoute> all = TomTomJson.routes(body);
        assertEquals(2, all.size());
        NavRoute best = all.get(0);
        assertTrue(best.best);
        assertEquals("ถ. พญาไท", best.label);
        assertEquals(2, best.traffic.length);
        assertEquals(NavRoute.FREE, best.traffic[0]);
        assertEquals(NavRoute.JAM, best.traffic[1]);
        NavRoute other = all.get(1);
        assertFalse(other.best);
        assertEquals(NavRoute.SLOW, other.traffic[1]);
        // the first route alone, as before
        assertEquals(best.meters, TomTomJson.route(body).meters, 1e-6);
    }

    @Test
    public void aClosureCountsAsAJam() throws Exception {
        NavRoute r = TomTomJson.routes("{\"routes\":[" + route("x", 0, "ROAD_CLOSURE") + "]}").get(0);
        assertEquals(NavRoute.JAM, r.traffic[1]);
    }

    @Test
    public void colours() {
        assertEquals("#D93025", NavMapPainter.trafficColor(NavRoute.JAM));
        assertEquals("#F29900", NavMapPainter.trafficColor(NavRoute.SLOW));
        assertEquals("#4285F4", NavMapPainter.trafficColor(NavRoute.FREE));
    }

    @Test
    public void theTrimmedLineKeepsItsTraffic() throws Exception {
        NavRoute r = TomTomJson.routes("{\"routes\":[" + route("x", 3, "JAM") + "]}").get(0);
        // on the first piece: the car's piece is free, the next one jammed
        byte[] t = NavMapPainter.aheadTraffic(r, 0);
        assertEquals(r.pointCount() - 1, t.length);
        assertEquals(NavRoute.FREE, t[0]);
        assertEquals(NavRoute.JAM, t[1]);
        // on the second piece: only the jam is left
        byte[] later = NavMapPainter.aheadTraffic(r, 1);
        assertEquals(NavRoute.JAM, later[0]);
    }

    @Test
    public void requestAsksForTrafficAndAlternatives() {
        String url = TomTomJson.routeUrl(1, 2, null, 3, 4, "th-TH", true);
        assertTrue(url, url.contains("/calculateRoute/1.000000,2.000000:3.000000,4.000000/json"));
        assertTrue(url, url.contains("sectionType=traffic"));
        assertTrue(url, url.contains("maxAlternatives=2"));
        assertTrue(url, url.contains("language=th-TH"));
        assertFalse(url, url.contains("key="));
        assertTrue(TomTomJson.routeUrl(1, 2, null, 3, 4, "th-TH", false).contains("maxAlternatives=0"));
    }
}
