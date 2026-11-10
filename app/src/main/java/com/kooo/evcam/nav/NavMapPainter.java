package com.kooo.evcam.nav;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.kooo.evcam.AppConfig;

import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Draws a {@link Navigator.Snapshot} on the map ({@link NavMap}): the route, the destination pin, the
 * car, and the camera. The route line is coloured by the traffic on it (blue free, orange slow, red
 * jam); the other routes to choose from are grey and can be tapped. While guiding, the camera follows
 * like a phone's navigation, every frame: heading up, tilted, the car (a Zeekr 7X from above, a view
 * over the map) centred left to right and near the bottom so the road ahead fills the map. The zoom
 * follows the speed: close in when slow (side streets and alleys show), further out when fast, close
 * again before a turn; it glides there, quicker coming in than going out. Shared by the full screen,
 * the floating window and the HUD map. Main thread.
 */
final class NavMapPainter {

    private static final String ROUTE_BLUE = "#4285F4";
    private static final String ROUTE_EDGE = "#1A5BC4";
    private static final String TRAFFIC_SLOW = "#F29900";
    private static final String TRAFFIC_JAM = "#D93025";
    private static final String OTHER_ROUTE = "#B4C6E4";
    private static final String OTHER_EDGE = "#8199BF";
    /** Space scheme: a glowing teal route, amber and hot pink traffic, faint grey choices. */
    private static final String SPACE_FREE = "#22E6D2";
    private static final String SPACE_SLOW = "#FFB020";
    private static final String SPACE_JAM = "#FF3B6B";
    private static final String SPACE_EDGE = "#032026";
    private static final String SPACE_CORE = "#E9FFFD";
    private static final String SPACE_OTHER = "#3A5068";
    private static final String SPACE_OTHER_EDGE = "#16222F";
    /** Space: a lower camera, more of the city ahead, faded into the night at the horizon. */
    private static final float SPACE_TILT = 60f;
    private static final float HORIZON_FADE = 0.38f;
    /** Our zoom numbers count 256 px tiles; MapLibre's world is 512 px, one level closer. */
    private static final float ZOOM_SHIFT = 1f;
    private static final String COLOR = "color";
    private static final String ROUTE_INDEX = "route";
    private static final String OTHERS_LINE = "nav-others-line";
    /** The map style's source of incidents: icons, and lines along the jammed roads. */
    private static final String INCIDENTS = "vectorTilesIncidents";
    /** While guiding, the car sits this far down the map, centred left to right. */
    static final float CAR_AT = 0.78f;
    private static final float TILT = 50f;
    /** Zoom when crawling and at motorway speed; between them it eases off, staying close at low speeds. */
    private static final float ZOOM_SLOW = 20.5f;
    private static final float ZOOM_FAST = 18.5f;
    /** From this width (in the map's own units) the map is at its full size; a narrower window sees further and draws the car smaller. */
    private static final float FULL_WIDTH = 900f;
    private static final float CAR_SCALE_MIN = 0.65f;
    /** Up to this speed the map stays at its closest. */
    private static final float SLOW_KMH = 25f;
    private static final float FAST_KMH = 110f;
    /** This close to a turn the map is at least {@link #ZOOM_TURN}, whatever the speed. */
    private static final double TURN_NEAR_M = 250;
    private static final float ZOOM_TURN = 20.0f;
    /** Smoothing times: the car's place and heading, zooming in (slowing down) and out (speeding up). */
    private static final float POS_S = 0.25f;
    /**
     * Between fixes the car goes on at its speed for at most this long after the fix was measured,
     * then waits for the next (fixes reach the map up to a second late, through Navigator's tick).
     */
    private static final float PREDICT_MAX_S = 2.5f;
    /** No new fix for this long: the car has stopped, whatever speed it last had. */
    private static final float FIXES_STOPPED_S = 3f;
    private static final float BEARING_S = 0.5f;
    private static final float ZOOM_IN_S = 0.9f;
    private static final float ZOOM_OUT_S = 2.2f;

    private final MapLibreMap map;
    private final View mapView;
    private final float density;
    /** Kept clear for the cards on top of the map when not guiding (the search, the trip sheet). */
    private final int cardsLeft;
    private final int cardsTop;

    /** The map's style; null while it loads. The route's layers go on it again after every style change. */
    private Style style;
    private GeoJsonSource restSource;
    private GeoJsonSource headSource;
    private GeoJsonSource othersSource;
    private GeoJsonSource pinSource;
    private Navigator.Snapshot last;
    /** The style's jam lines stay on the road after the car has passed: gone while guiding, the route shows the traffic. */
    private final List<LineLayer> incidentLines = new ArrayList<>();
    private boolean incidentLinesShown = true;
    /** The stretch from the car to the next point of the line, apart so it moves cheaply every frame. */
    private List<Point> head;
    private String headColor = ROUTE_BLUE;
    private boolean hasLine;
    /** The other routes to choose from (grey). */
    private List<NavRoute> shownChoices = new ArrayList<>();
    private final List<NavRoute> otherRoutes = new ArrayList<>();
    /** Told when a grey route is tapped: choose it. */
    java.util.function.Consumer<NavRoute> onRouteTapped;
    private ImageView car;
    private NavRoute shownRoute;
    private com.kooo.evcam.trip.DestinationLink.Place shownPin;
    private int shownCarColor = -1;
    private float shownCarScale = -1;
    private int shownScheme = -1;
    private boolean shownDark;
    private String url;
    /** Showing the space scheme: its own paint on the dark style, a glow on the route, a lower camera. */
    private boolean space;
    private float tilt = TILT;
    /** Space: the night colour fading the far edge of the map. */
    private View horizon;
    /** The HUD's map: its own zoom setting, not the one of Navigation's own map. */
    private boolean onHud;
    /** Zoom step of the zoom buttons and the HUD zoom actions. */
    static final float ZOOM_STEP = 0.5f;
    private boolean placed;
    /** The camera follows the car until the map is moved by hand. */
    boolean following = true;

    NavMapPainter(Context context, MapLibreMap map, View mapView, int cardsLeft, int cardsTop) {
        this.map = map;
        this.mapView = mapView;
        this.cardsLeft = cardsLeft;
        this.cardsTop = cardsTop;
        density = context.getResources().getDisplayMetrics().density;
        map.setMaxZoomPreference(22);
        applyScheme(new AppConfig(context).getNavMapScheme());
        // moved by hand: keep the car pointing the right way on screen
        map.addOnCameraMoveListener(this::placeCar);
        map.addOnMapClickListener(this::tapRoute);
    }

    /** A tap near a grey route chooses it. */
    private boolean tapRoute(LatLng at) {
        if (style == null || onRouteTapped == null || otherRoutes.isEmpty()) {
            return false;
        }
        android.graphics.PointF p = map.getProjection().toScreenLocation(at);
        float slop = 14 * density;
        List<Feature> hit = map.queryRenderedFeatures(new android.graphics.RectF(p.x - slop, p.y - slop, p.x + slop, p.y + slop),
                OTHERS_LINE);
        if (hit.isEmpty() || !hit.get(0).hasProperty(ROUTE_INDEX)) {
            return false;
        }
        int i = hit.get(0).getNumberProperty(ROUTE_INDEX).intValue();
        if (i < 0 || i >= otherRoutes.size()) {
            return false;
        }
        onRouteTapped.accept(otherRoutes.get(i));
        return true;
    }

    /** A new style has no layers of ours: add them, then draw what is on again. */
    private void onStyle(Style loaded) {
        style = loaded;
        NavMapColors.apply(loaded, shownDark, space);
        restSource = new GeoJsonSource("nav-rest");
        headSource = new GeoJsonSource("nav-head");
        // moved every frame: an update that waits for the map's worker leaves the line behind the car
        headSource.setOverrideSynchronousUpdate(true);
        othersSource = new GeoJsonSource("nav-others");
        pinSource = new GeoJsonSource("nav-pin");
        loaded.addSource(restSource);
        loaded.addSource(headSource);
        loaded.addSource(othersSource);
        loaded.addSource(pinSource);
        loaded.addImage("nav-pin", pinIcon());
        loaded.addLayer(line("nav-others-edge", "nav-others", 13f, PropertyFactory.lineColor(space ? SPACE_OTHER_EDGE : OTHER_EDGE)));
        loaded.addLayer(line(OTHERS_LINE, "nav-others", 9f, PropertyFactory.lineColor(space ? SPACE_OTHER : OTHER_ROUTE)));
        Expression color = Expression.toColor(Expression.get(COLOR));
        if (space) {
            // a soft halo of the route's own colour under it: the line glows on the night map
            for (String part : new String[] {"rest", "head"}) {
                loaded.addLayer(line("nav-" + part + "-glow", "nav-" + part, 40f, PropertyFactory.lineColor(color))
                        .withProperties(PropertyFactory.lineBlur(22f), PropertyFactory.lineOpacity(0.45f)));
            }
        }
        String edge = space ? SPACE_EDGE : ROUTE_EDGE;
        loaded.addLayer(line("nav-rest-edge", "nav-rest", 15f, PropertyFactory.lineColor(edge)));
        loaded.addLayer(line("nav-head-edge", "nav-head", 15f, PropertyFactory.lineColor(edge)));
        loaded.addLayer(line("nav-rest-line", "nav-rest", 10f, PropertyFactory.lineColor(color)));
        loaded.addLayer(line("nav-head-line", "nav-head", 10f, PropertyFactory.lineColor(color)));
        if (space) {
            // a bright thin core down the middle, like a lit tube
            for (String part : new String[] {"rest", "head"}) {
                loaded.addLayer(line("nav-" + part + "-core", "nav-" + part, 3f, PropertyFactory.lineColor(SPACE_CORE))
                        .withProperties(PropertyFactory.lineBlur(1.5f), PropertyFactory.lineOpacity(0.75f)));
            }
        }
        loaded.addLayer(new SymbolLayer("nav-pin", "nav-pin").withProperties(
                PropertyFactory.iconImage("nav-pin"),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)));
        incidentLines.clear();
        for (org.maplibre.android.style.layers.Layer layer : loaded.getLayers()) {
            if (layer instanceof LineLayer && INCIDENTS.equals(((LineLayer) layer).getSourceId())) {
                incidentLines.add((LineLayer) layer);
            }
        }
        incidentLinesShown = true;
        shownRoute = null;
        shownPin = null;
        shownChoices = new ArrayList<>();
        hasLine = false;
        trimmedSegment = -1;
        if (last != null) {
            draw(last);
        }
    }

    private static LineLayer line(String id, String source, float width, org.maplibre.android.style.layers.PropertyValue<?> color) {
        return new LineLayer(id, source).withProperties(PropertyFactory.lineWidth(width), color,
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND));
    }

    /** A red drop with a dark dot, its point on the place. */
    private android.graphics.Bitmap pinIcon() {
        int w = Math.round(40 * density);
        int h = Math.round(56 * density);
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(b);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        float cx = w / 2f;
        float r = w / 2f - density * 2;
        float cy = r + density * 2;
        android.graphics.Path drop = new android.graphics.Path();
        drop.addArc(cx - r, cy - r, cx + r, cy + r, 150, 240);
        drop.cubicTo(cx + r * 0.55f, cy + r * 1.1f, cx + r * 0.2f, h - density * 12, cx, h - density);
        drop.cubicTo(cx - r * 0.2f, h - density * 12, cx - r * 0.55f, cy + r * 1.1f, cx - r * 0.87f, cy + r * 0.5f);
        drop.close();
        p.setColor(0xFFEA4335);
        c.drawPath(drop, p);
        p.setStyle(android.graphics.Paint.Style.STROKE);
        p.setStrokeWidth(density * 1.2f);
        p.setColor(0xFFB3261E);
        c.drawPath(drop, p);
        p.setStyle(android.graphics.Paint.Style.FILL);
        p.setColor(0xFF7A1411);
        c.drawCircle(cx, cy, r * 0.36f, p);
        return b;
    }

    /**
     * Light, dark, or the head unit's day / night ({@link AppConfig#NAV_MAP_AUTO}). Auto is read here,
     * not left to the map's own follow-the-system, which does not change in a floating window; the
     * owner calls this again when the head unit switches.
     */
    void applyScheme(int scheme) {
        boolean dark = isDark(mapView.getContext(), scheme);
        boolean nowSpace = scheme == AppConfig.NAV_MAP_SPACE;
        if (scheme != shownScheme || dark != shownDark) {
            shownScheme = scheme;
            shownDark = dark;
            com.kooo.evcam.AppLog.i("NavMapPainter", "map colours: " + (nowSpace ? "space" : dark ? "dark" : "light")
                    + (scheme == AppConfig.NAV_MAP_AUTO ? " (head unit)" : ""));
        }
        boolean spaceChanged = nowSpace != space;
        space = nowSpace;
        tilt = space ? SPACE_TILT : TILT;
        showHorizon();
        // space and dark share TomTom's style: a change between them reloads it for the other paint
        if (url == null || spaceChanged || !url.equals(NavMap.styleUrl(dark))) {
            url = NavMap.styleUrl(dark);
            style = null;
            map.setStyle(new Style.Builder().fromUri(url), this::onStyle);
        }
    }

    /** Space: the night colour over the top of the map, so the far tiles melt into the dark sky. */
    private void showHorizon() {
        if (!space) {
            if (horizon != null) {
                horizon.setVisibility(View.GONE);
            }
            return;
        }
        if (horizon == null) {
            horizon = new View(mapView.getContext());
            horizon.setClickable(false);
            horizon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            horizon.setBackground(new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[] {0xF207101A, 0x9907101A, 0x0007101A}));
            ((ViewGroup) mapView).addView(horizon, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));
            mapView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> sizeHorizon());
            if (car != null) {
                car.bringToFront();
            }
        }
        horizon.setVisibility(View.VISIBLE);
        sizeHorizon();
    }

    private void sizeHorizon() {
        if (horizon == null) {
            return;
        }
        ViewGroup.LayoutParams lp = horizon.getLayoutParams();
        int h = Math.round(mapView.getHeight() * HORIZON_FADE);
        if (lp.height != h) {
            lp.height = h;
            horizon.setLayoutParams(lp);
        }
    }

    /** Whether this scheme means the dark map right now. */
    static boolean isDark(Context context, int scheme) {
        return scheme == AppConfig.NAV_MAP_DARK || scheme == AppConfig.NAV_MAP_SPACE || (scheme == AppConfig.NAV_MAP_AUTO && headUnitNight(context));
    }

    /** The head unit's own night mode (the system's, not this app's theme). */
    static boolean headUnitNight(android.content.Context context) {
        android.content.res.Configuration c = context.getApplicationContext().getResources().getConfiguration();
        return (c.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    void draw(Navigator.Snapshot s) {
        long t0 = android.os.SystemClock.uptimeMillis();
        drawTimed(s);
        com.kooo.evcam.blackbox.SlowStep.check("map draw (" + s.phase + ", "
                + (s.route == null ? 0 : s.route.pointCount()) + " route points)", t0, android.os.SystemClock.uptimeMillis());
    }

    private void drawTimed(Navigator.Snapshot s) {
        last = s;
        if (style == null) {
            return;
        }
        boolean guiding = s.phase == Navigator.Phase.GUIDING;
        if (incidentLinesShown == guiding) {
            incidentLinesShown = !guiding;
            for (LineLayer layer : incidentLines) {
                layer.setProperties(PropertyFactory.visibility(guiding ? Property.NONE : Property.VISIBLE));
            }
        }
        if (s.route != shownRoute) {
            shownRoute = s.route;
            clearLine();
            trimmedSegment = -1;
            if (s.route != null) {
                drawLine(whole(s.route), s.route.traffic.clone());
            }
        }
        drawChoices(s);
        // arrived still counts: the driven route must not come back for the last moments
        trimRoute(s, guiding || s.phase == Navigator.Phase.ARRIVED);
        if (s.destination != shownPin) {
            shownPin = s.destination;
            pinSource.setGeoJson(FeatureCollection.fromFeatures(s.destination == null ? new Feature[0]
                    : new Feature[] {Feature.fromGeometry(Point.fromLngLat(s.destination.lng, s.destination.lat))}));
        }
        if (s.carLat == null) {
            return;
        }
        boolean onRoute = s.progress != null && !s.progress.offRoute && guiding;
        LatLng at = onRoute ? new LatLng(s.progress.lat, s.progress.lng) : new LatLng(s.carLat, s.carLng);
        float bearing = s.carBearing == null ? (Float.isNaN(goalBearing) ? 0 : goalBearing) : s.carBearing.floatValue();
        int color = new AppConfig(mapView.getContext()).getNavCarColor();
        float width = mapView.getWidth() / (density * NavMap.SCALE);
        float carScale = carScale(width);
        if (color != shownCarColor || carScale != shownCarScale || car == null) {
            shownCarColor = color;
            shownCarScale = carScale;
            showCar(CarIcon.zeekr7x(density * NavMap.SCALE * carScale, CarIcon.Color.of(color)));
        }
        long now = android.os.SystemClock.uptimeMillis();
        // when the GPS measured this fix, on the uptime clock: the car is drawn where it is now, not
        // where it was when the fix reached the map
        long fixAt = now;
        if (s.carFixAtMs != null) {
            long age = android.os.SystemClock.elapsedRealtime() - s.carFixAtMs;
            if (age >= 0 && age < 5_000L) {
                fixAt = now - age;
            }
        }
        if (guiding && !Double.isNaN(goalLat) && (at.getLatitude() != goalLat || at.getLongitude() != goalLng)) {
            // how fast the fixes come in: the car keeps that pace between them
            float dt = (fixAt - goalAt) / 1000f;
            if (dt > 0.2f && dt < 5f) {
                float seen = (float) (Geo.meters(goalLat, goalLng, at.getLatitude(), at.getLongitude()) / dt);
                speed = Math.min(60f, speed <= 0 ? seen : speed * 0.5f + seen * 0.5f);
            }
        }
        if (s.carSpeedKmh != null && s.carSpeedKmh >= 0) {
            speed = s.carSpeedKmh / 3.6f;
        }
        if (at.getLatitude() != goalLat || at.getLongitude() != goalLng) {
            goalAt = fixAt;
        }
        goalLat = at.getLatitude();
        goalLng = at.getLongitude();
        // on the route the car goes on along the road between fixes, round the bends, as Google Maps
        // does; off it, straight on
        goalRoute = onRoute ? s.route : null;
        goalAlong = onRoute ? s.progress.along : 0;
        goalBearing = bearing;
        goalZoom = guiding ? zoomFor(s.carSpeedKmh, s.progress == null ? null : s.progress.toNext) + windowZoom(width)
                + zoomBias() : 15;
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
            curLat = at.getLatitude();
            curLng = at.getLongitude();
            curBearing = bearing;
            placeCar();
        }
    }

    // ---- following: every frame, eased towards the car ----

    private double goalLat = Double.NaN;
    private double goalLng;
    /** When the last fix came in (uptime ms), and the car's speed in m/s, measured or from the car. */
    private long goalAt;
    private float speed;
    /** The route the car is on and how far along it the last fix was; null when off it. */
    private NavRoute goalRoute;
    private double goalAlong;
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
    /** This close to where it should land the car is left alone, or a standing map shimmers. */
    private static final float AIM_SETTLED = 0.01f;

    private final android.view.Choreographer.FrameCallback frame = new android.view.Choreographer.FrameCallback() {
        @Override
        public void doFrame(long now) {
            if (!ticking) {
                return;
            }
            boolean yielding = MapPace.yielding();
            if (yielding != paceYielding) {
                setPace(yielding);
            }
            float dt = lastFrameNanos == 0 ? 0.016f : Math.min(0.1f, (now - lastFrameNanos) / 1e9f);
            // a little under the wanted gap: frames come on the screen's beat, not exactly on ours
            if (lastFrameNanos == 0 || dt >= MapPace.frameMinSeconds(yielding) - 0.004f) {
                lastFrameNanos = now;
                step(dt);
            }
            android.view.Choreographer.getInstance().postFrameCallback(this);
        }
    };

    /** The map engine draws at the pace the camera moves: slower while the side view shows ({@link MapPace}). */
    private boolean paceYielding;

    private void setPace(boolean yielding) {
        paceYielding = yielding;
        if (mapView instanceof org.maplibre.android.maps.MapView) {
            ((org.maplibre.android.maps.MapView) mapView).setMaximumFps(MapPace.maxFps(yielding));
        }
    }

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
        if (paceYielding) {
            // nothing checks the pace once the frames stop: back to full before a pinch or a fly-to
            setPace(false);
        }
        setViewScale(1f);
    }

    private float maxZoomLevel = Float.NaN;

    private float maxZoom() {
        if (Float.isNaN(maxZoomLevel)) {
            try {
                maxZoomLevel = (float) map.getMaxZoomLevel();
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

    /** This painter draws the HUD's map: it follows the HUD zoom setting. */
    void forHud() {
        onHud = true;
    }

    /** The zoom setting this map follows while guiding, in zoom levels (+ closer). */
    private float zoomBias() {
        AppConfig config = new AppConfig(mapView.getContext());
        return onHud ? config.getHudZoomBias() : config.getNavZoomBias();
    }

    /**
     * A zoom button: while guiding it moves the map's own zoom closer or further, and remembers
     * that; otherwise it zooms the map as a pinch would.
     */
    void zoomBy(float levels) {
        if (guiding && following) {
            AppConfig config = new AppConfig(mapView.getContext());
            float before = zoomBias();
            float after = AppConfig.clampZoomBias(before + levels);
            if (onHud) {
                config.setHudZoomBias(after);
            } else {
                config.setNavZoomBias(after);
            }
            goalZoom += after - before;
            return;
        }
        map.animateCamera(CameraUpdateFactory.zoomBy(levels), 250);
    }

    /** The owner is going: no more frames. */
    void release() {
        stopFollowing();
        if (car != null && car.getParent() instanceof ViewGroup) {
            ((ViewGroup) car.getParent()).removeView(car);
        }
        car = null;
    }

    /** The car as a view above the map's own drawing, so the route always runs under it. */
    private void showCar(android.graphics.Bitmap icon) {
        if (car == null) {
            car = new ImageView(mapView.getContext());
            car.setScaleType(ImageView.ScaleType.FIT_XY);
            car.setClickable(false);
            car.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            ((ViewGroup) mapView).addView(car, new FrameLayout.LayoutParams(icon.getWidth(), icon.getHeight()));
        } else {
            ViewGroup.LayoutParams size = car.getLayoutParams();
            size.width = icon.getWidth();
            size.height = icon.getHeight();
            car.setLayoutParams(size);
        }
        car.setImageBitmap(icon);
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
        // where the car is by now: the last fix, carried on at its speed (at most a second and a half)
        float age = (android.os.SystemClock.uptimeMillis() - goalAt) / 1000f;
        if (age > FIXES_STOPPED_S) {
            speed = 0;
        }
        float since = Math.min(PREDICT_MAX_S, age);
        float bearingNow = goalBearing;
        double[] predicted;
        if (speed <= 0.5f) {
            predicted = new double[] {goalLat, goalLng};
        } else if (goalRoute != null) {
            predicted = goalRoute.pointAt(goalAlong + speed * since);
            bearingNow = (float) predicted[2];
        } else {
            predicted = Geo.offset(goalLat, goalLng, goalBearing, speed * since);
        }
        float pos = ease(dt, POS_S);
        curLat += (predicted[0] - curLat) * pos;
        curLng += (predicted[1] - curLng) * pos;
        curBearing = turn(curBearing, bearingNow, ease(dt, BEARING_S));
        // closer in quickly when slowing down, further out gently when speeding up
        curZoom += (goalZoom - curZoom) * ease(dt, goalZoom > curZoom ? ZOOM_IN_S : ZOOM_OUT_S);
        int h = mapView.getHeight();
        float camZoom = Math.min(curZoom, maxZoom() + ZOOM_SHIFT);
        float scale = viewScale(curZoom, camZoom);
        setViewScale(scale);
        float carAt = carAtInView(scale);
        double ahead = h <= 0 ? 0 : aheadMeters(curLat, camZoom, h / (density * NavMap.SCALE), carAt, tilt) * aheadScale;
        double[] target = Geo.offset(curLat, curLng, curBearing, ahead);
        map.moveCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder()
                .target(new LatLng(target[0], target[1])).bearing(curBearing).tilt(tilt).zoom(camZoom - ZOOM_SHIFT)
                .padding(0, 0, 0, 0).build()));
        placeCar();
        lineToCar();
        learnAhead(h, carAt);
    }

    /** Where the car is drawn now, turned to its heading as the screen shows it. */
    private void placeCar() {
        if (car == null || Double.isNaN(curLat)) {
            return;
        }
        try {
            android.graphics.PointF p = map.getProjection().toScreenLocation(new LatLng(curLat, curLng));
            ViewGroup.LayoutParams size = car.getLayoutParams();
            car.setTranslationX(p.x - size.width / 2f);
            car.setTranslationY(p.y - size.height / 2f);
        } catch (RuntimeException ignored) {
            // no projection yet
        }
        car.setRotation(screenTurn(curBearing, (float) map.getCameraPosition().bearing));
        // the map view may be drawn bigger: the car stays its own size
        car.setScaleX(1f / shownScale);
        car.setScaleY(1f / shownScale);
    }

    /** Checks where the car landed on the map and nudges the aim, so it settles at {@link #CAR_AT}. */
    private void learnAhead(int h, float carAt) {
        if (h <= 0) {
            return;
        }
        try {
            android.graphics.PointF p = map.getProjection().toScreenLocation(new LatLng(curLat, curLng));
            float landed = (float) p.y / h;
            if (landed > 0.5f && landed < 1f) {
                float want = (carAt - 0.5f) / (landed - 0.5f);
                if (Math.abs(want - 1f) < AIM_SETTLED) {
                    return;
                }
                aheadScale = Math.max(0.3f, Math.min(4f, aheadScale * (1f + (want - 1f) * 0.1f)));
            } else if (landed <= 0.5f) {
                aheadScale = Math.min(4f, aheadScale * 1.05f);
            }
        } catch (RuntimeException ignored) {
            // no projection yet
        }
    }

    private void snapTo(LatLng at, float bearing, float zoom, boolean guiding) {
        curLat = at.getLatitude();
        curLng = at.getLongitude();
        curBearing = bearing;
        curZoom = zoom;
        map.moveCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder()
                .target(at).bearing(guiding ? bearing : 0).tilt(guiding ? tilt : 0).zoom(zoom - ZOOM_SHIFT)
                .padding(0, 0, 0, 0).build()));
    }

    /** How much further out a narrow window sits: enough road ahead shows in the floating window and on the HUD. */
    static float windowZoom(float width) {
        return width <= 0 || width >= FULL_WIDTH ? 0f : (float) (-0.5 * Math.log(FULL_WIDTH / width) / Math.log(2));
    }

    /** The car's size in a window this wide, so it stays in proportion to the map around it. */
    static float carScale(float width) {
        return width <= 0 ? 1f : Math.max(CAR_SCALE_MIN, Math.min(1f, width / FULL_WIDTH));
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
    static double aheadMeters(double lat, float zoom, float heightPx, float carAt) {
        return aheadMeters(lat, zoom, heightPx, carAt, TILT);
    }

    static double aheadMeters(double lat, float zoom, float heightPx, float carAt, float tilt) {
        double metresPerPx = 156543.03392 * Math.cos(Math.toRadians(lat)) / Math.pow(2, zoom);
        return (carAt - 0.5) * heightPx * metresPerPx / Math.cos(Math.toRadians(tilt));
    }

    /**
     * The route still to drive: from the car's place on the line ({@code lat, lng} on segment
     * {@code segment}) to the end. Behind the car the line is gone, like a phone's navigation.
     */
    static List<LatLng> ahead(NavRoute r, int segment, double lat, double lng) {
        int from = Math.max(0, Math.min(segment + 1, r.pointCount() - 1));
        List<LatLng> pts = new ArrayList<>(r.pointCount() - from + 1);
        pts.add(new LatLng(lat, lng));
        for (int i = from; i < r.pointCount(); i++) {
            pts.add(new LatLng(r.lat(i), r.lng(i)));
        }
        return pts;
    }

    /** The line the car is on and where on it, last drawn; -1 = the whole route. */
    private int trimmedSegment = -1;
    private double trimmedLat;
    private double trimmedLng;

    /**
     * While guiding, the route line starts at the car: the part already driven goes. Redrawn when
     * the car moves on along the line; the whole route again before guiding.
     */
    private void trimRoute(Navigator.Snapshot s, boolean guiding) {
        if (!hasLine || s.route == null) {
            trimmedSegment = -1;
            return;
        }
        Guidance.Progress p = s.progress;
        if (!guiding || p == null || p.offRoute) {
            if (trimmedSegment != -1 && !guiding) {
                drawLine(whole(s.route), s.route.traffic.clone());
                trimmedSegment = -1;
            }
            return;
        }
        // on the same piece of road the line's start only moves, which lineToCar does while following
        if (p.segment == trimmedSegment && (ticking || Geo.meters(p.lat, p.lng, trimmedLat, trimmedLng) < 3)) {
            return;
        }
        trimmedSegment = p.segment;
        trimmedLat = p.lat;
        trimmedLng = p.lng;
        drawLine(ahead(s.route, p.segment, p.lat, p.lng), aheadTraffic(s.route, p.segment));
    }

    private static List<LatLng> whole(NavRoute r) {
        List<LatLng> pts = new ArrayList<>(r.pointCount());
        for (int i = 0; i < r.pointCount(); i++) {
            pts.add(new LatLng(r.lat(i), r.lng(i)));
        }
        return pts;
    }

    /** The traffic on each piece of {@link #ahead}'s line: the car's own piece, then the route's. */
    static byte[] aheadTraffic(NavRoute r, int segment) {
        int from = Math.max(0, Math.min(segment + 1, r.pointCount() - 1));
        byte[] out = new byte[r.pointCount() - from];
        if (out.length == 0 || r.traffic.length == 0) {
            return out;
        }
        out[0] = r.traffic[Math.max(0, Math.min(segment, r.traffic.length - 1))];
        for (int k = 1; k < out.length; k++) {
            out[k] = r.traffic[Math.min(from + k - 1, r.traffic.length - 1)];
        }
        return out;
    }

    /** The colour of a stretch: blue when free, orange when slow, red in a jam, like Google Maps. */
    static String trafficColor(byte level) {
        return level == NavRoute.JAM ? TRAFFIC_JAM : level == NavRoute.SLOW ? TRAFFIC_SLOW : ROUTE_BLUE;
    }

    /** The traffic colour in the scheme shown: neon on the space map. */
    private String color(byte level) {
        if (!space) {
            return trafficColor(level);
        }
        return level == NavRoute.JAM ? SPACE_JAM : level == NavRoute.SLOW ? SPACE_SLOW : SPACE_FREE;
    }

    /** The line through {@code pts}, coloured by {@code levels} (one per piece between two points). */
    private void drawLine(List<LatLng> pts, byte[] levels) {
        if (pts.size() < 2) {
            clearLine();
            return;
        }
        hasLine = true;
        head = new ArrayList<>();
        head.add(point(pts.get(0)));
        head.add(point(pts.get(1)));
        headColor = color(levels.length > 0 ? levels[0] : NavRoute.FREE);
        drawHead();
        List<Feature> runs = new ArrayList<>();
        int i = 1;
        while (i < pts.size() - 1) {
            byte level = i < levels.length ? levels[i] : NavRoute.FREE;
            int j = i;
            while (j < pts.size() - 1 && (j < levels.length ? levels[j] : NavRoute.FREE) == level) {
                j++;
            }
            List<Point> run = new ArrayList<>(j - i + 1);
            for (int k = i; k <= j; k++) {
                run.add(point(pts.get(k)));
            }
            Feature f = Feature.fromGeometry(LineString.fromLngLats(run));
            f.addStringProperty(COLOR, color(level));
            runs.add(f);
            i = j;
        }
        restSource.setGeoJson(FeatureCollection.fromFeatures(runs));
    }

    private static Point point(LatLng p) {
        return Point.fromLngLat(p.getLongitude(), p.getLatitude());
    }

    private void drawHead() {
        Feature f = Feature.fromGeometry(LineString.fromLngLats(head));
        f.addStringProperty(COLOR, headColor);
        headSource.setGeoJson(f);
    }

    private void clearLine() {
        hasLine = false;
        head = null;
        FeatureCollection none = FeatureCollection.fromFeatures(new Feature[0]);
        restSource.setGeoJson(none);
        headSource.setGeoJson(none);
    }

    /** While guiding: the line's start sits right under the car as it moves, every frame. */
    private void lineToCar() {
        if (trimmedSegment < 0 || head == null) {
            return;
        }
        Point here = Point.fromLngLat(curLng, curLat);
        if (here.equals(head.get(0))) {
            return;
        }
        head.set(0, here);
        drawHead();
    }

    /** Before starting: the other routes in grey, under the chosen one; a tap on one chooses it. */
    private void drawChoices(Navigator.Snapshot s) {
        List<NavRoute> want = s.phase == Navigator.Phase.PREVIEW && s.choices.size() > 1 ? s.choices : new ArrayList<>();
        if (want.equals(shownChoices) && !otherRoutes.contains(s.route)) {
            return;
        }
        shownChoices = new ArrayList<>(want);
        otherRoutes.clear();
        List<Feature> lines = new ArrayList<>();
        for (NavRoute r : want) {
            if (r == s.route) {
                continue;
            }
            List<Point> pts = new ArrayList<>(r.pointCount());
            for (int i = 0; i < r.pointCount(); i++) {
                pts.add(Point.fromLngLat(r.lng(i), r.lat(i)));
            }
            Feature f = Feature.fromGeometry(LineString.fromLngLats(pts));
            f.addNumberProperty(ROUTE_INDEX, otherRoutes.size());
            lines.add(f);
            otherRoutes.add(r);
        }
        othersSource.setGeoJson(FeatureCollection.fromFeatures(lines));
    }

    /** Back to following the car, flat and north-up when not guiding. */
    void recenter(Navigator.Snapshot s) {
        following = true;
        curLat = Double.NaN;
        if (s.phase != Navigator.Phase.GUIDING && s.carLat != null) {
            map.animateCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder()
                    .target(new LatLng(s.carLat, s.carLng)).zoom(15 - ZOOM_SHIFT).tilt(0).bearing(0)
                    .padding(0, 0, 0, 0).build()));
        }
        draw(s);
    }

    void fitRoute(NavRoute r) {
        if (r == null || r.pointCount() < 2) {
            return;
        }
        LatLngBounds.Builder b = new LatLngBounds.Builder();
        for (int i = 0; i < r.pointCount(); i++) {
            b.include(new LatLng(r.lat(i), r.lng(i)));
        }
        placed = true;
        int edge = Math.round(48 * density);
        try {
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), cardsLeft + edge, cardsTop + edge, edge, edge));
        } catch (IllegalStateException e) {
            // map not laid out yet
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
            z = d < 300 ? 20.0f : d < 2_000 ? 19.5f : 19.0f;
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
