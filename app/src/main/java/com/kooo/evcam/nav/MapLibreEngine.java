package com.kooo.evcam.nav;

import android.content.Context;
import android.graphics.PointF;

import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
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
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * TomTom's map drawn by MapLibre ({@link NavMap}): works on every head unit, with or without Play
 * services. The route is GeoJSON on layers of our own over TomTom's style; the style's incident lines
 * hide while guiding.
 */
final class MapLibreEngine implements MapEngine {

    private static final String ROUTE_EDGE = "#1A5BC4";
    private static final String OTHER_ROUTE = "#B4C6E4";
    private static final String OTHER_EDGE = "#8199BF";
    private static final String SPACE_EDGE = "#032026";
    private static final String SPACE_CORE = "#E9FFFD";
    private static final String SPACE_OTHER = "#3A5068";
    private static final String SPACE_OTHER_EDGE = "#16222F";
    /** Our zoom numbers count 256 px tiles; MapLibre's world is 512 px, one level closer. */
    private static final float ZOOM_SHIFT = 1f;
    private static final String COLOR = "color";
    private static final String ROUTE_INDEX = "route";
    private static final String OTHERS_LINE = "nav-others-line";
    /** The map style's source of incidents: icons, and lines along the jammed roads. */
    private static final String INCIDENTS = "vectorTilesIncidents";

    private final MapLibreMap map;
    private final MapView view;
    private final float density;
    private Style style;
    private GeoJsonSource restSource;
    private GeoJsonSource headSource;
    private GeoJsonSource othersSource;
    private GeoJsonSource pinSource;
    private final List<LineLayer> incidentLines = new ArrayList<>();
    private boolean trafficShown = true;
    private String url;
    private boolean space;
    private android.graphics.Bitmap pinIcon;
    private IntConsumer onOther;
    private final List<Runnable> onMove = new ArrayList<>();
    private final List<Runnable> onGesture = new ArrayList<>();
    private final List<Consumer<LatLng>> onLong = new ArrayList<>();

    MapLibreEngine(Context context, MapLibreMap map, MapView view) {
        this.map = map;
        this.view = view;
        density = context.getResources().getDisplayMetrics().density;
        map.setMaxZoomPreference(22);
        map.addOnCameraMoveListener(() -> {
            for (Runnable r : onMove) {
                r.run();
            }
        });
        map.addOnCameraMoveStartedListener(reason -> {
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                for (Runnable r : onGesture) {
                    r.run();
                }
            }
        });
        map.addOnMapClickListener(this::tapRoute);
        map.addOnMapLongClickListener(at -> {
            for (Consumer<LatLng> c : onLong) {
                c.accept(at);
            }
            return !onLong.isEmpty();
        });
    }

    @Override
    public float unitPx() {
        return density * NavMap.SCALE;
    }

    // ---- camera ----

    private static CameraPosition position(LatLng target, float bearing, float tilt, float zoom) {
        return new CameraPosition.Builder().target(target).bearing(bearing).tilt(tilt).zoom(zoom - ZOOM_SHIFT)
                .padding(0, 0, 0, 0).build();
    }

    @Override
    public void moveCamera(LatLng target, float bearing, float tilt, float zoom) {
        map.moveCamera(CameraUpdateFactory.newCameraPosition(position(target, bearing, tilt, zoom)));
    }

    @Override
    public void animateTo(LatLng target) {
        if (MapPace.yielding()) {
            // no glide while the side view shows: one move, not a third of a second of frames
            map.moveCamera(CameraUpdateFactory.newLatLng(target));
        } else {
            map.animateCamera(CameraUpdateFactory.newLatLng(target));
        }
    }

    @Override
    public void animateCamera(LatLng target, float bearing, float tilt, float zoom) {
        map.animateCamera(CameraUpdateFactory.newCameraPosition(position(target, bearing, tilt, zoom)));
    }

    @Override
    public void zoomBy(float levels, int ms) {
        map.animateCamera(CameraUpdateFactory.zoomBy(levels), ms);
    }

    @Override
    public void fitBounds(List<LatLng> points, int left, int top, int edge) {
        LatLngBounds.Builder b = new LatLngBounds.Builder();
        for (LatLng p : points) {
            b.include(p);
        }
        try {
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), left + edge, top + edge, edge, edge));
        } catch (IllegalStateException e) {
            // map not laid out yet
        }
    }

    @Override
    public PointF toScreen(LatLng at) {
        return map.getProjection().toScreenLocation(at);
    }

    @Override
    public float cameraBearing() {
        return (float) map.getCameraPosition().bearing;
    }

    private float maxZoomLevel = Float.NaN;

    @Override
    public float maxZoom() {
        if (Float.isNaN(maxZoomLevel)) {
            try {
                maxZoomLevel = (float) map.getMaxZoomLevel();
            } catch (RuntimeException e) {
                maxZoomLevel = 21f;
            }
        }
        return maxZoomLevel + ZOOM_SHIFT;
    }

    @Override
    public boolean canScaleView() {
        return true;
    }

    @Override
    public void setMaxFps(int fps) {
        view.setMaximumFps(fps);
    }

    // ---- listeners ----

    @Override
    public void addOnCameraMove(Runnable r) {
        onMove.add(r);
    }

    @Override
    public void addOnGestureMove(Runnable r) {
        onGesture.add(r);
    }

    @Override
    public void addOnLongClick(Consumer<LatLng> c) {
        onLong.add(c);
    }

    @Override
    public void setOnOtherRouteTapped(IntConsumer c) {
        onOther = c;
    }

    @Override
    public void setCompassEnabled(boolean on) {
        map.getUiSettings().setCompassEnabled(on);
    }

    @Override
    public void setGesturesEnabled(boolean on) {
        map.getUiSettings().setAllGesturesEnabled(on);
    }

    /** A tap near a grey route chooses it. */
    private boolean tapRoute(LatLng at) {
        if (style == null || onOther == null) {
            return false;
        }
        PointF p = map.getProjection().toScreenLocation(at);
        float slop = 14 * density;
        List<Feature> hit = map.queryRenderedFeatures(new android.graphics.RectF(p.x - slop, p.y - slop, p.x + slop, p.y + slop),
                OTHERS_LINE);
        if (hit.isEmpty() || !hit.get(0).hasProperty(ROUTE_INDEX)) {
            return false;
        }
        onOther.accept(hit.get(0).getNumberProperty(ROUTE_INDEX).intValue());
        return true;
    }

    // ---- drawing ----

    @Override
    public boolean setScheme(boolean dark, boolean nowSpace, Runnable ready) {
        boolean spaceChanged = nowSpace != space;
        space = nowSpace;
        // space and dark share TomTom's style: a change between them reloads it for the other paint
        if (url != null && !spaceChanged && url.equals(NavMap.styleUrl(dark))) {
            return false;
        }
        url = NavMap.styleUrl(dark);
        style = null;
        map.setStyle(new Style.Builder().fromUri(url), loaded -> {
            onStyle(loaded, dark);
            ready.run();
        });
        return true;
    }

    @Override
    public boolean isReady() {
        return style != null;
    }

    /** A new style has no layers of ours: add them. */
    private void onStyle(Style loaded, boolean dark) {
        style = loaded;
        NavMapColors.apply(loaded, dark, space);
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
        pinIcon = null;
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
        trafficShown = true;
    }

    private static LineLayer line(String id, String source, float width, org.maplibre.android.style.layers.PropertyValue<?> color) {
        return new LineLayer(id, source).withProperties(PropertyFactory.lineWidth(width), color,
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND));
    }

    private static Point point(LatLng p) {
        return Point.fromLngLat(p.getLongitude(), p.getLatitude());
    }

    private static List<Point> points(List<LatLng> pts) {
        List<Point> out = new ArrayList<>(pts.size());
        for (LatLng p : pts) {
            out.add(point(p));
        }
        return out;
    }

    @Override
    public void drawRest(List<List<LatLng>> runs, List<String> colors) {
        if (style == null) {
            return;
        }
        List<Feature> features = new ArrayList<>(runs.size());
        for (int i = 0; i < runs.size(); i++) {
            Feature f = Feature.fromGeometry(LineString.fromLngLats(points(runs.get(i))));
            f.addStringProperty(COLOR, colors.get(i));
            features.add(f);
        }
        restSource.setGeoJson(FeatureCollection.fromFeatures(features));
    }

    @Override
    public void drawHead(List<LatLng> pts, String color) {
        if (style == null) {
            return;
        }
        Feature f = Feature.fromGeometry(LineString.fromLngLats(points(pts)));
        f.addStringProperty(COLOR, color);
        headSource.setGeoJson(f);
    }

    @Override
    public void clearLine() {
        if (style == null) {
            return;
        }
        FeatureCollection none = FeatureCollection.fromFeatures(new Feature[0]);
        restSource.setGeoJson(none);
        headSource.setGeoJson(none);
    }

    @Override
    public void drawOthers(List<List<LatLng>> routes) {
        if (style == null) {
            return;
        }
        List<Feature> lines = new ArrayList<>(routes.size());
        for (int i = 0; i < routes.size(); i++) {
            Feature f = Feature.fromGeometry(LineString.fromLngLats(points(routes.get(i))));
            f.addNumberProperty(ROUTE_INDEX, i);
            lines.add(f);
        }
        othersSource.setGeoJson(FeatureCollection.fromFeatures(lines));
    }

    @Override
    public void drawPin(LatLng at, android.graphics.Bitmap icon) {
        if (style == null) {
            return;
        }
        if (at != null && pinIcon != icon) {
            pinIcon = icon;
            style.addImage("nav-pin", icon);
        }
        pinSource.setGeoJson(FeatureCollection.fromFeatures(at == null ? new Feature[0]
                : new Feature[] {Feature.fromGeometry(point(at))}));
    }

    @Override
    public void showTraffic(boolean on) {
        if (style == null || trafficShown == on) {
            return;
        }
        trafficShown = on;
        for (LineLayer layer : incidentLines) {
            layer.setProperties(PropertyFactory.visibility(on ? Property.VISIBLE : Property.NONE));
        }
    }
}
