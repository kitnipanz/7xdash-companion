package com.kooo.evcam.nav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class RouteTrafficTest {

    /** Google's example line, three points: used as the step's line and the whole route's line. */
    private static final String LINE = "_p~iF~ps|U_ulLnnqC_mqNvxq`@";

    private static String route(String description, String label, String speed) {
        return "{\"duration\":\"600s\",\"staticDuration\":\"500s\",\"distanceMeters\":2000,"
                + "\"description\":\"" + description + "\",\"routeLabels\":[\"" + label + "\"],"
                + "\"polyline\":{\"encodedPolyline\":\"" + LINE + "\"},"
                + "\"travelAdvisory\":{\"speedReadingIntervals\":["
                + "{\"startPolylinePointIndex\":0,\"endPolylinePointIndex\":1},"
                + "{\"startPolylinePointIndex\":1,\"endPolylinePointIndex\":2,\"speed\":\"" + speed + "\"}]},"
                + "\"legs\":[{\"steps\":[{\"distanceMeters\":2000,\"staticDuration\":\"500s\","
                + "\"polyline\":{\"encodedPolyline\":\"" + LINE + "\"},"
                + "\"navigationInstruction\":{\"maneuver\":\"DEPART\",\"instructions\":\"Go\"}}]}]}";
    }

    @Test
    public void trafficAlongTheRouteAndTheAlternatives() throws Exception {
        String body = "{\"routes\":[" + route("ถ. พญาไท", "DEFAULT_ROUTE", "TRAFFIC_JAM") + ","
                + route("ทางพิเศษศรีรัช", "DEFAULT_ROUTE_ALTERNATE", "SLOW") + "]}";
        List<NavRoute> all = GoogleJson.routes(body);
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
        assertEquals(best.meters, GoogleJson.route(body).meters, 1e-6);
    }

    @Test
    public void colours() {
        assertEquals(0xFFD93025, NavMapPainter.trafficColor(NavRoute.JAM));
        assertEquals(0xFFF29900, NavMapPainter.trafficColor(NavRoute.SLOW));
        assertEquals(0xFF4285F4, NavMapPainter.trafficColor(NavRoute.FREE));
    }

    @Test
    public void theTrimmedLineKeepsItsTraffic() throws Exception {
        NavRoute r = GoogleJson.routes("{\"routes\":[" + route("x", "DEFAULT_ROUTE", "TRAFFIC_JAM") + "]}").get(0);
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
    public void requestAsksForTrafficAndAlternatives() throws Exception {
        org.json.JSONObject j = new org.json.JSONObject(GoogleJson.routeRequest(1, 2, null, 3, 4, "th", true));
        assertTrue(j.getBoolean("computeAlternativeRoutes"));
        assertEquals("TRAFFIC_ON_POLYLINE", j.getJSONArray("extraComputations").getString(0));
        assertFalse(new org.json.JSONObject(GoogleJson.routeRequest(1, 2, null, 3, 4, "th", false))
                .getBoolean("computeAlternativeRoutes"));
    }
}
