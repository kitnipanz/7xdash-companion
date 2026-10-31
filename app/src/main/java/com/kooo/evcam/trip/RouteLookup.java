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

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The two things that need the internet: road km from here to there (OSRM's public server on
 * OpenStreetMap data; free, no key, fair use only, which one car asking every few minutes is) and
 * following a short share link to the long one with the coordinates in it. Blocking: call off the
 * main thread.
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
