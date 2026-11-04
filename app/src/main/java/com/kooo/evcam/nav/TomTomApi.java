package com.kooo.evcam.nav;

import android.content.Context;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.BuildConfig;

import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The two TomTom calls Navigation makes over the internet: a driving route with traffic and a place
 * search. Both use the build's {@code TOMTOM_API_KEY}, added by {@link NavMap#withKey}. Blocking:
 * call off the main thread.
 */
final class TomTomApi {

    private static final String TAG = "TomTomApi";

    /** Why a call gave nothing. */
    static final class Failure extends Exception {
        Failure(String message) {
            super(message);
        }
    }

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(NavMap::withKey)
            .build();
    private final Context context;

    TomTomApi(Context context) {
        this.context = context.getApplicationContext();
    }

    static boolean hasKey() {
        return !BuildConfig.TOMTOM_API_KEY.isEmpty();
    }

    /**
     * The routes, TomTom's pick first; with {@code alternatives} the other good ones too (up to two
     * more). Empty when TomTom found no road there.
     */
    List<NavRoute> routes(double fromLat, double fromLng, Double heading, double toLat, double toLng,
                          boolean alternatives) throws Failure {
        String body = get(TomTomJson.routeUrl(fromLat, fromLng, heading, toLat, toLng, language(), alternatives), true);
        try {
            return TomTomJson.noRoute(body) ? new java.util.ArrayList<>() : TomTomJson.routes(body);
        } catch (Exception e) {
            throw new Failure("unreadable route: " + e.getMessage());
        }
    }

    List<TomTomJson.Found> search(String query, Double lat, Double lng) throws Failure {
        String body = get(TomTomJson.searchUrl(query, lat, lng, language()), false);
        try {
            return TomTomJson.places(body);
        } catch (Exception e) {
            throw new Failure("unreadable search: " + e.getMessage());
        }
    }

    /** "th-TH" when 7xDash is in Thai, else "en-GB": instructions come back in that language. */
    String language() {
        String lang = context.getResources().getConfiguration().getLocales().get(0).getLanguage();
        return "th".equals(lang) ? "th-TH" : "en-GB";
    }

    private String get(String url, boolean routing) throws Failure {
        if (!hasKey()) {
            throw new Failure("no key");
        }
        try (Response r = client.newCall(new Request.Builder().url(url).build()).execute()) {
            ResponseBody body = r.body();
            String text = body == null ? "" : body.string();
            if (!r.isSuccessful() && !(routing && TomTomJson.noRoute(text))) {
                String why = TomTomJson.error(text);
                AppLog.w(TAG, "HTTP " + r.code() + (why == null ? "" : ": " + why));
                throw new Failure(why == null ? "HTTP " + r.code() : why);
            }
            return text;
        } catch (java.io.IOException e) {
            throw new Failure("offline");
        }
    }
}
