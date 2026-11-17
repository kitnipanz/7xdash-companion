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
 * drawn by MapLibre. A Google map that fails to start, doesn't come up within {@link #GOOGLE_WAIT_MS},
 * or fails later while drawing (microG's maps code runs in this process and can throw), gives way to
 * TomTom's in the same place: the owner's ready callback then runs again with the new engine. The
 * Play services / microG version it failed with isn't tried again ({@link AppConfig#getNavGoogleMapBrokenVersion}).
 * The owner passes on its lifecycle. Main thread.
 */
final class MapHost {

    private static final String TAG = "MapHost";
    /** A Google map not ready by then has failed (microG without its maps part, a blocked service). */
    private static final long GOOGLE_WAIT_MS = 8_000L;
    /** Google failed once in this run: straight to TomTom's map from now on. */
    private static boolean googleFailed;
    private static final String GMS = "com.google.android.gms";

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

    /**
     * The map fills {@code parent}; {@code ready} gets its engine once it can be drawn on, and again
     * with TomTom's if Google's fails later (the owner drops what it built on the old one).
     */
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
        AppConfig config = new AppConfig(context);
        if (googleFailed || BuildConfig.MAPS_API_KEY.isEmpty() || !config.isNavGoogleMapEnabled()) {
            return false;
        }
        long broken = config.getNavGoogleMapBrokenVersion();
        if (broken != 0 && broken == gmsVersion(context)) {
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

    /** Whether the Google map failed with the Play services / microG installed now. */
    static boolean googleBroken(Context context) {
        long broken = new AppConfig(context).getNavGoogleMapBrokenVersion();
        return googleFailed || (broken != 0 && broken == gmsVersion(context));
    }

    private static long gmsVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(GMS, 0).getLongVersionCode();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Google's map failed: not again in this run, nor with this Play services / microG. */
    private void markGoogleFailed() {
        googleFailed = true;
        long v = gmsVersion(context);
        if (v > 0) {
            new AppConfig(context).setNavGoogleMapBrokenVersion(v);
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
            googleView = v;
            v.onCreate(null);
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
                    // a failure while drawing: switch after the call that failed has unwound
                    engine = new GoogleEngine(context, map, t -> main.post(() -> {
                        if (!destroyed && googleView == v) {
                            fallBack("failed while drawing: " + t);
                        }
                    }));
                } catch (Throwable t) {
                    fallBack("map refused: " + t);
                    return;
                }
                AppLog.i(TAG, "Google map");
                deliver();
            });
            return true;
        } catch (Throwable t) {
            AppLog.w(TAG, "no Google map: " + t);
            markGoogleFailed();
            dropGoogle();
            return false;
        }
    }

    private void fallBack(String why) {
        AppLog.w(TAG, "Google map failed (" + why + "): TomTom's map instead");
        markGoogleFailed();
        main.removeCallbacks(googleTooSlow);
        engine = null;
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
        if (ready != null && engine != null) {
            ready.accept(engine);
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
