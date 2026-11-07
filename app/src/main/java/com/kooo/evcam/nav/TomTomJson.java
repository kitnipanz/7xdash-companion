package com.kooo.evcam.nav;

import com.kooo.evcam.trip.DestinationLink;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Writes what we ask TomTom's Orbis Routing and Search APIs and reads what they answer. Routes must
 * come from Orbis, the same map as the tiles ({@link NavMap}): the older Routing API's line runs
 * metres beside the drawn roads. Pure Java plus org.json, see the test.
 */
public final class TomTomJson {

    private static final String BASE = "https://api.tomtom.com";

    private TomTomJson() {
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

    /**
     * Drive from here to there, with traffic and the traffic along the line; {@code alternatives}
     * asks for up to two other routes too (to choose before starting). {@code language} like "th-TH".
     */
    public static String routeUrl(double fromLat, double fromLng, Double heading,
                                  double toLat, double toLng, String language, boolean alternatives) {
        StringBuilder url = new StringBuilder(String.format(Locale.US,
                "%s/maps/orbis/routing/calculateRoute/%.6f,%.6f:%.6f,%.6f/json", BASE, fromLat, fromLng, toLat, toLng))
                .append("?apiVersion=2&travelMode=car&routeType=fast&traffic=live&sectionType=traffic")
                .append("&instructionsType=text&guidanceVersion=2&instructionPhonetics=LHP&language=").append(language)
                .append("&maxAlternatives=").append(alternatives ? 2 : 0);
        if (heading != null) {
            url.append("&vehicleHeading=").append(((int) Math.round(heading) % 360 + 360) % 360);
        }
        return url.toString();
    }

    /** Fuzzy search in Thailand, leaning towards (lat, lng) when known. */
    public static String searchUrl(String query, Double lat, Double lng, String language) {
        StringBuilder url = new StringBuilder(BASE).append("/search/2/search/").append(encode(query))
                .append(".json?limit=10&countrySet=TH&language=").append(language);
        if (lat != null && lng != null) {
            url.append(String.format(Locale.US, "&lat=%.6f&lon=%.6f", lat, lng));
        }
        return url.toString();
    }

    private static String encode(String text) {
        try {
            return URLEncoder.encode(text, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The first route in an answer, or null when there is none. */
    public static NavRoute route(String body) throws JSONException {
        List<NavRoute> all = routes(body);
        return all.isEmpty() ? null : all.get(0);
    }

    /** Every route in an answer; TomTom orders them best first. */
    public static List<NavRoute> routes(String body) throws JSONException {
        List<NavRoute> out = new ArrayList<>();
        JSONArray routes = new JSONObject(body).optJSONArray("routes");
        for (int i = 0; routes != null && i < routes.length(); i++) {
            NavRoute r = route(routes.getJSONObject(i), i == 0);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    private static NavRoute route(JSONObject r, boolean best) throws JSONException {
        double[] pts = points(r);
        if (pts.length < 4) {
            return null;
        }
        JSONObject summary = r.optJSONObject("summary");
        double seconds = summary == null ? 0 : summary.optDouble("travelTimeInSeconds", 0);
        double delay = summary == null ? 0 : summary.optDouble("trafficDelayInSeconds", 0);
        double free = Math.max(0, seconds - delay);
        NavRoute.Builder b = new NavRoute.Builder().duration(seconds, free);
        addTraffic(b, r, pts);

        JSONObject guidance = r.optJSONObject("guidance");
        JSONArray steps = guidance == null ? null : guidance.optJSONArray("instructions");
        int count = pts.length / 2;
        if (steps == null || steps.length() == 0) {
            b.step(Maneuver.DEPART, "", 0, seconds, pts);
            return b.label("", best).build();
        }
        String via = "";
        double longest = 0;
        int from = 0;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.getJSONObject(i);
            Maneuver m = Maneuver.ofTomTom(step.optString("maneuver", null));
            boolean last = i == steps.length() - 1;
            if (m == Maneuver.ARRIVE && last) {
                continue;
            }
            JSONObject next = last ? null : steps.getJSONObject(i + 1);
            int to = next == null ? count - 1 : pointOf(next, pts, from);
            double meters = next == null ? 0 : next.optDouble("routeOffsetInMeters", 0) - step.optDouble("routeOffsetInMeters", 0);
            // a step's own time is without traffic: the route's traffic factor goes on top of it
            double time = next == null ? 0 : timeAt(next) - timeAt(step);
            time *= seconds > 0 ? free / seconds : 1;
            double[] line = new double[(to - from + 1) * 2];
            System.arraycopy(pts, from * 2, line, 0, line.length);
            b.step(m, step.optString("instructionMessage", ""), meters, time, line);
            String street = street(step);
            if (meters > longest && !street.isEmpty()) {
                longest = meters;
                via = street;
            }
            from = to;
        }
        return b.label(via, best).build();
    }

    /** The line's point at an instruction's maneuver, at or after {@code from}. */
    private static int pointOf(JSONObject step, double[] pts, int from) {
        JSONObject at = step.optJSONObject("maneuverPoint");
        if (at == null) {
            return from;
        }
        double lat = at.optDouble("latitude");
        double lng = at.optDouble("longitude");
        int best = from;
        double nearest = Double.MAX_VALUE;
        for (int i = from; i < pts.length / 2; i++) {
            double d = Math.abs(pts[2 * i] - lat) + Math.abs(pts[2 * i + 1] - lng);
            if (d < nearest) {
                nearest = d;
                best = i;
            }
        }
        return best;
    }

    /** Seconds from the start of the route to an instruction, from the first point of its path. */
    private static double timeAt(JSONObject step) {
        JSONArray path = step.optJSONArray("routePath");
        JSONObject first = path == null ? null : path.optJSONObject(0);
        return first == null ? 0 : first.optDouble("travelTimeInSeconds", 0);
    }

    /** The road an instruction leads onto. */
    private static String street(JSONObject step) {
        JSONObject road = step.optJSONObject("nextRoadInfo");
        JSONObject name = road == null ? null : road.optJSONObject("streetName");
        return name == null ? "" : name.optString("text", "");
    }

    /** {@code [lat0, lng0, lat1, lng1, ...]} of all the route's legs, joined. */
    private static double[] points(JSONObject r) throws JSONException {
        List<Double> out = new ArrayList<>();
        JSONArray legs = r.optJSONArray("legs");
        for (int l = 0; legs != null && l < legs.length(); l++) {
            JSONArray pts = legs.getJSONObject(l).optJSONArray("points");
            for (int i = 0; pts != null && i < pts.length(); i++) {
                JSONObject p = pts.getJSONObject(i);
                out.add(p.getDouble("latitude"));
                out.add(p.getDouble("longitude"));
            }
        }
        double[] pts = new double[out.size()];
        for (int i = 0; i < pts.length; i++) {
            pts[i] = out.get(i);
        }
        return pts;
    }

    /** TomTom's traffic sections along the route, point indices turned into metres along it. */
    private static void addTraffic(NavRoute.Builder b, JSONObject r, double[] pts) {
        JSONArray sections = r.optJSONArray("sections");
        if (sections == null) {
            return;
        }
        int count = pts.length / 2;
        double[] along = new double[count];
        for (int i = 1; i < count; i++) {
            along[i] = along[i - 1] + Geo.meters(pts[2 * i - 2], pts[2 * i - 1], pts[2 * i], pts[2 * i + 1]);
        }
        for (int i = 0; i < sections.length(); i++) {
            JSONObject s = sections.optJSONObject(i);
            if (s == null || !"TRAFFIC".equals(s.optString("sectionType"))) {
                continue;
            }
            int from = Math.max(0, Math.min(count - 1, s.optInt("startPointIndex", 0)));
            int to = Math.max(0, Math.min(count - 1, s.optInt("endPointIndex", 0)));
            b.traffic(along[from], along[to], level(s));
        }
    }

    /** Major or indefinite delays and closures are a jam; anything lesser is slow. */
    static byte level(JSONObject section) {
        int delay = section.optInt("magnitudeOfDelay", 0);
        boolean closed = "ROAD_CLOSURE".equals(section.optString("simpleCategory"));
        return closed || delay >= 3 ? NavRoute.JAM : NavRoute.SLOW;
    }

    /** The places in a search answer, in TomTom's order. */
    public static List<Found> places(String body) throws JSONException {
        List<Found> out = new ArrayList<>();
        JSONArray results = new JSONObject(body).optJSONArray("results");
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject p = results.getJSONObject(i);
            JSONObject at = p.optJSONObject("position");
            if (at == null || !at.has("lat") || !at.has("lon")) {
                continue;
            }
            JSONObject poi = p.optJSONObject("poi");
            JSONObject address = p.optJSONObject("address");
            String line = address == null ? "" : address.optString("freeformAddress", "");
            out.add(new Found(new DestinationLink.Place(at.getDouble("lat"), at.getDouble("lon"),
                    poi == null ? line : poi.optString("name", line)), line));
        }
        return out;
    }

    /** No road between the two places: a normal answer of nothing, not a failure. */
    public static boolean noRoute(String body) {
        return body != null && body.contains("NO_ROUTE_FOUND");
    }

    /** The message in an error answer, in any of the shapes TomTom uses, or null. */
    public static String error(String body) {
        try {
            JSONObject j = new JSONObject(body);
            JSONObject detailed = j.optJSONObject("detailedError");
            if (detailed != null) {
                return detailed.optString("message", null);
            }
            JSONObject e = j.optJSONObject("error");
            return e != null ? e.optString("description", null) : j.optString("errorText", null);
        } catch (Exception e) {
            return null;
        }
    }
}
