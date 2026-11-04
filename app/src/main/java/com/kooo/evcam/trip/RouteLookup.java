package com.kooo.evcam.trip;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.BuildConfig;
import com.kooo.evcam.nav.TomTomJson;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The two things that need the internet: road km from here to there (OSRM's public server on
 * OpenStreetMap data; free, no key, fair use only, which one car asking every few minutes is) and
 * following a short share link to the long one with the coordinates in it. A Google link that only
 * names the place is looked up in TomTom's search. Blocking: call off the main thread.
 */
final class RouteLookup {

    private static final String TAG = "RouteLookup";
    private static final String OSRM = "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=false";
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

    /** Road km, or null when there is no answer (no internet, no road). */
    Double drivingKm(double fromLat, double fromLng, double toLat, double toLng) {
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
            return routes.getJSONObject(0).getDouble("distance") / 1000.0;
        } catch (Exception e) {
            AppLog.w(TAG, "route failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * The place behind a short link: follows the redirects, trying each address on the way, then
     * looks in the last page for the coordinates Apple and Google put there. Google links that
     * only name the place (no pin on the way, none in the page) are looked up by that name in
     * TomTom's search. Null if none found.
     */
    DestinationLink.Place resolve(String url, String name) {
        String at = url;
        String googleName = DestinationLink.googleQuery(url);
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
                    String q = DestinationLink.googleQuery(next);
                    if (q != null) {
                        googleName = q;
                    }
                    at = next;
                    continue;
                }
                ResponseBody body = r.body();
                DestinationLink.Place p = body == null ? null : fromPage(readCapped(body));
                if (p != null) {
                    return named(p, name);
                }
                break;
            } catch (IOException | RuntimeException e) {
                AppLog.w(TAG, "resolve failed at hop " + hop + ": " + e.getMessage());
                break;
            }
        }
        if (googleName == null) {
            AppLog.w(TAG, "resolve: no coordinates and no Google place name");
            return null;
        }
        return named(search(googleName), name);
    }

    /** The first place TomTom's search finds for {@code query}, or null. */
    DestinationLink.Place search(String query) {
        if (BuildConfig.TOMTOM_API_KEY.isEmpty()) {
            return null;
        }
        String url = TomTomJson.searchUrl(query, null, null, "th-TH") + "&key=" + BuildConfig.TOMTOM_API_KEY;
        try (Response r = client.newCall(new Request.Builder().url(url).header("User-Agent", agent).build()).execute()) {
            ResponseBody body = r.body();
            if (!r.isSuccessful() || body == null) {
                AppLog.w(TAG, "search: HTTP " + r.code());
                return null;
            }
            DestinationLink.Place p = fromSearch(readCapped(body));
            if (p == null) {
                AppLog.w(TAG, "search: no place found");
            }
            return p;
        } catch (IOException | RuntimeException e) {
            AppLog.w(TAG, "search failed");
            return null;
        }
    }

    /** Where the first place in a TomTom search answer is, or null; its name stays the one that was shared. */
    static DestinationLink.Place fromSearch(String answer) {
        if (answer == null) {
            return null;
        }
        try {
            java.util.List<TomTomJson.Found> found = TomTomJson.places(answer);
            if (found.isEmpty()) {
                return null;
            }
            DestinationLink.Place p = found.get(0).place;
            return new DestinationLink.Place(p.lat, p.lng, "");
        } catch (org.json.JSONException e) {
            return null;
        }
    }

    /**
     * The body, at most {@link #MAX_BODY} bytes. Google sends pages without a length (chunked):
     * asking for exactly MAX_BODY bytes then fails at the end of a shorter page, so read up to it.
     */
    private static String readCapped(ResponseBody body) throws IOException {
        okio.BufferedSource source = body.source();
        source.request(MAX_BODY);
        okio.Buffer buffer = source.getBuffer();
        return buffer.readUtf8(Math.min(buffer.size(), MAX_BODY));
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
            // The static map's centre is the area the page shows (often where the reader is), not the place
            if (m.group().contains("staticmap")) {
                continue;
            }
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
