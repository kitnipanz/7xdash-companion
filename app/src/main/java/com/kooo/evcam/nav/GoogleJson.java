package com.kooo.evcam.nav;

import com.kooo.evcam.trip.DestinationLink;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads what Google's Routes API (computeRoutes) and Places API (searchText) answer, and writes
 * what we ask them. Pure Java plus org.json, see the test.
 */
public final class GoogleJson {

    /** Only the fields guidance uses: Google bills by the fields asked for. */
    public static final String ROUTE_FIELDS = "routes.duration,routes.staticDuration,routes.distanceMeters,"
            + "routes.legs.steps.distanceMeters,routes.legs.steps.staticDuration,"
            + "routes.legs.steps.polyline.encodedPolyline,routes.legs.steps.navigationInstruction";
    public static final String PLACE_FIELDS = "places.displayName,places.formattedAddress,places.location";

    private GoogleJson() {
    }

    /** A place found by search: where it is, what it is called and its address. */
    public static final class Found {
        public final DestinationLink.Place place;
        public final String address;

        public Found(DestinationLink.Place place, String address) {
            this.place = place;
            this.address = address == null ? "" : address;
        }
    }

    /** The computeRoutes request body: drive from here to there, with traffic. */
    public static String routeRequest(double fromLat, double fromLng, Double heading,
                                      double toLat, double toLng, String language) {
        try {
            JSONObject origin = waypoint(fromLat, fromLng);
            if (heading != null) {
                origin.getJSONObject("location").put("heading", (int) Math.round(heading) % 360);
            }
            return new JSONObject()
                    .put("origin", origin)
                    .put("destination", waypoint(toLat, toLng))
                    .put("travelMode", "DRIVE")
                    .put("routingPreference", "TRAFFIC_AWARE")
                    .put("languageCode", language)
                    .put("units", "METRIC")
                    .toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JSONObject waypoint(double lat, double lng) throws JSONException {
        return new JSONObject().put("location", new JSONObject()
                .put("latLng", new JSONObject().put("latitude", lat).put("longitude", lng)));
    }

    /** The searchText request body, leaning towards results within 50 km of (lat, lng) when known. */
    public static String searchRequest(String query, Double lat, Double lng, String language) {
        try {
            JSONObject body = new JSONObject().put("textQuery", query).put("languageCode", language);
            if (lat != null && lng != null) {
                body.put("locationBias", new JSONObject().put("circle", new JSONObject()
                        .put("center", new JSONObject().put("latitude", lat).put("longitude", lng))
                        .put("radius", 50_000.0)));
            }
            return body.toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The first route in a computeRoutes answer, or null when there is none. */
    public static NavRoute route(String body) throws JSONException {
        JSONObject json = new JSONObject(body);
        JSONArray routes = json.optJSONArray("routes");
        if (routes == null || routes.length() == 0) {
            return null;
        }
        JSONObject r = routes.getJSONObject(0);
        NavRoute.Builder b = new NavRoute.Builder()
                .duration(seconds(r.optString("duration")), seconds(r.optString("staticDuration")));
        JSONArray legs = r.optJSONArray("legs");
        for (int l = 0; legs != null && l < legs.length(); l++) {
            JSONArray steps = legs.getJSONObject(l).optJSONArray("steps");
            for (int s = 0; steps != null && s < steps.length(); s++) {
                JSONObject step = steps.getJSONObject(s);
                JSONObject nav = step.optJSONObject("navigationInstruction");
                JSONObject line = step.optJSONObject("polyline");
                Maneuver m = Maneuver.of(nav == null ? null : nav.optString("maneuver", null));
                if (l == 0 && s == 0 && m == Maneuver.STRAIGHT) {
                    m = Maneuver.DEPART;
                }
                b.step(m, nav == null ? "" : nav.optString("instructions", ""),
                        step.optDouble("distanceMeters", 0),
                        seconds(step.optString("staticDuration")),
                        Geo.decode(line == null ? null : line.optString("encodedPolyline", null)));
            }
        }
        return b.build();
    }

    /** The places in a searchText answer, in Google's order. */
    public static List<Found> places(String body) throws JSONException {
        List<Found> out = new ArrayList<>();
        JSONArray places = new JSONObject(body).optJSONArray("places");
        for (int i = 0; places != null && i < places.length(); i++) {
            JSONObject p = places.getJSONObject(i);
            JSONObject at = p.optJSONObject("location");
            if (at == null || !at.has("latitude") || !at.has("longitude")) {
                continue;
            }
            JSONObject name = p.optJSONObject("displayName");
            out.add(new Found(new DestinationLink.Place(at.getDouble("latitude"), at.getDouble("longitude"),
                    name == null ? "" : name.optString("text", "")), p.optString("formattedAddress", "")));
        }
        return out;
    }

    /** The message in a Google error answer ({"error": {"message": ...}}), or null. */
    public static String error(String body) {
        try {
            JSONObject e = new JSONObject(body).optJSONObject("error");
            return e == null ? null : e.optString("message", null);
        } catch (Exception e) {
            return null;
        }
    }

    /** "1234s" (protobuf Duration as JSON) to seconds; 0 when missing. */
    static double seconds(String duration) {
        if (duration == null || duration.isEmpty()) {
            return 0;
        }
        String s = duration.endsWith("s") ? duration.substring(0, duration.length() - 1) : duration;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
