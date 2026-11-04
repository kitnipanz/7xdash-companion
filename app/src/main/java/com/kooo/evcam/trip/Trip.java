package com.kooo.evcam.trip;

import android.content.Context;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;
import com.kooo.evcam.telemetry.VehicleState;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Battery at arrival (beta). The phone shares a place over the car's Wi-Fi
 * ({@link DestinationServer}); this keeps that destination, asks for the road km now and then
 * ({@link RouteLookup}) and turns battery %, range and km left into an {@link Arrival.Estimate}
 * for the Dash panel, the Home card and {@link ArrivalActivity}. CarPlay keeps its route on the
 * phone, so the phone has to tell us where it is going.
 *
 * <p>Runs only while Settings → Beta features is on and the feature's switch is on. While a
 * destination is set it keeps the full telemetry (GPS and float sensors) running.</p>
 */
public final class Trip implements Telemetry.Listener {

    private static final String TAG = "Trip";
    private static final String TELEMETRY_USER = "arrival";
    /** Ask for a fresh route this often anyway (traffic changes the road Google picks). */
    private static final long REROUTE_MS = 5 * 60_000L;
    /** Further than this from the route line (metres) and the car is off it... */
    private static final double OFF_ROUTE_M = 60.0;
    /** ...for this many position fixes in a row: it took another road, ask for a new route... */
    private static final int OFF_ROUTE_FIXES = 3;
    /** ...but not sooner than this after the last ask. */
    private static final long REROUTE_MIN_MS = 15_000L;
    /** After an ask that got no answer, try again after this. */
    private static final long RETRY_MS = 30_000L;
    /** Move this far (metres) before taking the direction of travel from two positions. */
    private static final double HEADING_MOVED_M = 15.0;
    /** Inside this, we are there: the destination clears itself. */
    private static final double ARRIVED_KM = 0.25;
    /** A destination older than this is from another day: dropped on start. */
    private static final long STALE_MS = 18 * 3600_000L;
    /** GPS alone does not wake the listener (only car readings do): look again this often too. */
    private static final long TICK_MS = 5_000L;

    private static final Trip INSTANCE = new Trip();

    public static Trip get() {
        return INSTANCE;
    }

    /** What the screens show. */
    public static final class Status {
        public final DestinationLink.Place destination;
        /** Null until the car has given a position, battery and range. */
        public final Arrival.Estimate estimate;
        /** True until the route service has answered: km left is straight line x 1.3. */
        public final boolean roughKm;
        /** True when the route is Google's (the same road and km Google Maps shows). */
        public final boolean google;

        Status(DestinationLink.Place destination, Arrival.Estimate estimate, boolean roughKm, boolean google) {
            this.destination = destination;
            this.estimate = estimate;
            this.roughKm = roughKm;
            this.google = google;
        }
    }

    private final Object lock = new Object();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "trip");
        t.setDaemon(true);
        return t;
    });
    private final RouteLookup route = new RouteLookup();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            onReadingsChanged(null);
            main.postDelayed(this, TICK_MS);
        }
    };

    private Context app;
    private DestinationServer server;
    private boolean listening;
    private DestinationLink.Place destination;
    /** The road to the destination; null until the route service has answered. */
    private RouteLine line;
    private boolean lineFromGoogle;
    /** The segment of the line the car was last matched to, -1 for none yet. */
    private int lineSegment = -1;
    /** Position fixes in a row that were off the line. */
    private int offRoute;
    private long routedAt;
    /** When to ask for a route next even without leaving it (0 = now). */
    private long nextRouteAt;
    private double headingLat = Double.NaN;
    private double headingLng = Double.NaN;
    /** Direction of travel, degrees from north; null until the car has moved. */
    private Float heading;
    private double lastLat = Double.NaN;
    private double lastLng = Double.NaN;
    private boolean routing;
    private Arrival.Estimate estimate;

    private Trip() {
    }

    /** At launch: starts listening if the feature is on. */
    public static void install(Context context) {
        get().apply(context);
    }

    /** Is it allowed to run: beta features on and its own switch on. */
    public static boolean active(Context context) {
        AppConfig config = new AppConfig(context);
        return config.isBetaFeaturesEnabled() && config.isArrivalEnabled();
    }

    /** Starts or stops to match the settings. */
    public void apply(Context context) {
        synchronized (lock) {
            app = context.getApplicationContext();
        }
        if (active(context)) {
            start();
        } else {
            stop();
        }
    }

    private void start() {
        DestinationServer s;
        synchronized (lock) {
            if (server != null) {
                return;
            }
            server = new DestinationServer(app, this);
            s = server;
        }
        try {
            s.start();
            AppLog.i(TAG, "listening for places on port " + DestinationServer.PORT);
        } catch (Exception e) {
            AppLog.e(TAG, "could not listen on port " + DestinationServer.PORT, e);
        }
        AppConfig config = new AppConfig(app);
        String stored = config.getArrivalDestination();
        DestinationLink.Place p = DestinationLink.Place.restore(stored);
        if (p != null && System.currentTimeMillis() - DestinationLink.Place.storedAt(stored) < STALE_MS) {
            setDestination(p, false);
        } else if (p != null) {
            config.setArrivalDestination("");
        }
    }

    private void stop() {
        DestinationServer s;
        synchronized (lock) {
            s = server;
            server = null;
        }
        if (s != null) {
            s.stop();
            AppLog.i(TAG, "stopped listening");
        }
        clearInternal(false);
    }

    /** Is the receiver up (port bound)? */
    public boolean serverUp() {
        synchronized (lock) {
            return server != null && server.isAlive();
        }
    }

    /**
     * A place shared from the phone, as the server got it. Blocking (may follow a short link): call
     * off the main thread. Returns the place set, or null when nothing in it looked like a place.
     */
    DestinationLink.Place receive(String shared) {
        DestinationLink.Place p = DestinationLink.parse(shared);
        if (p == null) {
            String url = DestinationLink.firstUrl(shared);
            if (url != null) {
                p = route.resolve(url, DestinationLink.nameOf(shared, url));
            }
        }
        if (p == null) {
            AppLog.w(TAG, "no place in what the phone sent (" + (shared == null ? 0 : shared.length()) + " chars)");
            return null;
        }
        setDestination(p, true);
        return p;
    }

    private void setDestination(DestinationLink.Place p, boolean store) {
        Context context;
        boolean acquire;
        synchronized (lock) {
            context = app;
            acquire = destination == null;
            destination = p;
            forgetRoute();
            estimate = null;
            if (!listening) {
                listening = true;
                Telemetry.get().addListener(this);
                main.postDelayed(tick, TICK_MS);
            }
        }
        if (store && context != null) {
            new AppConfig(context).setArrivalDestination(p.store(System.currentTimeMillis()));
        }
        if (acquire && context != null) {
            Telemetry.get().acquire(context, TELEMETRY_USER);
        }
        AppLog.i(TAG, "destination set" + (p.name.isEmpty() ? "" : ": " + p.name));
        onReadingsChanged(null);
    }

    /** Clears the destination (the Clear button, or arriving). */
    public void clear() {
        clearInternal(true);
    }

    private void clearInternal(boolean log) {
        boolean had;
        Context context;
        synchronized (lock) {
            had = destination != null;
            destination = null;
            forgetRoute();
            estimate = null;
            context = app;
            if (listening) {
                listening = false;
                Telemetry.get().removeListener(this);
                main.removeCallbacks(tick);
            }
        }
        if (context != null) {
            new AppConfig(context).setArrivalDestination("");
        }
        if (had) {
            Telemetry.get().release(TELEMETRY_USER);
            if (log) {
                AppLog.i(TAG, "destination cleared");
            }
        }
    }

    /** Under {@link #lock}. */
    private void forgetRoute() {
        line = null;
        lineFromGoogle = false;
        lineSegment = -1;
        offRoute = 0;
        routedAt = 0;
        nextRouteAt = 0;
    }

    public Status status() {
        synchronized (lock) {
            return new Status(destination, estimate, line == null, line != null && lineFromGoogle);
        }
    }

    @Override
    public void onReadingsChanged(Readings ignored) {
        VehicleState s = Telemetry.get().latest();
        if (s == null || s.latitude == null || s.longitude == null) {
            return;
        }
        Readings r = s.readings;
        Float battery = r == null ? null : r.number(Signal.BATTERY);
        Float range = r == null ? null : r.number(Signal.RANGE);
        boolean arrived = false;
        boolean reroute = false;
        double fromLat = s.latitude;
        double fromLng = s.longitude;
        DestinationLink.Place to;
        Float headingNow;
        synchronized (lock) {
            to = destination;
            if (to == null) {
                return;
            }
            // direction of travel from the last two positions far enough apart (Google starts the
            // route in that direction, so it does not send the car into a U-turn)
            if (Double.isNaN(headingLat)
                    || RouteLine.metres(headingLat, headingLng, fromLat, fromLng) >= HEADING_MOVED_M) {
                if (!Double.isNaN(headingLat)) {
                    heading = (float) Arrival.bearing(headingLat, headingLng, fromLat, fromLng);
                }
                headingLat = fromLat;
                headingLng = fromLng;
            }
            headingNow = heading;
            boolean moved = fromLat != lastLat || fromLng != lastLng;
            lastLat = fromLat;
            lastLng = fromLng;
            double straight = Arrival.straightKm(fromLat, fromLng, to.lat, to.lng);
            if (straight < ARRIVED_KM) {
                arrived = true;
            } else {
                double left;
                if (line == null) {
                    left = straight * Arrival.ROAD_FACTOR;
                } else {
                    // Google Maps' way: snap onto the route line and count what is left along it
                    RouteLine.Fix fix = line.locate(fromLat, fromLng, lineSegment);
                    if (fix.offRouteM <= OFF_ROUTE_M) {
                        lineSegment = fix.segment;
                        offRoute = 0;
                        left = fix.remainingM / 1000.0;
                    } else {
                        // count each new fix only: the tick repeats the same one every 5 s
                        if (moved) {
                            offRoute++;
                        }
                        // off the line until the new route comes: at least the way back to it
                        left = Math.max(straight, (fix.remainingM + fix.offRouteM) / 1000.0);
                    }
                }
                estimate = Arrival.estimate(battery, range, left);
                long now = System.currentTimeMillis();
                boolean due = line == null ? now >= nextRouteAt
                        : now >= nextRouteAt
                        || (offRoute >= OFF_ROUTE_FIXES && now - routedAt >= REROUTE_MIN_MS);
                if (due && !routing) {
                    routing = true;
                    routedAt = now;
                    reroute = true;
                }
            }
        }
        if (arrived) {
            AppLog.i(TAG, "arrived");
            clear();
            return;
        }
        if (reroute) {
            String key;
            Context context;
            synchronized (lock) {
                context = app;
            }
            key = context == null ? null : new AppConfig(context).getGoogleMapsKey();
            worker.execute(() -> askRoute(fromLat, fromLng, headingNow, to, key));
        }
    }

    private void askRoute(double fromLat, double fromLng, Float heading, DestinationLink.Place to, String key) {
        RouteLookup.Route r = route.drivingRoute(fromLat, fromLng, heading, to.lat, to.lng, key);
        boolean wasOff;
        synchronized (lock) {
            routing = false;
            if (destination != to) {
                return;
            }
            wasOff = offRoute >= OFF_ROUTE_FIXES;
            long now = System.currentTimeMillis();
            if (r != null) {
                line = r.line;
                lineFromGoogle = r.google;
                lineSegment = -1;
                offRoute = 0;
                nextRouteAt = now + REROUTE_MS;
            } else {
                // no answer: keep the old line (if any) and try again soon
                nextRouteAt = now + RETRY_MS;
            }
        }
        if (r != null) {
            AppLog.i(TAG, String.format(java.util.Locale.US, "route%s: %.1f km, %d points (%s)",
                    wasOff ? " (left the old one)" : "", r.line.totalM / 1000.0, r.line.size(),
                    r.google ? "Google" : "OSRM"));
        }
        onReadingsChanged(null);
    }
}
