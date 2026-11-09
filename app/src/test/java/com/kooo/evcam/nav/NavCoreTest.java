package com.kooo.evcam.nav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class NavCoreTest {

    /** 1 km north from (13.70, 100.50), then 1 km east: two steps with a right turn between. */
    private static final double LAT0 = 13.70;
    private static final double LNG0 = 100.50;
    private static final double DLAT_1KM = 1000 / 111_195.0;
    private static final double DLNG_1KM = DLAT_1KM / Math.cos(Math.toRadians(LAT0 + DLAT_1KM));

    private static NavRoute lRoute() {
        double cornerLat = LAT0 + DLAT_1KM;
        return new NavRoute.Builder()
                .step(Maneuver.DEPART, "Head north", 1000, 100,
                        new double[]{LAT0, LNG0, LAT0 + DLAT_1KM / 2, LNG0, cornerLat, LNG0})
                .step(Maneuver.TURN_RIGHT, "Turn right onto Rama IV", 1000, 100,
                        new double[]{cornerLat, LNG0, cornerLat, LNG0 + DLNG_1KM})
                .duration(300, 200)
                .build();
    }

    @Test
    public void decodesGooglesExample() {
        double[] p = Geo.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@");
        assertEquals(6, p.length);
        assertEquals(38.5, p[0], 1e-9);
        assertEquals(-120.2, p[1], 1e-9);
        assertEquals(40.7, p[2], 1e-9);
        assertEquals(-120.95, p[3], 1e-9);
        assertEquals(43.252, p[4], 1e-9);
        assertEquals(-126.453, p[5], 1e-9);
    }

    @Test
    public void brokenPolylineStopsInsteadOfThrowing() {
        assertEquals(2, Geo.decode("_p~iF~ps|U_ulL").length);
        assertEquals(0, Geo.decode(null).length);
    }

    @Test
    public void bearingsAndDistances() {
        assertEquals(0, Geo.bearing(LAT0, LNG0, LAT0 + 0.01, LNG0), 0.01);
        assertEquals(90, Geo.bearing(LAT0, LNG0, LAT0, LNG0 + 0.01), 0.1);
        assertEquals(1000, Geo.meters(LAT0, LNG0, LAT0 + DLAT_1KM, LNG0), 1);
    }

    @Test
    public void stepsShareTheirJoint() {
        NavRoute r = lRoute();
        assertEquals(4, r.pointCount());
        assertEquals(2, r.steps.size());
        assertEquals(0, r.steps.get(0).startPoint);
        assertEquals(2, r.steps.get(1).startPoint);
        assertEquals(1000, r.steps.get(1).startAlong, 2);
        assertEquals(2000, r.meters, 4);
        assertEquals(1.5, r.trafficFactor(), 1e-9);
    }

    @Test
    public void countsDownToTheTurn() {
        Guidance g = new Guidance(lRoute());
        // 300 m up the first road, 10 m to the side of it
        Guidance.Progress p = g.update(LAT0 + DLAT_1KM * 0.3, LNG0 + DLNG_1KM * 0.01, 10);
        assertFalse(p.offRoute);
        assertFalse(p.arrived);
        assertEquals(0, p.step);
        assertEquals(1, p.next);
        assertEquals(700, p.toNext, 3);
        assertEquals(1700, p.remainingMeters, 4);
        assertEquals(0, p.bearing, 1);
        // 70 s of the first step and all 100 s of the second, times traffic 1.5
        assertEquals(255, p.remainingSeconds, 1);
        assertEquals(10, p.offMeters, 1);
    }

    @Test
    public void afterTheTurnTheDestinationIsNext() {
        Guidance g = new Guidance(lRoute());
        g.update(LAT0 + DLAT_1KM * 0.9, LNG0, 5);
        Guidance.Progress p = g.update(LAT0 + DLAT_1KM, LNG0 + DLNG_1KM * 0.4, 5);
        assertEquals(1, p.step);
        assertEquals(-1, p.next);
        assertEquals(600, p.toNext, 4);
        assertEquals(90, p.bearing, 1);
    }

    @Test
    public void arrivesAtTheEnd() {
        Guidance g = new Guidance(lRoute());
        Guidance.Progress p = g.update(LAT0 + DLAT_1KM, LNG0 + DLNG_1KM * 0.995, 5);
        assertTrue(p.arrived);
    }

    @Test
    public void offRouteOnlyAfterSeveralFixes() {
        Guidance g = new Guidance(lRoute());
        double lat = LAT0 + DLAT_1KM * 0.5;
        double lng = LNG0 - DLNG_1KM * 0.2; // 200 m west of the road
        assertFalse(g.update(lat, lng, 10).offRoute);
        assertFalse(g.update(lat, lng, 10).offRoute);
        assertTrue(g.update(lat, lng, 10).offRoute);
        // back on the road: fine again at once
        assertFalse(g.update(lat, LNG0, 10).offRoute);
    }

    @Test
    public void poorGpsWidensTheLine() {
        Guidance g = new Guidance(lRoute());
        double lat = LAT0 + DLAT_1KM * 0.5;
        double lng = LNG0 - DLNG_1KM * 0.08; // 80 m off, with 100 m accuracy
        for (int i = 0; i < 5; i++) {
            assertFalse(g.update(lat, lng, 100).offRoute);
        }
    }

    @Test
    public void readsARoutesAnswer() throws Exception {
        String body = "{\"routes\":[{\"summary\":{\"travelTimeInSeconds\":300,\"trafficDelayInSeconds\":100},"
                + "\"legs\":[{\"points\":[{\"latitude\":13.70,\"longitude\":100.50},{\"latitude\":13.71,\"longitude\":100.50},"
                + "{\"latitude\":13.72,\"longitude\":100.50},{\"latitude\":13.72,\"longitude\":100.51}]}],"
                + "\"guidance\":{\"instructions\":["
                + "{\"maneuverPoint\":{\"latitude\":13.70,\"longitude\":100.50},\"routeOffsetInMeters\":0,\"maneuver\":\"DEPART\",\"instructionMessage\":\"Head north on Soi 1\","
                + "\"routePath\":[{\"travelTimeInSeconds\":0}]},"
                + "{\"maneuverPoint\":{\"latitude\":13.72,\"longitude\":100.50},\"routeOffsetInMeters\":2200,\"maneuver\":\"TURN_LEFT\",\"instructionMessage\":\"Turn left onto Rama IV\","
                + "\"routePath\":[{\"travelTimeInSeconds\":200}]},"
                + "{\"maneuverPoint\":{\"latitude\":13.72,\"longitude\":100.51},\"routeOffsetInMeters\":3200,\"maneuver\":\"ARRIVE_RIGHT\",\"instructionMessage\":\"You have arrived\","
                + "\"routePath\":[{\"travelTimeInSeconds\":300}]}"
                + "]}}]}";
        NavRoute r = TomTomJson.route(body);
        assertNotNull(r);
        assertEquals(2, r.steps.size());
        assertEquals(Maneuver.DEPART, r.steps.get(0).maneuver);
        assertEquals(Maneuver.TURN_LEFT, r.steps.get(1).maneuver);
        assertEquals("Turn left onto Rama IV", r.steps.get(1).instruction);
        assertEquals(2, r.steps.get(1).startPoint);
        assertEquals(2200, r.steps.get(0).meters, 1e-9);
        // steps count without traffic, so the traffic factor doesn't go on twice
        assertEquals(200 * 200.0 / 300, r.steps.get(0).seconds, 1e-9);
        assertEquals(300, r.seconds, 1e-9);
        assertEquals(200, r.staticSeconds, 1e-9);
        assertEquals(4, r.pointCount());
    }

    @Test
    public void noRouteIsNull() throws Exception {
        assertNull(TomTomJson.route("{}"));
        assertTrue(TomTomJson.noRoute("{\"detailedError\":{\"code\":\"NO_ROUTE_FOUND\",\"message\":\"No route\"}}"));
        assertEquals("No route", TomTomJson.error("{\"detailedError\":{\"code\":\"NO_ROUTE_FOUND\",\"message\":\"No route\"}}"));
        assertEquals("Invalid key", TomTomJson.error("{\"error\":{\"description\":\"Invalid key\"}}"));
        assertNull(TomTomJson.error("not json"));
    }

    @Test
    public void readsPlaces() throws Exception {
        String body = "{\"results\":[{\"poi\":{\"name\":\"Siam Paragon\"},\"address\":{\"freeformAddress\":\"991 Rama I Rd\"},"
                + "\"position\":{\"lat\":13.746,\"lon\":100.535}},{\"poi\":{\"name\":\"No location\"}},"
                + "{\"address\":{\"freeformAddress\":\"Rama IV Rd\"},\"position\":{\"lat\":13.7,\"lon\":100.5}}]}";
        List<TomTomJson.Found> found = TomTomJson.places(body);
        assertEquals(2, found.size());
        assertEquals("Siam Paragon", found.get(0).place.name);
        assertEquals("991 Rama I Rd", found.get(0).address);
        assertEquals(13.746, found.get(0).place.lat, 1e-9);
        assertEquals("Rama IV Rd", found.get(1).place.name);
    }

    @Test
    public void searchUrlIsEscapedAndInThailand() {
        String url = TomTomJson.searchUrl("สยาม พารากอน", 13.7, 100.5, "th-TH");
        assertTrue(url, url.contains("/search/2/search/%E0%B8%AA"));
        assertFalse(url, url.contains(" "));
        assertTrue(url, url.contains("countrySet=TH"));
        assertTrue(url, url.contains("lat=13.700000&lon=100.500000"));
    }

    @Test
    public void unknownManeuverIsStraight() {
        assertEquals(Maneuver.STRAIGHT, Maneuver.of("SOMETHING_NEW"));
        assertEquals(Maneuver.FERRY, Maneuver.of("FERRY_TRAIN"));
        assertTrue(Maneuver.UTURN_LEFT.left());
        assertFalse(Maneuver.RAMP_RIGHT.left());
    }

    @Test
    public void tomTomManeuvers() {
        assertEquals(Maneuver.TURN_SHARP_LEFT, Maneuver.ofTomTom("SHARP_LEFT"));
        assertEquals(Maneuver.TURN_SLIGHT_RIGHT, Maneuver.ofTomTom("KEEP_RIGHT"));
        assertEquals(Maneuver.UTURN_RIGHT, Maneuver.ofTomTom("MAKE_UTURN"));
        assertEquals(Maneuver.RAMP_LEFT, Maneuver.ofTomTom("EXIT_MOTORWAY_LEFT"));
        assertEquals(Maneuver.MERGE, Maneuver.ofTomTom("MERGE_LEFT_LANE"));
        assertEquals(Maneuver.TURN_SLIGHT_RIGHT, Maneuver.ofTomTom("SLIGHT_RIGHT"));
        assertEquals(Maneuver.ARRIVE, Maneuver.ofTomTom("ARRIVE_RIGHT"));
        assertEquals(Maneuver.STRAIGHT, Maneuver.ofTomTom("SOMETHING_NEW"));
        assertEquals(Maneuver.STRAIGHT, Maneuver.ofTomTom(null));
    }

    @Test
    public void routeHeadingIsWrapped() {
        assertTrue(TomTomJson.routeUrl(1, 2, 370.4, 3, 4, "th-TH", false).contains("vehicleHeading=10"));
    }
}
