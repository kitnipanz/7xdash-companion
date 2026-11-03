package com.kooo.evcam.nav;

import android.content.Context;
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
 * line is the way to go. While guiding, the camera follows like a phone's navigation, every frame:
 * heading up, tilted, the car (a Zeekr 7X from above) centred left to right and near the bottom so
 * the road ahead fills the map. It aims ahead of the car rather than using map padding, which the
 * MapLibre-based Play services on head units ignore, and checks where the car landed to correct the
 * aim. The zoom follows the speed: close in when slow (side streets and alleys show), further out
 * when fast, close again before a turn; it glides there, quicker coming in than going out. Past the
 * closest the map goes (about 21; the big, low-density head unit screen still shows a lot at that),
 * the map view itself is drawn bigger. Shared
 * by the full screen, the floating window and the HUD map. Main thread.
 */
final class NavMapPainter {

    private static final int ROUTE_BLUE = 0xFF4285F4;
    private static final int ROUTE_EDGE = 0xFF1A5BC4;
    /** While guiding, the car sits this far down the map, centred left to right. */
    static final float CAR_AT = 0.78f;
    private static final float TILT = 50f;
    /** Zoom when crawling and at motorway speed; between them it eases off, staying close at low speeds. */
    private static final float ZOOM_SLOW = 22.0f;
    private static final float ZOOM_FAST = 19.0f;
    /** Up to this speed the map stays at its closest. */
    private static final float SLOW_KMH = 25f;
    private static final float FAST_KMH = 110f;
    /** This close to a turn the map is at least {@link #ZOOM_TURN}, whatever the speed. */
    private static final double TURN_NEAR_M = 250;
    private static final float ZOOM_TURN = 20.0f;
    /** Smoothing times: the car's place and heading, zooming in (slowing down) and out (speeding up). */
    private static final float POS_S = 0.45f;
    private static final float BEARING_S = 0.5f;
    private static final float ZOOM_IN_S = 0.9f;
    private static final float ZOOM_OUT_S = 2.2f;
    /** About 30 camera moves a second: smooth, and light on the head unit (and the HUD). */
    private static final float FRAME_MIN_S = 0.03f;

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
    private int shownCarColor = -1;
    private boolean trafficShown;
    /** Live traffic on the map before guiding (the route preview); the HUD map never shows it. */
    boolean trafficAllowed = true;
    private int shownScheme = -1;
    private boolean shownDark;
    private boolean placed;
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
        // moved by hand: keep the car pointing the right way on screen
        map.setOnCameraMoveListener(this::placeCar);
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
        // padding only clears the cards before guiding; while guiding the camera aims ahead instead
        // (MapLibre-based Play services on head units ignore padding)
        pad(guiding ? 0 : cardsLeft, guiding ? 0 : cardsTop, 0, 0);
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
        float bearing = s.carBearing == null ? (Float.isNaN(goalBearing) ? 0 : goalBearing) : s.carBearing.floatValue();
        int color = new AppConfig(mapView.getContext()).getNavCarColor();
        if (car == null) {
            // not flat, turned against the camera: points the right way on every renderer
            car = map.addMarker(new MarkerOptions().position(at).flat(false).anchor(0.5f, 0.5f).zIndex(3)
                    .icon(BitmapDescriptorFactory.fromBitmap(CarIcon.zeekr7x(density, CarIcon.Color.of(color)))));
            shownCarColor = color;
        } else if (color != shownCarColor) {
            shownCarColor = color;
            car.setIcon(BitmapDescriptorFactory.fromBitmap(CarIcon.zeekr7x(density, CarIcon.Color.of(color))));
        }
        goalLat = at.latitude;
        goalLng = at.longitude;
        goalBearing = bearing;
        goalZoom = guiding ? zoomFor(s.carSpeedKmh, s.progress == null ? null : s.progress.toNext) : 15;
        this.guiding = guiding;
        if (!placed) {
            // straight to the car the first time, not a fly-in from the whole world
            placed = true;
            snapTo(at, bearing, goalZoom, guiding);
        }
        if (following && guiding) {
            startFollowing();
        } else {
            stopFollowing();
            if (following && s.phase == Navigator.Phase.IDLE) {
                map.animateCamera(CameraUpdateFactory.newLatLng(at));
            }
            curLat = at.latitude;
            curLng = at.longitude;
            curBearing = bearing;
            placeCar();
        }
    }

    // ---- following: every frame, eased towards the car ----

    private double goalLat;
    private double goalLng;
    private float goalBearing = Float.NaN;
    private float goalZoom = 15;
    private boolean guiding;
    private double curLat = Double.NaN;
    private double curLng;
    private float curBearing;
    private float curZoom = Float.NaN;
    /** Scales the distance aimed ahead of the car so it lands at {@link #CAR_AT}, learned from where it lands. */
    private float aheadScale = 1f;
    private long lastFrameNanos;
    private boolean ticking;

    private final android.view.Choreographer.FrameCallback frame = new android.view.Choreographer.FrameCallback() {
        @Override
        public void doFrame(long now) {
            if (!ticking) {
                return;
            }
            float dt = lastFrameNanos == 0 ? 0.016f : Math.min(0.1f, (now - lastFrameNanos) / 1e9f);
            if (lastFrameNanos == 0 || dt >= FRAME_MIN_S) {
                lastFrameNanos = now;
                step(dt);
            }
            android.view.Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private void startFollowing() {
        if (!ticking) {
            ticking = true;
            lastFrameNanos = 0;
            android.view.Choreographer.getInstance().postFrameCallback(frame);
        }
    }

    private void stopFollowing() {
        if (ticking) {
            ticking = false;
            android.view.Choreographer.getInstance().removeFrameCallback(frame);
        }
        setViewScale(1f);
    }

    private float maxZoomLevel = Float.NaN;

    private float maxZoom() {
        if (Float.isNaN(maxZoomLevel)) {
            try {
                maxZoomLevel = map.getMaxZoomLevel();
            } catch (RuntimeException e) {
                maxZoomLevel = 21f;
            }
        }
        return maxZoomLevel;
    }

    private float shownScale = 1f;

    private void setViewScale(float scale) {
        if (Math.abs(scale - shownScale) < 0.001f) {
            return;
        }
        shownScale = scale;
        mapView.setPivotX(mapView.getWidth() / 2f);
        mapView.setPivotY(mapView.getHeight() / 2f);
        mapView.setScaleX(scale);
        mapView.setScaleY(scale);
    }

    /** How much bigger to draw the map view for a zoom past what the map goes to. */
    static float viewScale(float zoom, float cameraZoom) {
        return (float) Math.pow(2, Math.max(0f, zoom - cameraZoom));
    }

    /** Where the car must sit in the unscaled view so that, drawn {@code scale} times bigger, it shows at {@link #CAR_AT}. */
    static float carAtInView(float scale) {
        return 0.5f + (CAR_AT - 0.5f) / scale;
    }

    /** The owner is going: no more frames. */
    void release() {
        stopFollowing();
    }

    private void step(float dt) {
        if (Double.isNaN(curLat)) {
            curLat = goalLat;
            curLng = goalLng;
            curBearing = goalBearing;
        }
        if (Float.isNaN(curZoom)) {
            curZoom = goalZoom;
        }
        float pos = ease(dt, POS_S);
        curLat += (goalLat - curLat) * pos;
        curLng += (goalLng - curLng) * pos;
        curBearing = turn(curBearing, goalBearing, ease(dt, BEARING_S));
        // closer in quickly when slowing down, further out gently when speeding up
        curZoom += (goalZoom - curZoom) * ease(dt, goalZoom > curZoom ? ZOOM_IN_S : ZOOM_OUT_S);
        int h = mapView.getHeight();
        // Google stops at about 21: closer than that, the map view itself is drawn bigger
        float camZoom = Math.min(curZoom, maxZoom());
        float scale = viewScale(curZoom, camZoom);
        setViewScale(scale);
        float carAt = carAtInView(scale);
        double ahead = h <= 0 ? 0 : aheadMeters(curLat, camZoom, h, carAt) * aheadScale;
        double[] target = Geo.offset(curLat, curLng, curBearing, ahead);
        map.moveCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder()
                .target(new LatLng(target[0], target[1])).bearing(curBearing).tilt(TILT).zoom(camZoom).build()));
        placeCar();
        learnAhead(h, carAt);
    }

    /** Where the car is drawn now, turned to its heading as the screen shows it. */
    private void placeCar() {
        if (car == null || Double.isNaN(curLat)) {
            return;
        }
        car.setPosition(new LatLng(curLat, curLng));
        car.setRotation(screenTurn(curBearing, map.getCameraPosition().bearing));
    }

    /** Checks where the car landed on the map and nudges the aim, so it settles at {@link #CAR_AT}. */
    private void learnAhead(int h, float carAt) {
        if (h <= 0) {
            return;
        }
        try {
            android.graphics.Point p = map.getProjection().toScreenLocation(new LatLng(curLat, curLng));
            float landed = (float) p.y / h;
            if (landed > 0.5f && landed < 1f) {
                float want = (carAt - 0.5f) / (landed - 0.5f);
                aheadScale = Math.max(0.3f, Math.min(4f, aheadScale * (1f + (want - 1f) * 0.1f)));
            } else if (landed <= 0.5f) {
                aheadScale = Math.min(4f, aheadScale * 1.05f);
            }
        } catch (RuntimeException ignored) {
            // no projection yet
        }
    }

    private void snapTo(LatLng at, float bearing, float zoom, boolean guiding) {
        curLat = at.latitude;
        curLng = at.longitude;
        curBearing = bearing;
        curZoom = zoom;
        map.moveCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder()
                .target(at).bearing(guiding ? bearing : 0).tilt(guiding ? TILT : 0).zoom(zoom).build()));
    }

    /** Share of the way to go this frame, for a smoothing time of {@code seconds}. */
    static float ease(float dt, float seconds) {
        return (float) (1 - Math.exp(-dt / seconds));
    }

    /** From {@code from} towards {@code to} by {@code share}, the short way round. */
    static float turn(float from, float to, float share) {
        float d = ((to - from) % 360 + 540) % 360 - 180;
        return ((from + d * share) % 360 + 360) % 360;
    }

    /** The marker's turn on screen: the heading less the way the map faces. */
    static float screenTurn(float heading, float cameraBearing) {
        return ((heading - cameraBearing) % 360 + 360) % 360;
    }

    /**
     * Metres ahead of the car to aim the camera so the car sits at {@link #CAR_AT} of the map's
     * height: the ground under that many screen pixels at this zoom, stretched by the tilt.
     */
    static double aheadMeters(double lat, float zoom, int heightPx, float carAt) {
        double metresPerPx = 156543.03392 * Math.cos(Math.toRadians(lat)) / Math.pow(2, zoom);
        return (carAt - 0.5) * heightPx * metresPerPx / Math.cos(Math.toRadians(TILT));
    }

    /** Back to following the car, flat and north-up when not guiding. */
    void recenter(Navigator.Snapshot s) {
        following = true;
        curLat = Double.NaN;
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
            // most of the zooming out happens early, so slowing down brings the map in clearly
            float out = 1f - (float) Math.pow(1f - t, 1.6);
            z = ZOOM_SLOW + (ZOOM_FAST - ZOOM_SLOW) * out;
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
}
