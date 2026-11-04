package com.kooo.evcam.nav;

import android.content.Context;

import com.kooo.evcam.BuildConfig;

import org.maplibre.android.MapLibre;
import org.maplibre.android.maps.MapLibreMapOptions;
import org.maplibre.android.module.http.HttpRequestUtil;

import java.io.IOException;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * The map engine, MapLibre, built into the app: it draws TomTom's map itself, so the map looks and
 * behaves the same on every head unit, with or without Play services. Incidents (accidents, road
 * works, closures) come as a layer of TomTom's map style.
 */
final class NavMap {

    private static final String STYLE = "https://api.tomtom.com/maps/orbis/assets/styles/0.*/style?apiVersion=1"
            + "&map=basic_street-%1$s-driving&trafficIncidents=incidents_%1$s";
    private static boolean started;

    private NavMap() {
    }

    /** Before the first map. */
    static void start(Context context) {
        if (started) {
            return;
        }
        started = true;
        MapLibre.getInstance(context.getApplicationContext());
        HttpRequestUtil.setOkHttpClient(new OkHttpClient.Builder().addInterceptor(NavMap::withKey).build());
        // its logs carry the URLs, and the URLs the key
        HttpRequestUtil.setLogEnabled(false);
        HttpRequestUtil.setPrintRequestUrlOnFailure(false);
    }

    /** Texture mode: the map clips to rounded windows and stays under the views over it. */
    static MapLibreMapOptions options(Context context) {
        return MapLibreMapOptions.createFromAttributes(context).textureMode(true);
    }

    static String styleUrl(boolean dark) {
        return String.format(java.util.Locale.US, STYLE, dark ? "dark" : "light");
    }

    /** Every call to TomTom carries the key, including the map's tiles that its style names. */
    static Response withKey(Interceptor.Chain chain) throws IOException {
        Request request = chain.request();
        HttpUrl url = request.url();
        if (url.host().endsWith("tomtom.com") && url.queryParameter("key") == null) {
            url = url.newBuilder().addQueryParameter("key", BuildConfig.TOMTOM_API_KEY).build();
            request = request.newBuilder().url(url).build();
        }
        return chain.proceed(request);
    }
}
