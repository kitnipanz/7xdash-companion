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
    /** Ask for a fresh route this often while moving... */
    private static final long REROUTE_MS = 3 * 60_000L;
    /** ...and only after moving this far since the last one. */
    private static final double REROUTE_MOVED_KM = 1.0;
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

        Status(DestinationLink.Place destination, Arrival.Estimate estimate, boolean roughKm) {
            this.destination = destination;
            this.estimate = estimate;
            this.roughKm = roughKm;
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
    private Double routeKm;
    private double straightAtRoute;
    private Float odometerAtRoute;
    private double latAtRoute;
    private double lngAtRoute;
    private long routedAt;
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
            routeKm = null;
            routedAt = 0;
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
            routeKm = null;
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

    public Status status() {
        synchronized (lock) {
            return new Status(destination, estimate, routeKm == null);
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
        synchronized (lock) {
            to = destination;
            if (to == null) {
                return;
            }
            double straight = Arrival.straightKm(fromLat, fromLng, to.lat, to.lng);
            if (straight < ARRIVED_KM) {
                arrived = true;
            } else {
                Double driven = routeKm == null || odometerAtRoute == null || s.odometerKm == null
                        ? null : (double) (s.odometerKm - odometerAtRoute);
                double left = Arrival.kmLeft(straight, routeKm, straightAtRoute, driven);
                estimate = Arrival.estimate(battery, range, left);
                long now = System.currentTimeMillis();
                boolean due = routedAt == 0 || (now - routedAt > REROUTE_MS
                        // NaN (last ask failed) counts as moved
                        && !(Arrival.straightKm(latAtRoute, lngAtRoute, fromLat, fromLng) <= REROUTE_MOVED_KM));
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
            Float odometer = s.odometerKm;
            worker.execute(() -> askRoute(fromLat, fromLng, to, odometer));
        }
    }

    private void askRoute(double fromLat, double fromLng, DestinationLink.Place to, Float odometer) {
        Double km = route.drivingKm(fromLat, fromLng, to.lat, to.lng);
        synchronized (lock) {
            routing = false;
            if (destination != to) {
                return;
            }
            if (km != null) {
                routeKm = km;
                straightAtRoute = Arrival.straightKm(fromLat, fromLng, to.lat, to.lng);
                odometerAtRoute = odometer;
                latAtRoute = fromLat;
                lngAtRoute = fromLng;
            } else {
                // no answer: try again sooner than a full REROUTE_MS from a place we have not moved from
                routedAt = System.currentTimeMillis() - REROUTE_MS + 30_000L;
                latAtRoute = Double.NaN;
            }
        }
        if (km != null) {
            AppLog.i(TAG, String.format(java.util.Locale.US, "route: %.1f km", km));
        }
        onReadingsChanged(null);
    }
}
