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
    public void voiceSaysEachStageOnceInOrder() {
        VoicePlan v = new VoicePlan();
        // 60 km/h: far 1000 m, near 250 m, now 83 m
        assertNull(v.due(1, 1500, 3000, 60));
        VoicePlan.Prompt far = v.due(1, 990, 3000, 60);
        assertNotNull(far);
        assertEquals(VoicePlan.Stage.FAR, far.stage);
        assertEquals(1000, far.meters);
        assertNull(v.due(1, 900, 3000, 60));
        VoicePlan.Prompt near = v.due(1, 240, 3000, 60);
        assertEquals(VoicePlan.Stage.NEAR, near.stage);
        assertEquals(250, near.meters);
        assertNull(v.due(1, 200, 3000, 60));
        assertEquals(VoicePlan.Stage.NOW, v.due(1, 60, 3000, 60).stage);
        assertNull(v.due(1, 20, 3000, 60));
        // the next maneuver starts over
        assertNotNull(v.due(2, 80, 3000, 60));
    }

    @Test
    public void noFarPromptWhenTurnsAreClose() {
        VoicePlan v = new VoicePlan();
        assertNull(v.due(1, 400, 400, 30));
        assertEquals(VoicePlan.Stage.NEAR, v.due(1, 140, 400, 30).stage);
    }

    @Test
    public void jumpingAheadSkipsEarlierStages() {
        VoicePlan v = new VoicePlan();
        assertEquals(VoicePlan.Stage.NOW, v.due(1, 25, 3000, 50).stage);
        assertNull(v.due(1, 20, 3000, 50));
    }

    @Test
    public void highwayAnnouncesEarlier() {
        VoicePlan v = new VoicePlan();
        assertEquals(VoicePlan.Stage.FAR, v.due(1, 1900, 10000, 110).stage);
    }

    @Test
    public void roundsLikePeopleSayIt() {
        assertEquals(300, VoicePlan.spokenMeters(312));
        assertEquals(50, VoicePlan.spokenMeters(48));
        assertEquals(1500, VoicePlan.spokenMeters(1420));
        assertEquals(2000, VoicePlan.spokenMeters(1980));
    }

    @Test
    public void readsARoutesAnswer() throws Exception {
        String body = "{\"routes\":[{\"distanceMeters\":2000,\"duration\":\"300s\",\"staticDuration\":\"200s\","
                + "\"legs\":[{\"steps\":["
                + "{\"distanceMeters\":1000,\"staticDuration\":\"100s\",\"polyline\":{\"encodedPolyline\":\"_p~iF~ps|U_ulLnnqC\"},"
                + "\"navigationInstruction\":{\"instructions\":\"Head north on Soi 1\"}},"
                + "{\"distanceMeters\":1000,\"staticDuration\":\"100s\",\"polyline\":{\"encodedPolyline\":\"_mqNvxq`@_mqNvxq`@\"},"
                + "\"navigationInstruction\":{\"maneuver\":\"TURN_LEFT\",\"instructions\":\"Turn left onto Rama IV\"}}"
                + "]}]}]}";
        NavRoute r = GoogleJson.route(body);
        assertNotNull(r);
        assertEquals(2, r.steps.size());
        assertEquals(Maneuver.DEPART, r.steps.get(0).maneuver);
        assertEquals(Maneuver.TURN_LEFT, r.steps.get(1).maneuver);
        assertEquals("Turn left onto Rama IV", r.steps.get(1).instruction);
        assertEquals(300, r.seconds, 1e-9);
        assertEquals(200, r.staticSeconds, 1e-9);
        assertEquals(4, r.pointCount());
    }

    @Test
    public void noRouteIsNull() throws Exception {
        assertNull(GoogleJson.route("{}"));
        assertEquals("API key not valid.", GoogleJson.error("{\"error\":{\"code\":400,\"message\":\"API key not valid.\"}}"));
        assertNull(GoogleJson.error("not json"));
    }

    @Test
    public void readsPlaces() throws Exception {
        String body = "{\"places\":[{\"displayName\":{\"text\":\"Siam Paragon\"},\"formattedAddress\":\"991 Rama I Rd\","
                + "\"location\":{\"latitude\":13.746,\"longitude\":100.535}},{\"displayName\":{\"text\":\"No location\"}}]}";
        List<GoogleJson.Found> found = GoogleJson.places(body);
        assertEquals(1, found.size());
        assertEquals("Siam Paragon", found.get(0).place.name);
        assertEquals("991 Rama I Rd", found.get(0).address);
        assertEquals(13.746, found.get(0).place.lat, 1e-9);
    }

    @Test
    public void unknownManeuverIsStraight() {
        assertEquals(Maneuver.STRAIGHT, Maneuver.of("SOMETHING_NEW"));
        assertEquals(Maneuver.FERRY, Maneuver.of("FERRY_TRAIN"));
        assertTrue(Maneuver.UTURN_LEFT.left());
        assertFalse(Maneuver.RAMP_RIGHT.left());
    }

    @Test
    public void routeRequestHasTraffic() throws Exception {
        org.json.JSONObject j = new org.json.JSONObject(GoogleJson.routeRequest(1, 2, 370.4, 3, 4, "th", true));
        assertEquals("TRAFFIC_AWARE", j.getString("routingPreference"));
        assertEquals(10, j.getJSONObject("origin").getJSONObject("location").getInt("heading"));
        assertEquals(3, j.getJSONObject("destination").getJSONObject("location")
                .getJSONObject("latLng").getDouble("latitude"), 1e-9);
    }
}
