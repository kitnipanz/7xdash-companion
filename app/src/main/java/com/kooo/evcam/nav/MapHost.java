package com.kooo.evcam.nav;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.BuildConfig;

import java.util.function.Consumer;

/**
 * The map view of one screen (the full screen, the floating window, the HUD) and its engine: Google's
 * map where Google Play services or microG answers and the setting allows it, otherwise TomTom's map
 * drawn by MapLibre. A Google map that fails to start, or doesn't come up within {@link #GOOGLE_WAIT_MS},
 * gives way to TomTom's in the same place, and Google isn't tried again until the app restarts. The
 * owner passes on its lifecycle. Main thread.
 */
final class MapHost {

    private static final String TAG = "MapHost";
    /** A Google map not ready by then has failed (microG without its maps part, a blocked service). */
    private static final long GOOGLE_WAIT_MS = 8_000L;
    /** Google failed once in this run: straight to TomTom's map from now on. */
    private static boolean googleFailed;

    private final Context context;
    private final ViewGroup parent;
    private final Handler main = new Handler(Looper.getMainLooper());
    private View view;
    private com.google.android.gms.maps.MapView googleView;
    private org.maplibre.android.maps.MapView libreView;
    private Consumer<MapEngine> ready;
    private MapEngine engine;
    private boolean started;
    private boolean resumed;
    private boolean destroyed;

    /** The map fills {@code parent}; {@code ready} gets its engine once it can be drawn on. */
    MapHost(Context context, ViewGroup parent, Consumer<MapEngine> ready) {
        this.context = context;
        this.parent = parent;
        this.ready = ready;
        if (!wantGoogle(context) || !openGoogle()) {
            openLibre();
        }
    }

    /** The view the map is drawn in; the painter puts the car over it. */
    ViewGroup view() {
        return (ViewGroup) view;
    }

    boolean isGoogle() {
        return googleView != null;
    }

    /** Google's map can be tried: the setting is on, the build has its key, and Play services (or microG) answers. */
    static boolean wantGoogle(Context context) {
        if (googleFailed || BuildConfig.MAPS_API_KEY.isEmpty() || !new AppConfig(context).isNavGoogleMapEnabled()) {
            return false;
        }
        try {
            int state = com.google.android.gms.common.GoogleApiAvailability.getInstance()
                    .isGooglePlayServicesAvailable(context);
            if (state != com.google.android.gms.common.ConnectionResult.SUCCESS) {
                AppLog.i(TAG, "no Google map: Play services state " + state);
                return false;
            }
            return true;
        } catch (Throwable t) {
            AppLog.i(TAG, "no Google map: " + t.getMessage());
            return false;
        }
    }

    private final Runnable googleTooSlow = () -> {
        if (engine == null && googleView != null && !destroyed) {
            fallBack("not ready after " + GOOGLE_WAIT_MS + " ms");
        }
    };

    private boolean openGoogle() {
        try {
            com.google.android.gms.maps.GoogleMapOptions options = new com.google.android.gms.maps.GoogleMapOptions()
                    .compassEnabled(false).mapToolbarEnabled(false).zoomControlsEnabled(false);
            com.google.android.gms.maps.MapView v = new com.google.android.gms.maps.MapView(context, options);
            v.onCreate(null);
            googleView = v;
            view = v;
            parent.addView(v, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            main.postDelayed(googleTooSlow, GOOGLE_WAIT_MS);
            v.getMapAsync(map -> {
                if (destroyed || googleView != v) {
                    return;
                }
                main.removeCallbacks(googleTooSlow);
                try {
                    engine = new GoogleEngine(context, map);
                } catch (RuntimeException e) {
                    fallBack("map refused: " + e.getMessage());
                    return;
                }
                AppLog.i(TAG, "Google map");
                deliver();
            });
            return true;
        } catch (Throwable t) {
            AppLog.w(TAG, "no Google map: " + t);
            googleFailed = true;
            dropGoogle();
            return false;
        }
    }

    private void fallBack(String why) {
        AppLog.w(TAG, "Google map failed (" + why + "): TomTom's map instead");
        googleFailed = true;
        main.removeCallbacks(googleTooSlow);
        dropGoogle();
        openLibre();
    }

    private void dropGoogle() {
        com.google.android.gms.maps.MapView v = googleView;
        googleView = null;
        if (v == null) {
            return;
        }
        try {
            if (resumed) {
                v.onPause();
            }
            if (started) {
                v.onStop();
            }
            v.onDestroy();
        } catch (RuntimeException ignored) {
            // it never came up
        }
        parent.removeView(v);
        if (view == v) {
            view = null;
        }
    }

    private void openLibre() {
        NavMap.start(context);
        org.maplibre.android.maps.MapView v = new org.maplibre.android.maps.MapView(context, NavMap.options(context));
        v.onCreate(null);
        libreView = v;
        view = v;
        parent.addView(v, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        if (started) {
            v.onStart();
        }
        if (resumed) {
            v.onResume();
        }
        v.getMapAsync(map -> {
            if (destroyed || libreView != v) {
                return;
            }
            engine = new MapLibreEngine(context, map, v);
            AppLog.i(TAG, "TomTom map");
            deliver();
        });
    }

    private void deliver() {
        Consumer<MapEngine> r = ready;
        ready = null;
        if (r != null) {
            r.accept(engine);
        }
    }

    // ---- the owner's lifecycle ----

    void start() {
        if (started) {
            return;
        }
        started = true;
        if (googleView != null) {
            googleView.onStart();
        } else if (libreView != null) {
            libreView.onStart();
        }
    }

    void resume() {
        if (resumed) {
            return;
        }
        resumed = true;
        if (googleView != null) {
            googleView.onResume();
        } else if (libreView != null) {
            libreView.onResume();
        }
    }

    void pause() {
        if (!resumed) {
            return;
        }
        resumed = false;
        if (googleView != null) {
            googleView.onPause();
        } else if (libreView != null) {
            libreView.onPause();
        }
    }

    void stop() {
        pause();
        if (!started) {
            return;
        }
        started = false;
        if (googleView != null) {
            googleView.onStop();
        } else if (libreView != null) {
            libreView.onStop();
        }
    }

    void lowMemory() {
        if (googleView != null) {
            googleView.onLowMemory();
        } else if (libreView != null) {
            libreView.onLowMemory();
        }
    }

    void destroy() {
        if (destroyed) {
            return;
        }
        stop();
        destroyed = true;
        main.removeCallbacks(googleTooSlow);
        ready = null;
        if (googleView != null) {
            googleView.onDestroy();
        } else if (libreView != null) {
            libreView.onDestroy();
        }
    }
}
