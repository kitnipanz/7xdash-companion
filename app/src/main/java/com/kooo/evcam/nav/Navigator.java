package com.kooo.evcam.nav;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;
import com.kooo.evcam.telemetry.VehicleState;
import com.kooo.evcam.trip.Arrival;
import com.kooo.evcam.trip.DestinationLink;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Turn-by-turn navigation (beta), Google Maps style: a route with traffic from the car's GPS to
 * a place ({@link GoogleApi}), followed fix by fix ({@link Guidance}), with the turns spoken in the
 * cabin ({@link VoicePlan}, {@link NavVoice}) and kept on the HUD ({@link HudTurnWindow}). Leaving
 * the route asks for a new one. Keeps going when the Navigation screen is closed, until the
 * destination or End.
 *
 * <p>Main thread only, except the Google calls, which run on a worker and come back to it.</p>
 */
public final class Navigator {

    private static final String TAG = "Navigator";
    private static final String TELEMETRY_USER = "navigation";
    private static final long TICK_MS = 1_000L;
    /** Not more often than this when off the route (GPS in a car park can wander)... */
    private static final long REROUTE_MS = 10_000L;
    /** ...and this often anyway, for fresh traffic and arrival time. */
    private static final long REFRESH_MS = 10 * 60_000L;
    /** The screen and HUD show "Arrived" this long, then navigation ends by itself. */
    private static final long ARRIVED_SHOW_MS = 15_000L;
    /** Moved this far between fixes: enough to tell which way the car is heading off the route. */
    private static final double HEADING_MIN_M = 8;

    public enum Phase {
        /** Nothing planned. */
        IDLE,
        /** Asking Google for the route. */
        ROUTING,
        /** Route shown, not started yet. */
        PREVIEW,
        GUIDING,
        ARRIVED,
        /** No route: see {@link Snapshot#error}. */
        FAILED
    }

    /** What the screen shows. Immutable. */
    public static final class Snapshot {
        public final Phase phase;
        public final DestinationLink.Place destination;
        public final NavRoute route;
        /** Null until there is a route and a fix. */
        public final Guidance.Progress progress;
        /** Asking for a new route while guiding (off the route). */
        public final boolean rerouting;
        /** A string resource for {@link Phase#FAILED}, else 0. */
        public final int error;
        public final String errorDetail;
        /** The car, or null without GPS. */
        public final Double carLat;
        public final Double carLng;
        public final Double carBearing;
        /** Battery at arrival, or null until the car has given battery and range. */
        public final Arrival.Estimate battery;
        public final boolean muted;

        Snapshot(Phase phase, DestinationLink.Place destination, NavRoute route, Guidance.Progress progress,
                 boolean rerouting, int error, String errorDetail, Double carLat, Double carLng, Double carBearing,
                 Arrival.Estimate battery, boolean muted) {
            this.phase = phase;
            this.destination = destination;
            this.route = route;
            this.progress = progress;
            this.rerouting = rerouting;
            this.error = error;
            this.errorDetail = errorDetail;
            this.carLat = carLat;
            this.carLng = carLng;
            this.carBearing = carBearing;
            this.battery = battery;
            this.muted = muted;
        }
    }

    public interface Listener {
        void onNavChanged(Snapshot s);
    }

    private static final Navigator INSTANCE = new Navigator();

    public static Navigator get() {
        return INSTANCE;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "navigation");
        t.setDaemon(true);
        return t;
    });
    private final List<Listener> listeners = new ArrayList<>();
    private final VoicePlan voicePlan = new VoicePlan();
    private final HudTurnWindow hud = new HudTurnWindow();
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            onTick();
            if (running()) {
                main.postDelayed(this, TICK_MS);
            }
        }
    };

    private Context app;
    private GoogleApi api;
    private NavVoice voice;
    private Phase phase = Phase.IDLE;
    private DestinationLink.Place destination;
    private Guidance guidance;
    private Guidance.Progress progress;
    private boolean rerouting;
    /** Bumped for each route asked for: an answer for an older ask is dropped. */
    private int ask;
    private long routedAt;
    private long arrivedAt;
    private int error;
    private String errorDetail;
    private Double carLat;
    private Double carLng;
    private Double carBearing;
    private double headingFromLat = Double.NaN;
    private double headingFromLng = Double.NaN;
    private Arrival.Estimate battery;
    private boolean muted;
    private boolean telemetryHeld;
    /** The Navigation screen is open: GPS on for the map and the search, even with nothing planned. */
    private boolean attached;

    private Navigator() {
    }

    /** Is the feature allowed: beta features on. */
    public static boolean active(Context context) {
        return new AppConfig(context).isBetaFeaturesEnabled();
    }

    /** True when the build has a Google Maps Platform key. */
    public static boolean hasKey() {
        return GoogleApi.hasKey();
    }

    /** The Navigation screen opened: keep the car's position coming. */
    public void attach(Context context) {
        bind(context);
        attached = true;
        keepRunning();
    }

    /** The Navigation screen closed: guiding goes on, but nothing else needs the GPS. */
    public void detach() {
        attached = false;
        keepRunning();
    }

    private boolean running() {
        return attached || phase != Phase.IDLE;
    }

    /** Telemetry held and the tick going while {@link #running}, released and stopped when not. */
    private void keepRunning() {
        main.removeCallbacks(tick);
        if (running()) {
            if (!telemetryHeld) {
                telemetryHeld = true;
                Telemetry.get().acquire(app, TELEMETRY_USER);
            }
            main.post(tick);
        } else if (telemetryHeld) {
            telemetryHeld = false;
            Telemetry.get().release(TELEMETRY_USER);
        }
    }

    public void addListener(Listener l) {
        if (!listeners.contains(l)) {
            listeners.add(l);
        }
        l.onNavChanged(snapshot());
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public Snapshot snapshot() {
        return new Snapshot(phase, destination, guidance == null ? null : guidance.route(), progress, rerouting,
                error, errorDetail, carLat, carLng, carBearing, battery, muted);
    }

    public boolean isGuiding() {
        return phase == Phase.GUIDING;
    }

    /** Plans a route to {@code place} and shows it (Start begins guiding). */
    public void preview(Context context, DestinationLink.Place place) {
        bind(context);
        boolean wasGuiding = phase == Phase.GUIDING;
        destination = place;
        guidance = null;
        progress = null;
        voicePlan.reset();
        phase = Phase.ROUTING;
        AppLog.i(TAG, "planning" + (place.name.isEmpty() ? "" : " to " + place.name));
        if (wasGuiding) {
            hud.hide();
        }
        keepRunning();
        askRoute(false);
        changed();
    }

    /** Starts guiding along the route shown. */
    public void start() {
        if (guidance == null || (phase != Phase.PREVIEW && phase != Phase.GUIDING)) {
            return;
        }
        phase = Phase.GUIDING;
        voicePlan.reset();
        AppLog.i(TAG, "guiding");
        NavRoute r = guidance.route();
        if (!r.steps.isEmpty()) {
            speak(NavText.headline(app, r, 0));
        }
        onTick();
    }

    /** Ends navigation: route, voice and HUD card go. */
    public void end() {
        if (phase == Phase.IDLE) {
            return;
        }
        AppLog.i(TAG, "ended");
        phase = Phase.IDLE;
        destination = null;
        guidance = null;
        progress = null;
        rerouting = false;
        error = 0;
        errorDetail = null;
        ask++;
        hud.hide();
        if (voice != null) {
            voice.stop();
        }
        keepRunning();
        changed();
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
        if (muted && voice != null) {
            voice.stop();
        }
        changed();
    }

    /** Asks again after a failure (the Retry button). */
    public void retry() {
        if (destination != null && phase == Phase.FAILED) {
            phase = Phase.ROUTING;
            askRoute(false);
            changed();
        }
    }

    /** The search box: places matching {@code query}, near the car. Answers on the main thread. */
    public void search(Context context, String query, SearchCallback callback) {
        bind(context);
        Double lat = carLat;
        Double lng = carLng;
        worker.execute(() -> {
            List<GoogleJson.Found> found = null;
            String why = null;
            try {
                found = api.search(query, lat, lng);
            } catch (GoogleApi.Failure f) {
                why = f.getMessage();
            }
            List<GoogleJson.Found> result = found;
            String failure = why;
            main.post(() -> callback.onResult(result, failure == null ? 0 : errorText(failure)));
        });
    }

    public interface SearchCallback {
        /** {@code found} is null on failure, with {@code error} a string resource. */
        void onResult(List<GoogleJson.Found> found, int error);
    }

    private void bind(Context context) {
        if (app == null) {
            app = context.getApplicationContext();
            api = new GoogleApi(app);
            voice = new NavVoice(app);
        }
    }

    private void askRoute(boolean reroute) {
        if (carLat == null || carLng == null) {
            readCar();
        }
        if (carLat == null || carLng == null) {
            // no fix yet: the tick asks again once there is one
            routedAt = 0;
            return;
        }
        DestinationLink.Place to = destination;
        double fromLat = carLat;
        double fromLng = carLng;
        Double heading = carBearing;
        int mine = ++ask;
        routedAt = System.currentTimeMillis();
        rerouting = reroute;
        worker.execute(() -> {
            NavRoute route = null;
            String why = null;
            try {
                route = api.route(fromLat, fromLng, heading, to.lat, to.lng);
                if (route == null) {
                    why = "no route";
                }
            } catch (GoogleApi.Failure f) {
                why = f.getMessage();
            }
            NavRoute got = route;
            String failure = why;
            main.post(() -> onRoute(mine, got, failure, reroute));
        });
    }

    private void onRoute(int mine, NavRoute route, String failure, boolean reroute) {
        if (mine != ask || phase == Phase.IDLE) {
            return;
        }
        rerouting = false;
        if (route == null) {
            AppLog.w(TAG, "no route: " + failure);
            if (reroute || phase == Phase.GUIDING) {
                // keep guiding on the old line; the tick asks again after REROUTE_MS
                changed();
                return;
            }
            phase = Phase.FAILED;
            error = errorText(failure);
            errorDetail = failure;
            changed();
            return;
        }
        AppLog.i(TAG, String.format(Locale.US, "route: %.1f km, %.0f min, %d steps%s", route.meters / 1000,
                route.seconds / 60, route.steps.size(), reroute ? " (rerouted)" : ""));
        guidance = new Guidance(route);
        voicePlan.reset();
        error = 0;
        errorDetail = null;
        if (phase != Phase.GUIDING) {
            phase = Phase.PREVIEW;
        }
        onTick();
    }

    private static int errorText(String failure) {
        if (failure == null) {
            return R.string.nav_error_route;
        }
        if (failure.equals("no key")) {
            return R.string.nav_error_no_key;
        }
        if (failure.startsWith("offline")) {
            return R.string.nav_error_offline;
        }
        if (failure.equals("no route")) {
            return R.string.nav_error_no_road;
        }
        return R.string.nav_error_route;
    }

    /** Reads the car's position and heading from the telemetry (left as they were without a fix). */
    private VehicleState readCar() {
        VehicleState s = Telemetry.get().latest();
        if (s == null || s.latitude == null || s.longitude == null) {
            return s;
        }
        carLat = s.latitude;
        carLng = s.longitude;
        if (Double.isNaN(headingFromLat)) {
            headingFromLat = carLat;
            headingFromLng = carLng;
        } else if (Geo.meters(headingFromLat, headingFromLng, carLat, carLng) >= HEADING_MIN_M) {
            carBearing = Geo.bearing(headingFromLat, headingFromLng, carLat, carLng);
            headingFromLat = carLat;
            headingFromLng = carLng;
        }
        return s;
    }

    private void onTick() {
        if (app == null) {
            return;
        }
        VehicleState s = readCar();
        if (phase == Phase.IDLE) {
            changed();
            return;
        }
        long now = System.currentTimeMillis();
        if (guidance == null) {
            // still waiting for a fix to plan from
            if (phase == Phase.ROUTING && routedAt == 0 && carLat != null) {
                askRoute(false);
            }
            changed();
            return;
        }
        if (carLat == null) {
            changed();
            return;
        }
        progress = guidance.update(carLat, carLng, 0);
        if (!progress.offRoute) {
            carBearing = progress.bearing;
        }
        Readings r = s == null ? null : s.readings;
        Float pct = r == null ? null : r.number(Signal.BATTERY);
        Float range = r == null ? null : r.number(Signal.RANGE);
        battery = Arrival.estimate(pct, range, progress.remainingMeters / 1000);

        if (phase == Phase.GUIDING) {
            guide(s, now);
        } else if (phase == Phase.ARRIVED && now - arrivedAt > ARRIVED_SHOW_MS) {
            end();
            return;
        }
        changed();
    }

    private void guide(VehicleState s, long now) {
        if (progress.arrived) {
            AppLog.i(TAG, "arrived");
            phase = Phase.ARRIVED;
            arrivedAt = now;
            speak(app.getString(R.string.nav_say_arrived));
            showHud(Maneuver.ARRIVE, app.getString(R.string.nav_arrived), destinationName());
            return;
        }
        if (progress.offRoute && !rerouting && now - routedAt > REROUTE_MS) {
            AppLog.i(TAG, String.format(Locale.US, "off the route by %.0f m: rerouting", progress.offMeters));
            askRoute(true);
        } else if (!rerouting && now - routedAt > REFRESH_MS) {
            askRoute(true);
        }
        NavRoute route = guidance.route();
        Maneuver m = progress.next < 0 ? Maneuver.ARRIVE : route.steps.get(progress.next).maneuver;
        String headline = NavText.headline(app, route, progress.next);
        showHud(m, NavText.distance(app, progress.toNext), rerouting ? app.getString(R.string.nav_rerouting) : headline);

        float kmh = s == null || s.speedKmh == null ? 0 : s.speedKmh;
        double leg = progress.next < 0 ? route.meters - route.steps.get(route.steps.size() - 1).startAlong
                : route.steps.get(progress.next).startAlong
                - (progress.next > 0 ? route.steps.get(progress.next - 1).startAlong : 0);
        VoicePlan.Prompt prompt = voicePlan.due(progress.next, progress.toNext, leg, kmh);
        if (prompt != null && !progress.offRoute) {
            speak(NavText.spoken(app, route, prompt));
        }
    }

    private String destinationName() {
        return destination == null || destination.name.isEmpty() ? "" : destination.name;
    }

    private void showHud(Maneuver m, String distance, String text) {
        AppConfig config = new AppConfig(app);
        if (config.isNavHudEnabled()) {
            hud.show(app, m, distance, text);
        } else {
            hud.hide();
        }
    }

    private void speak(String text) {
        if (muted || voice == null || !new AppConfig(app).isNavVoiceEnabled()) {
            return;
        }
        voice.say(text, Locale.forLanguageTag(api.language()));
    }

    private void changed() {
        Snapshot s = snapshot();
        for (Listener l : new ArrayList<>(listeners)) {
            l.onNavChanged(s);
        }
    }
}
