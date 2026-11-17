package com.kooo.evcam.nav;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PointF;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.JointType;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MapStyleOptions;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.gms.maps.model.RoundCap;
import com.kooo.evcam.R;

import org.maplibre.android.geometry.LatLng;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Google's map through the Maps SDK, where Google Play services or microG is on the head unit
 * ({@link MapHost}). The route is polylines; Google's own traffic layer stands in for TomTom's
 * incidents. The map can't be drawn bigger than its plain size, so the zoom is moved up by
 * {@link NavMap#SCALE} instead.
 *
 * <p>The map's code may be microG's, running in this process: any call can throw (an Error too, such
 * as a class missing from its maps package). Every call is guarded; the first failure turns the
 * engine off (all later calls do nothing) and tells {@link MapHost}, which puts TomTom's map in its place.
 */
final class GoogleEngine implements MapEngine {

    private static final String ROUTE_EDGE = "#1A5BC4";
    private static final String OTHER_ROUTE = "#B4C6E4";
    private static final String OTHER_EDGE = "#8199BF";
    private static final String SPACE_EDGE = "#032026";
    private static final String SPACE_CORE = "#E9FFFD";
    private static final String SPACE_OTHER = "#3A5068";
    private static final String SPACE_OTHER_EDGE = "#16222F";
    /** Our zoom is for a map drawn {@link NavMap#SCALE} times bigger: the same ground shows this much closer in Google's. */
    private static final float ZOOM_SHIFT = (float) (Math.log(NavMap.SCALE) / Math.log(2));

    private final Context context;
    private final GoogleMap map;
    private final float density;
    private Boolean shownDark;
    private boolean space;
    private boolean padded;
    private final List<Polyline> rest = new ArrayList<>();
    private final List<Polyline> head = new ArrayList<>();
    private final List<Polyline> others = new ArrayList<>();
    private Marker pin;
    private android.graphics.Bitmap pinIcon;
    private IntConsumer onOther;
    private final List<Runnable> onMove = new ArrayList<>();
    private final List<Runnable> onGesture = new ArrayList<>();
    private final List<Consumer<LatLng>> onLong = new ArrayList<>();
    /** Told once, on the first failure. */
    private final Consumer<Throwable> onBroken;
    private boolean broken;

    GoogleEngine(Context context, GoogleMap map, Consumer<Throwable> onBroken) {
        this.context = context;
        this.map = map;
        this.onBroken = onBroken;
        density = context.getResources().getDisplayMetrics().density;
        map.setMaxZoomPreference(22);
        map.setBuildingsEnabled(true);
        map.setIndoorEnabled(false);
        map.setTrafficEnabled(true);
        map.getUiSettings().setMapToolbarEnabled(false);
        map.getUiSettings().setZoomControlsEnabled(false);
        map.getUiSettings().setMyLocationButtonEnabled(false);
        map.getUiSettings().setIndoorLevelPickerEnabled(false);
        map.setOnCameraMoveListener(() -> {
            for (Runnable r : onMove) {
                r.run();
            }
        });
        map.setOnCameraMoveStartedListener(reason -> {
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                for (Runnable r : onGesture) {
                    r.run();
                }
            }
        });
        map.setOnMapLongClickListener(at -> {
            for (Consumer<LatLng> c : onLong) {
                c.accept(new LatLng(at.latitude, at.longitude));
            }
        });
        map.setOnPolylineClickListener(line -> {
            if (onOther != null && line.getTag() instanceof Integer) {
                onOther.accept((Integer) line.getTag());
            }
        });
    }

    /** The map failed: no more calls into it. */
    private void broke(Throwable t) {
        if (broken) {
            return;
        }
        broken = true;
        com.kooo.evcam.AppLog.w("GoogleEngine", "Google map failed: " + t);
        onBroken.accept(t);
    }

    /** Runs a call into the map unless it has failed; a failure turns the engine off. */
    private void guard(Runnable call) {
        if (broken) {
            return;
        }
        try {
            call.run();
        } catch (Throwable t) {
            broke(t);
        }
    }

    boolean isBroken() {
        return broken;
    }

    private static com.google.android.gms.maps.model.LatLng g(LatLng p) {
        return new com.google.android.gms.maps.model.LatLng(p.getLatitude(), p.getLongitude());
    }

    private static List<com.google.android.gms.maps.model.LatLng> g(List<LatLng> pts) {
        List<com.google.android.gms.maps.model.LatLng> out = new ArrayList<>(pts.size());
        for (LatLng p : pts) {
            out.add(g(p));
        }
        return out;
    }

    @Override
    public float unitPx() {
        return density * NavMap.SCALE;
    }

    // ---- camera ----

    /** The cards' room from {@link #fitBounds} goes once the camera is placed some other way. */
    private void unpad() {
        if (padded) {
            padded = false;
            map.setPadding(0, 0, 0, 0);
        }
    }

    private static CameraPosition position(LatLng target, float bearing, float tilt, float zoom) {
        return new CameraPosition(g(target), zoom + ZOOM_SHIFT, Math.max(0f, Math.min(90f, tilt)),
                ((bearing % 360) + 360) % 360);
    }

    @Override
    public void moveCamera(LatLng target, float bearing, float tilt, float zoom) {
        guard(() -> {
            unpad();
            map.moveCamera(CameraUpdateFactory.newCameraPosition(position(target, bearing, tilt, zoom)));
        });
    }

    @Override
    public void animateTo(LatLng target) {
        guard(() -> {
            unpad();
            if (MapPace.yielding()) {
                map.moveCamera(CameraUpdateFactory.newLatLng(g(target)));
            } else {
                map.animateCamera(CameraUpdateFactory.newLatLng(g(target)));
            }
        });
    }

    @Override
    public void animateCamera(LatLng target, float bearing, float tilt, float zoom) {
        guard(() -> {
            unpad();
            map.animateCamera(CameraUpdateFactory.newCameraPosition(position(target, bearing, tilt, zoom)));
        });
    }

    @Override
    public void zoomBy(float levels, int ms) {
        guard(() -> {
            map.animateCamera(CameraUpdateFactory.zoomBy(levels), ms, null);
        });
    }

    @Override
    public void fitBounds(List<LatLng> points, int left, int top, int edge) {
        guard(() -> {
            LatLngBounds.Builder b = new LatLngBounds.Builder();
            for (LatLng p : points) {
                b.include(g(p));
            }
            try {
                map.setPadding(left, top, 0, 0);
                padded = left != 0 || top != 0;
                map.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), edge));
            } catch (IllegalStateException e) {
                // map not laid out yet
                unpad();
            }
        });
    }

    @Override
    public PointF toScreen(LatLng at) {
        if (broken) {
            throw new IllegalStateException("Google map off");
        }
        try {
            android.graphics.Point p = map.getProjection().toScreenLocation(g(at));
            return new PointF(p.x, p.y);
        } catch (RuntimeException e) {
            // no projection yet: the caller tries again
            throw e;
        } catch (Throwable t) {
            broke(t);
            throw new IllegalStateException(t);
        }
    }

    @Override
    public float cameraBearing() {
        if (broken) {
            return 0f;
        }
        try {
            return map.getCameraPosition().bearing;
        } catch (Throwable t) {
            broke(t);
            return 0f;
        }
    }

    @Override
    public float maxZoom() {
        if (broken) {
            return 20f;
        }
        try {
            return map.getMaxZoomLevel() - ZOOM_SHIFT;
        } catch (Throwable t) {
            broke(t);
            return 20f;
        }
    }

    @Override
    public boolean canScaleView() {
        // its map may be a SurfaceView, which a view scale doesn't reach
        return false;
    }

    @Override
    public void setMaxFps(int fps) {
        // the Maps SDK has no frame cap
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
        guard(() -> {
            map.getUiSettings().setCompassEnabled(on);
        });
    }

    @Override
    public void setGesturesEnabled(boolean on) {
        guard(() -> {
            map.getUiSettings().setAllGesturesEnabled(on);
        });
    }

    // ---- drawing ----

    @Override
    public boolean setScheme(boolean dark, boolean nowSpace, Runnable ready) {
        if (shownDark != null && shownDark == dark && space == nowSpace) {
            return false;
        }
        if (broken) {
            return false;
        }
        shownDark = dark;
        space = nowSpace;
        guard(() -> {
            try {
                map.setMapStyle(dark ? MapStyleOptions.loadRawResourceStyle(context, R.raw.gmap_night) : null);
            } catch (RuntimeException e) {
                com.kooo.evcam.AppLog.w("GoogleEngine", "map style refused: " + e.getMessage());
            }
            // the route's paint changes with the scheme: the painter draws it all again
            removeAll(rest);
            removeAll(head);
            removeAll(others);
            if (pin != null) {
                pin.remove();
                pin = null;
            }
        });
        if (broken) {
            return false;
        }
        ready.run();
        return true;
    }

    @Override
    public boolean isReady() {
        return shownDark != null && !broken;
    }

    private static void removeAll(List<Polyline> lines) {
        for (Polyline l : lines) {
            l.remove();
        }
        lines.clear();
    }

    private Polyline line(List<com.google.android.gms.maps.model.LatLng> pts, float width, int color, float z) {
        return map.addPolyline(new PolylineOptions().addAll(pts).width(width * unitPx()).color(color).zIndex(z)
                .startCap(new RoundCap()).endCap(new RoundCap()).jointType(JointType.ROUND).geodesic(false));
    }

    private static int withAlpha(int color, float alpha) {
        return (Math.round(alpha * 255) << 24) | (color & 0xFFFFFF);
    }

    /** One stretch of the route: its edge, its colour, and on the space map a glow and a bright core. */
    private void addRoute(List<Polyline> into, List<LatLng> pts, String colorHex) {
        List<com.google.android.gms.maps.model.LatLng> g = g(pts);
        int color = Color.parseColor(colorHex);
        if (space) {
            into.add(line(g, 28f, withAlpha(color, 0.3f), 3));
        }
        into.add(line(g, 15f, Color.parseColor(space ? SPACE_EDGE : ROUTE_EDGE), 4));
        into.add(line(g, 10f, color, 5));
        if (space) {
            into.add(line(g, 3f, withAlpha(Color.parseColor(SPACE_CORE), 0.75f), 6));
        }
    }

    @Override
    public void drawRest(List<List<LatLng>> runs, List<String> colors) {
        guard(() -> {
            removeAll(rest);
            for (int i = 0; i < runs.size(); i++) {
                addRoute(rest, runs.get(i), colors.get(i));
            }
        });
    }

    /** The head moves every frame: its lines keep their paint and only take the new points. */
    private String headColor;

    @Override
    public void drawHead(List<LatLng> pts, String color) {
        guard(() -> {
            if (!head.isEmpty() && color.equals(headColor)) {
                List<com.google.android.gms.maps.model.LatLng> g = g(pts);
                for (Polyline l : head) {
                    l.setPoints(g);
                }
                return;
            }
            removeAll(head);
            headColor = color;
            addRoute(head, pts, color);
        });
    }

    @Override
    public void clearLine() {
        guard(() -> {
            removeAll(rest);
            removeAll(head);
        });
    }

    @Override
    public void drawOthers(List<List<LatLng>> routes) {
        guard(() -> {
            removeAll(others);
            int edge = Color.parseColor(space ? SPACE_OTHER_EDGE : OTHER_EDGE);
            int fill = Color.parseColor(space ? SPACE_OTHER : OTHER_ROUTE);
            for (int i = 0; i < routes.size(); i++) {
                List<com.google.android.gms.maps.model.LatLng> g = g(routes.get(i));
                others.add(line(g, 13f, edge, 1));
                Polyline top = line(g, 9f, fill, 2);
                top.setClickable(true);
                top.setTag(i);
                others.add(top);
            }
        });
    }

    @Override
    public void drawPin(LatLng at, android.graphics.Bitmap icon) {
        guard(() -> {
            if (at == null) {
                if (pin != null) {
                    pin.remove();
                    pin = null;
                }
                return;
            }
            if (pin == null || pinIcon != icon) {
                if (pin != null) {
                    pin.remove();
                }
                pinIcon = icon;
                pin = map.addMarker(new MarkerOptions().position(g(at)).anchor(0.5f, 1f).zIndex(10)
                        .icon(BitmapDescriptorFactory.fromBitmap(icon)));
            } else {
                pin.setPosition(g(at));
            }
        });
    }

    @Override
    public void showTraffic(boolean on) {
        guard(() -> {
            if (map.isTrafficEnabled() != on) {
                map.setTrafficEnabled(on);
            }
        });
    }
}
