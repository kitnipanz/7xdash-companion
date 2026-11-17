package com.kooo.evcam.nav;

import android.graphics.PointF;

import org.maplibre.android.geometry.LatLng;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * One map engine under {@link NavMapPainter}: TomTom's map drawn by MapLibre ({@link MapLibreEngine})
 * or Google's map through Play services or microG ({@link GoogleEngine}). The painter does the
 * following, the zoom and the car; the engine moves the camera and draws the route's lines and the
 * pin. {@link LatLng} is only a value here, whichever engine draws.
 *
 * <p>Zoom levels are the painter's own: 256 px tiles, with the map drawn {@link NavMap#SCALE} times
 * bigger than its plain size. Each engine turns them into its own. Main thread.
 */
interface MapEngine {

    /** How many screen pixels one unit of the map's own size is (the density, times {@link NavMap#SCALE}). */
    float unitPx();

    // ---- camera ----

    void moveCamera(LatLng target, float bearing, float tilt, float zoom);

    void animateTo(LatLng target);

    void animateCamera(LatLng target, float bearing, float tilt, float zoom);

    void zoomBy(float levels, int ms);

    /** The route in view, kept clear of {@code left} / {@code top} (the cards) and an edge all round. */
    void fitBounds(List<LatLng> points, int left, int top, int edge);

    /** Where a place is on the map view, in pixels; throws while the map has no size yet. */
    PointF toScreen(LatLng at);

    float cameraBearing();

    float maxZoom();

    /** Whether the map view can be drawn bigger (scaled) for a zoom past {@link #maxZoom()}. */
    boolean canScaleView();

    void setMaxFps(int fps);

    // ---- listeners (each kind may have several) ----

    void addOnCameraMove(Runnable r);

    /** The map was moved by a finger. */
    void addOnGestureMove(Runnable r);

    void addOnLongClick(java.util.function.Consumer<LatLng> c);

    /** A tap on one of the other routes: its index in the last {@link #drawOthers}. */
    void setOnOtherRouteTapped(IntConsumer c);

    void setCompassEnabled(boolean on);

    void setGesturesEnabled(boolean on);

    // ---- drawing ----

    /**
     * Light or dark, and the space look. Returns true when the map loads anew: everything drawn is
     * gone and {@code ready} runs once it is back, to draw again. False: nothing changed.
     */
    boolean setScheme(boolean dark, boolean space, Runnable ready);

    /** Whether the map can be drawn on (its style is loaded). */
    boolean isReady();

    /** The route still to drive, in runs of one traffic colour each. */
    void drawRest(List<List<LatLng>> runs, List<String> colors);

    /** The stretch from the car to the next point; moved every frame while guiding. */
    void drawHead(List<LatLng> points, String color);

    void clearLine();

    /** The other routes to choose from, grey; their index is what a tap reports. */
    void drawOthers(List<List<LatLng>> routes);

    /** The destination pin, or none. */
    void drawPin(LatLng at, android.graphics.Bitmap icon);

    /** The map's own traffic and incidents: off while guiding, the route shows the traffic. */
    void showTraffic(boolean on);
}
