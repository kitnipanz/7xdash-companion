package com.kooo.evcam.trip;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The two things that need the internet: the road from here to there (Google's Routes API when a
 * key is set, else OSRM's public server on OpenStreetMap data; free, no key, fair use only, which
 * one car asking every few minutes is) and
 * following a short share link to the long one with the coordinates in it. Blocking: call off the
 * main thread.
 */
final class RouteLookup {

    private static final String TAG = "RouteLookup";
    private static final String OSRM = "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f"
            + "?overview=full&geometries=polyline";
    private static final String GOOGLE_ROUTES = "https://routes.googleapis.com/directions/v2:computeRoutes";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_HOPS = 6;
    private static final int MAX_BODY = 512 * 1024;
    private static final String NUM = "(-?\\d{1,3}\\.\\d+)";
    private static final Pattern LAT_LNG_JSON = Pattern.compile(
            "\"latitude\"\\s*:\\s*" + NUM + "\\s*,\\s*\"longitude\"\\s*:\\s*" + NUM);
    private static final Pattern LAT_LNG_META = Pattern.compile(
            "place:location:latitude\"\\s+content=\"" + NUM + "\"[^>]*>\\s*<meta[^>]+place:location:longitude\"\\s+content=\"" + NUM);

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
    private final String agent = "7xDash/" + BuildConfig.VERSION_NAME;

    /** A route answer: the line of the road and who drew it. */
    static final class Route {
        final RouteLine line;
        /** True when Google drew it (the same route Google Maps on the phone takes). */
        final boolean google;

        Route(RouteLine line, boolean google) {
            this.line = line;
            this.google = google;
        }
    }

    /**
     * The driving route from here to there with its line, or null when there is no answer (no
     * internet, no road). With a Google Maps API key it asks Google's Routes API, traffic aware and
     * starting in the direction the car is heading, so the road and the km are the ones Google Maps
     * shows; without a key, or when Google does not answer, OSRM.
     *
     * @param heading degrees clockwise from north the car is driving, or null when not known
     */
    Route drivingRoute(double fromLat, double fromLng, Float heading, double toLat, double toLng, String googleKey) {
        if (googleKey != null && !googleKey.trim().isEmpty()) {
            RouteLine line = google(fromLat, fromLng, heading, toLat, toLng, googleKey.trim());
            if (line != null) {
                return new Route(line, true);
            }
        }
        RouteLine line = osrm(fromLat, fromLng, toLat, toLng);
        return line == null ? null : new Route(line, false);
    }

    private RouteLine google(double fromLat, double fromLng, Float heading, double toLat, double toLng, String key) {
        try {
            JSONObject origin = new JSONObject().put("location", new JSONObject()
                    .put("latLng", new JSONObject().put("latitude", fromLat).put("longitude", fromLng)));
            if (heading != null) {
                origin.getJSONObject("location").put("heading", Math.round(((heading % 360) + 360) % 360) % 360);
            }
            JSONObject request = new JSONObject()
                    .put("origin", origin)
                    .put("destination", new JSONObject().put("location", new JSONObject()
                            .put("latLng", new JSONObject().put("latitude", toLat).put("longitude", toLng))))
                    .put("travelMode", "DRIVE")
                    .put("routingPreference", "TRAFFIC_AWARE")
                    .put("polylineQuality", "HIGH_QUALITY")
                    .put("polylineEncoding", "ENCODED_POLYLINE");
            Request http = new Request.Builder()
                    .url(GOOGLE_ROUTES)
                    .header("User-Agent", agent)
                    .header("X-Goog-Api-Key", key)
                    .header("X-Goog-FieldMask", "routes.distanceMeters,routes.polyline.encodedPolyline")
                    .post(RequestBody.create(request.toString(), JSON))
                    .build();
            try (Response r = client.newCall(http).execute()) {
                ResponseBody body = r.body();
                String text = body == null ? "" : body.string();
                if (!r.isSuccessful()) {
                    AppLog.w(TAG, "google route: HTTP " + r.code() + " " + errorOf(text));
                    return null;
                }
                JSONArray routes = new JSONObject(text).optJSONArray("routes");
                if (routes == null || routes.length() == 0) {
                    AppLog.w(TAG, "google route: no route");
                    return null;
                }
                JSONObject route = routes.getJSONObject(0);
                JSONObject polyline = route.optJSONObject("polyline");
                return RouteLine.decode(polyline == null ? null : polyline.optString("encodedPolyline"),
                        route.optDouble("distanceMeters", 0));
            }
        } catch (Exception e) {
            AppLog.w(TAG, "google route failed: " + e.getMessage());
            return null;
        }
    }

    private RouteLine osrm(double fromLat, double fromLng, double toLat, double toLng) {
        String url = String.format(Locale.US, OSRM, fromLng, fromLat, toLng, toLat);
        try (Response r = client.newCall(new Request.Builder().url(url).header("User-Agent", agent).build()).execute()) {
            ResponseBody body = r.body();
            if (!r.isSuccessful() || body == null) {
                AppLog.w(TAG, "route: HTTP " + r.code());
                return null;
            }
            JSONObject json = new JSONObject(body.string());
            JSONArray routes = json.optJSONArray("routes");
            if (!"Ok".equals(json.optString("code")) || routes == null || routes.length() == 0) {
                AppLog.w(TAG, "route: " + json.optString("code"));
                return null;
            }
            JSONObject route = routes.getJSONObject(0);
            return RouteLine.decode(route.optString("geometry"), route.optDouble("distance", 0));
        } catch (Exception e) {
            AppLog.w(TAG, "route failed: " + e.getMessage());
            return null;
        }
    }

    /** Google's error message, short, for the log (never the key). */
    private static String errorOf(String body) {
        try {
            String m = new JSONObject(body).getJSONObject("error").optString("message");
            return m.length() > 160 ? m.substring(0, 160) : m;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * The place behind a short link: follows the redirects, trying each address on the way, then
     * looks in the last page for the coordinates Apple and Google put there. Null if none found.
     */
    DestinationLink.Place resolve(String url, String name) {
        String at = url;
        for (int hop = 0; hop < MAX_HOPS && at != null; hop++) {
            try (Response r = client.newCall(new Request.Builder().url(at).header("User-Agent", agent).build()).execute()) {
                String next = r.isRedirect() ? r.header("Location") : null;
                if (next != null) {
                    okhttp3.HttpUrl absolute = r.request().url().resolve(next);
                    next = absolute == null ? next : absolute.toString();
                    DestinationLink.Place p = DestinationLink.parse(next);
                    if (p != null) {
                        return named(p, name);
                    }
                    at = next;
                    continue;
                }
                ResponseBody body = r.body();
                if (body == null) {
                    return null;
                }
                String page = body.source().readUtf8(Math.min(MAX_BODY, Math.max(0, body.contentLength() < 0
                        ? MAX_BODY : body.contentLength())));
                return named(fromPage(page), name);
            } catch (IOException | RuntimeException e) {
                AppLog.w(TAG, "resolve failed at hop " + hop + ": " + e.getMessage());
                return null;
            }
        }
        return null;
    }

    static DestinationLink.Place fromPage(String page) {
        if (page == null) {
            return null;
        }
        for (Pattern p : new Pattern[] {LAT_LNG_META, LAT_LNG_JSON}) {
            Matcher m = p.matcher(page);
            if (m.find()) {
                try {
                    return new DestinationLink.Place(Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)), "");
                } catch (NumberFormatException ignored) {
                    // try the next pattern
                }
            }
        }
        // Google pages carry the long link (with the !3d!4d pin) somewhere in them
        Matcher m = Pattern.compile("https?://[^\\s\"'<>\\\\]*maps[^\\s\"'<>\\\\]*").matcher(page);
        while (m.find()) {
            DestinationLink.Place p = DestinationLink.parse(m.group());
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    private static DestinationLink.Place named(DestinationLink.Place p, String name) {
        if (p == null || !p.name.isEmpty() || name == null || name.isEmpty()) {
            return p;
        }
        return new DestinationLink.Place(p.lat, p.lng, name);
    }
}
