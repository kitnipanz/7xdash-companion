package com.kooo.evcam.nav;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MapColorScheme;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.kooo.evcam.AppConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Draws a {@link Navigator.Snapshot} on a Google map: the route, the destination pin, the car, and
 * the camera. Live traffic shows on the route preview and goes while guiding, so the only coloured
 * line is the way to go. While guiding, the camera follows like a phone's navigation: heading up, tilted,
 * the car centred left to right and low on the map so the road ahead fills it. The zoom follows the
 * speed: close in when slow (side streets and alleys show), further out when fast, and close again
 * before a turn; it eases there instead of jumping. Shared by the full screen and the floating
 * window. Main thread.
 */
final class NavMapPainter {

    private static final int ROUTE_BLUE = 0xFF4285F4;
    private static final int ROUTE_EDGE = 0xFF1A5BC4;
    /** While guiding, the car sits this far down the map. */
    private static final float CAR_AT = 0.64f;
    /** Zoom at walking pace and at motorway speed; in between it scales with the speed. */
    private static final float ZOOM_SLOW = 18.5f;
    private static final float ZOOM_FAST = 15.5f;
    private static final float SLOW_KMH = 15f;
    private static final float FAST_KMH = 100f;
    /** This close to a turn the map is at least {@link #ZOOM_TURN}, whatever the speed. */
    private static final double TURN_NEAR_M = 250;
    private static final float ZOOM_TURN = 17.5f;
    /** Share of the way to the new zoom taken at each update (about once a second). */
    private static final float ZOOM_EASE = 0.35f;

    private final GoogleMap map;
    private final View mapView;
    private final float density;
    /** Kept clear for the cards on top of the map when not guiding (the search, the trip sheet). */
    private final int cardsLeft;
    private final int cardsTop;

    private Polyline routeLine;
    private Polyline routeEdge;
    private Marker car;
    private Marker pin;
    private NavRoute shownRoute;
    private com.kooo.evcam.trip.DestinationLink.Place shownPin;
    private int[] padding;
    private boolean trafficShown;
    /** Live traffic on the map before guiding (the route preview); the HUD map never shows it. */
    boolean trafficAllowed = true;
    private int shownScheme = -1;
    private boolean shownDark;
    private boolean placed;
    /** The zoom the camera is easing; NaN until guiding starts. */
    private float zoom = Float.NaN;
    /** The camera follows the car until the map is moved by hand. */
    boolean following = true;

    NavMapPainter(Context context, GoogleMap map, View mapView, int cardsLeft, int cardsTop) {
        this.map = map;
        this.mapView = mapView;
        this.cardsLeft = cardsLeft;
        this.cardsTop = cardsTop;
        density = context.getResources().getDisplayMetrics().density;
        map.setTrafficEnabled(true);
        trafficShown = true;
        map.setBuildingsEnabled(true);
        map.getUiSettings().setMapToolbarEnabled(false);
        map.getUiSettings().setZoomControlsEnabled(false);
        applyScheme(new AppConfig(context).getNavMapScheme());
    }

    /**
     * Light, dark, or the head unit's day / night ({@link AppConfig#NAV_MAP_AUTO}). Auto is read here,
     * not left to the map's own follow-the-system, which does not change in a floating window; the
     * owner calls this again when the head unit switches.
     */
    void applyScheme(int scheme) {
        boolean dark = scheme == AppConfig.NAV_MAP_DARK || (scheme == AppConfig.NAV_MAP_AUTO && headUnitNight(mapView.getContext()));
        if (scheme != shownScheme || dark != shownDark) {
            shownScheme = scheme;
            shownDark = dark;
            com.kooo.evcam.AppLog.i("NavMapPainter", "map colours: " + (dark ? "dark" : "light")
                    + (scheme == AppConfig.NAV_MAP_AUTO ? " (head unit)" : ""));
        }
        map.setMapColorScheme(dark ? MapColorScheme.DARK : MapColorScheme.LIGHT);
        // the older map renderer (still on some head units) ignores the colour scheme: a night style does it there too
        try {
            map.setMapStyle(dark ? com.google.android.gms.maps.model.MapStyleOptions.loadRawResourceStyle(
                    mapView.getContext(), com.kooo.evcam.R.raw.nav_map_night) : null);
        } catch (RuntimeException e) {
            com.kooo.evcam.AppLog.w("NavMapPainter", "night style refused: " + e.getMessage());
        }
    }

    /** Before the first map: ask for Google's current renderer, which draws the light and dark schemes. */
    static void useLatestRenderer(android.content.Context context) {
        try {
            com.google.android.gms.maps.MapsInitializer.initialize(context.getApplicationContext(),
                    com.google.android.gms.maps.MapsInitializer.Renderer.LATEST, null);
        } catch (RuntimeException e) {
            com.kooo.evcam.AppLog.w("NavMapPainter", "renderer: " + e.getMessage());
        }
    }

    /** The head unit's own night mode (the system's, not this app's theme). */
    static boolean headUnitNight(android.content.Context context) {
        android.content.res.Configuration c = context.getApplicationContext().getResources().getConfiguration();
        return (c.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    void draw(Navigator.Snapshot s) {
        boolean guiding = s.phase == Navigator.Phase.GUIDING;
        // while guiding, the traffic layer's green and red roads looked like the way to go: only the route then
        boolean traffic = trafficAllowed && !guiding;
        if (traffic != trafficShown) {
            trafficShown = traffic;
            map.setTrafficEnabled(traffic);
        }
        int h = mapView.getHeight();
        if (guiding && h > 0) {
            // the camera's target is the middle of what the padding leaves: push it down to CAR_AT
            pad(0, Math.max(cardsTop, Math.round(h * (2 * CAR_AT - 1))), 0, 0);
        } else {
            pad(cardsLeft, cardsTop, 0, 0);
        }
        if (s.route != shownRoute) {
            shownRoute = s.route;
            if (routeLine != null) {
                routeLine.remove();
                routeEdge.remove();
                routeLine = null;
                routeEdge = null;
            }
            if (s.route != null) {
                List<LatLng> pts = new ArrayList<>(s.route.pointCount());
                for (int i = 0; i < s.route.pointCount(); i++) {
                    pts.add(new LatLng(s.route.lat(i), s.route.lng(i)));
                }
                routeEdge = map.addPolyline(new PolylineOptions().addAll(pts).color(ROUTE_EDGE).width(15 * density).zIndex(1));
                routeLine = map.addPolyline(new PolylineOptions().addAll(pts).color(ROUTE_BLUE).width(10 * density).zIndex(2));
            }
        }
        if (s.destination != shownPin) {
            shownPin = s.destination;
            if (pin != null) {
                pin.remove();
                pin = null;
            }
            if (s.destination != null) {
                pin = map.addMarker(new MarkerOptions().position(new LatLng(s.destination.lat, s.destination.lng))
                        .title(placeName(s.destination)));
            }
        }
        if (s.carLat == null) {
            return;
        }
        boolean onRoute = s.progress != null && !s.progress.offRoute && guiding;
        LatLng at = onRoute ? new LatLng(s.progress.lat, s.progress.lng) : new LatLng(s.carLat, s.carLng);
        float bearing = s.carBearing == null ? 0 : s.carBearing.floatValue();
        if (car == null) {
            car = map.addMarker(new MarkerOptions().position(at).flat(true).anchor(0.5f, 0.5f).zIndex(3)
                    .icon(BitmapDescriptorFactory.fromBitmap(carIcon())));
        }
        if (car != null) {
            car.setPosition(at);
            car.setRotation(bearing);
        }
        if (guiding) {
            float target = zoomFor(s.carSpeedKmh, s.progress == null ? null : s.progress.toNext);
            zoom = Float.isNaN(zoom) ? target : zoom + (target - zoom) * ZOOM_EASE;
        } else {
            zoom = Float.NaN;
        }
        if (!placed) {
            // straight to the car the first time, not a fly-in from the whole world
            placed = true;
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(at, guiding ? zoom : 15));
        }
        if (following && guiding) {
            map.animateCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder()
                    .target(at).bearing(bearing).tilt(50).zoom(zoom).build()), 900, null);
        } else if (following && s.phase == Navigator.Phase.IDLE) {
            map.animateCamera(CameraUpdateFactory.newLatLng(at));
        }
    }

    /** Back to following the car, flat and north-up when not guiding. */
    void recenter(Navigator.Snapshot s) {
        following = true;
        if (s.phase != Navigator.Phase.GUIDING && s.carLat != null) {
            map.animateCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder()
                    .target(new LatLng(s.carLat, s.carLng)).zoom(15).tilt(0).bearing(0).build()));
        }
        draw(s);
    }

    void fitRoute(NavRoute r) {
        if (r == null || r.pointCount() < 2) {
            return;
        }
        LatLngBounds.Builder b = LatLngBounds.builder();
        for (int i = 0; i < r.pointCount(); i++) {
            b.include(new LatLng(r.lat(i), r.lng(i)));
        }
        placed = true;
        try {
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), Math.round(48 * density)));
        } catch (IllegalStateException e) {
            // map not laid out yet
        }
    }

    private void pad(int left, int top, int right, int bottom) {
        int[] p = {left, top, right, bottom};
        if (!java.util.Arrays.equals(p, padding)) {
            padding = p;
            map.setPadding(left, top, right, bottom);
        }
    }

    /**
     * The zoom to ease towards: from the speed ({@link #ZOOM_SLOW} slow, {@link #ZOOM_FAST} fast),
     * at least {@link #ZOOM_TURN} near a turn. Without a speed, from the distance to the next turn.
     */
    static float zoomFor(Float kmh, Double toNextM) {
        float z;
        if (kmh != null) {
            float t = Math.max(0f, Math.min(1f, (kmh - SLOW_KMH) / (FAST_KMH - SLOW_KMH)));
            z = ZOOM_SLOW + (ZOOM_FAST - ZOOM_SLOW) * t;
        } else {
            double d = toNextM == null ? 1_000 : toNextM;
            z = d < 300 ? 17.5f : d < 2_000 ? 16.5f : 15.5f;
        }
        if (toNextM != null && toNextM < TURN_NEAR_M) {
            z = Math.max(z, ZOOM_TURN);
        }
        return z;
    }

    static String placeName(com.kooo.evcam.trip.DestinationLink.Place p) {
        if (p == null) {
            return "";
        }
        return p.name.isEmpty() ? String.format(Locale.US, "%.5f, %.5f", p.lat, p.lng) : p.name;
    }

    /** The car: a blue arrow with a white edge, like Google's. */
    private Bitmap carIcon() {
        int size = Math.round(44 * density);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        Path p = new Path();
        p.moveTo(size * 0.5f, size * 0.08f);
        p.lineTo(size * 0.86f, size * 0.9f);
        p.lineTo(size * 0.5f, size * 0.7f);
        p.lineTo(size * 0.14f, size * 0.9f);
        p.close();
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(ROUTE_BLUE);
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setColor(Color.WHITE);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(size * 0.07f);
        edge.setStrokeJoin(Paint.Join.ROUND);
        c.drawPath(p, edge);
        c.drawPath(p, fill);
        return bitmap;
    }
}
