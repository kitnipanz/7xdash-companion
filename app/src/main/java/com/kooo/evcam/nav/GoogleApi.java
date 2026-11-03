package com.kooo.evcam.nav;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.BuildConfig;

import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The two Google calls Navigation makes over the internet: a driving route with traffic (Routes
 * API, computeRoutes) and a place search (Places API (New), searchText). Both use the build's
 * {@code MAPS_API_KEY}. The package and signing certificate go with each call, so the key can
 * be restricted to this app in the Google Cloud console. Blocking: call off the main thread.
 */
final class GoogleApi {

    private static final String TAG = "GoogleApi";
    private static final String ROUTES = "https://routes.googleapis.com/directions/v2:computeRoutes";
    private static final String SEARCH = "https://places.googleapis.com/v1/places:searchText";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** Why a call gave nothing. */
    static final class Failure extends Exception {
        Failure(String message) {
            super(message);
        }
    }

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();
    private final Context context;
    private String cert;

    GoogleApi(Context context) {
        this.context = context.getApplicationContext();
    }

    static boolean hasKey() {
        return !BuildConfig.MAPS_API_KEY.isEmpty();
    }

    /** The route, or null when Google found no road there. */
    NavRoute route(double fromLat, double fromLng, Double heading, double toLat, double toLng) throws Failure {
        String body = post(ROUTES, GoogleJson.ROUTE_FIELDS,
                GoogleJson.routeRequest(fromLat, fromLng, heading, toLat, toLng, language()));
        try {
            return GoogleJson.route(body);
        } catch (Exception e) {
            throw new Failure("unreadable route: " + e.getMessage());
        }
    }

    List<GoogleJson.Found> search(String query, Double lat, Double lng) throws Failure {
        String body = post(SEARCH, GoogleJson.PLACE_FIELDS, GoogleJson.searchRequest(query, lat, lng, language()));
        try {
            return GoogleJson.places(body);
        } catch (Exception e) {
            throw new Failure("unreadable search: " + e.getMessage());
        }
    }

    /** "th" when 7xDash is in Thai, else "en": instructions come back in that language. */
    String language() {
        String lang = context.getResources().getConfiguration().getLocales().get(0).getLanguage();
        return "th".equals(lang) ? "th" : "en";
    }

    private String post(String url, String fields, String json) throws Failure {
        if (!hasKey()) {
            throw new Failure("no key");
        }
        Request request = new Request.Builder()
                .url(url)
                .header("X-Goog-Api-Key", BuildConfig.MAPS_API_KEY)
                .header("X-Goog-FieldMask", fields)
                .header("X-Android-Package", context.getPackageName())
                .header("X-Android-Cert", cert())
                .post(RequestBody.create(json, JSON))
                .build();
        try (Response r = client.newCall(request).execute()) {
            ResponseBody body = r.body();
            String text = body == null ? "" : body.string();
            if (!r.isSuccessful()) {
                String why = GoogleJson.error(text);
                AppLog.w(TAG, "HTTP " + r.code() + (why == null ? "" : ": " + why));
                throw new Failure(why == null ? "HTTP " + r.code() : why);
            }
            return text;
        } catch (java.io.IOException e) {
            throw new Failure("offline: " + e.getMessage());
        }
    }

    /** SHA-1 of the signing certificate, upper-case hex, as Google's key restrictions want it. */
    private synchronized String cert() {
        if (cert != null) {
            return cert;
        }
        cert = "";
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(),
                    PackageManager.GET_SIGNING_CERTIFICATES);
            Signature[] signatures = info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
            if (signatures != null && signatures.length > 0) {
                byte[] sha = MessageDigest.getInstance("SHA-1").digest(signatures[0].toByteArray());
                StringBuilder hex = new StringBuilder();
                for (byte b : sha) {
                    hex.append(String.format(Locale.US, "%02X", b));
                }
                cert = hex.toString();
            }
        } catch (Exception e) {
            AppLog.w(TAG, "no signing certificate: " + e.getMessage());
        }
        return cert;
    }
}
