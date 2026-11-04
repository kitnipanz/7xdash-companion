package com.kooo.evcam.nav;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.overlay.OverlayCoordinator;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;
import com.kooo.evcam.telemetry.VehicleState;
import com.kooo.evcam.trip.Arrival;
import com.kooo.evcam.trip.DestinationLink;
import com.kooo.evcam.trip.Trip;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Turn-by-turn navigation (beta): a route with traffic from the car's GPS to
 * a place ({@link TomTomApi}), followed fix by fix ({@link Guidance}), with the turns spoken in the
 * cabin ({@link VoicePlan}, {@link NavVoice}) and kept on the HUD ({@link HudTurnWindow}). Leaving
 * the route asks for a new one. Keeps going when the Navigation screen is closed, until the
 * destination or End.
 *
 * <p>Main thread only, except the TomTom calls, which run on a worker and come back to it.</p>
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
    /** Below this the car counts as standing: its GPS only wanders, worst under a roof. */
    private static final float STANDING_KMH = 3f;
    /** Further from where it stopped than this it has moved, whatever the speed says. */
    private static final double WANDER_M = 60;

    public enum Phase {
        /** Nothing planned. */
        IDLE,
        /** Asking TomTom for the route. */
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
        /** km/h, or null when the car does not say. */
        public final Float carSpeedKmh;
        /** Battery at arrival, or null until the car has given battery and range. */
        public final Arrival.Estimate battery;
        public final boolean muted;
        /** Before starting, the routes to choose from (TomTom's pick first); one or none otherwise. */
        public final List<NavRoute> choices;

        Snapshot(Phase phase, DestinationLink.Place destination, NavRoute route, Guidance.Progress progress,
                 boolean rerouting, int error, String errorDetail, Double carLat, Double carLng, Double carBearing,
                 Float carSpeedKmh, Arrival.Estimate battery, boolean muted, List<NavRoute> choices) {
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
            this.carSpeedKmh = carSpeedKmh;
            this.battery = battery;
            this.muted = muted;
            this.choices = choices;
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
    private final HudMapWindow hudMap = new HudMapWindow();
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
    private TomTomApi api;
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
    private Float carSpeed;
    private double headingFromLat = Double.NaN;
    private double headingFromLng = Double.NaN;
    private Arrival.Estimate battery;
    private boolean muted;
    private boolean telemetryHeld;
    /** The routes to choose from before starting (TomTom's pick first); the chosen one is {@link #guidance}'s. */
    private List<NavRoute> choices = new ArrayList<>();
    /** A place came from the iPhone while guiding: start guiding to it as soon as its route is in. */
    private boolean startWhenRouted;
    /** The turn card is being placed by hand ({@link #setHudPlacing}). */
    private boolean hudPlacing;
    /** Navigation screens open (the full screen, the floating window): GPS on for the map and the search, even with nothing planned. */
    private int attached;

    private Navigator() {
    }

    /** Is the feature allowed: beta features on. */
    public static boolean active(Context context) {
        return new AppConfig(context).isBetaFeaturesEnabled();
    }

    /** True when the build has a TomTom key. */
    public static boolean hasKey() {
        return TomTomApi.hasKey();
    }

    /** At launch: places shared from the iPhone open Navigation. */
    public static void install(Context context) {
        Context app = context.getApplicationContext();
        Trip.setSharedListener(place -> get().onShared(app, place));
    }

    /**
     * The iPhone shared a place: show its route, or switch to it while guiding. With no Navigation
     * screen open, the floating window opens so the head unit shows it at once.
     */
    private void onShared(Context context, DestinationLink.Place place) {
        String name = NavMapPainter.placeName(place);
        if (!active(context) || !hasKey() || !new AppConfig(context).isNavShareEnabled()) {
            // no route here, but the place is in: battery at arrival on the Dash panel and Home
            notice(context, context.getString(R.string.nav_place_received_plain, name));
            return;
        }
        boolean wasGuiding = phase == Phase.GUIDING;
        AppLog.i(TAG, "place shared from the phone" + (wasGuiding ? " while guiding" : ""));
        notice(context, context.getString(wasGuiding ? R.string.nav_place_received_switch
                : R.string.nav_place_received, name));
        preview(context, place);
        startWhenRouted = wasGuiding;
        if (attached == 0 && OverlayCoordinator.canShowOverlay(context)) {
            NavFloatWindow.get().show(context);
        }
    }

    /** The pop-up on the head unit (and on the HUD when HUD notices are on): a place came in from the phone. */
    private static void notice(Context context, String text) {
        com.kooo.evcam.ui.Notice.makeText(context, text, android.widget.Toast.LENGTH_LONG).show();
    }

    /** A Navigation screen opened: keep the car's position coming. Pair with {@link #detach}. */
    public void attach(Context context) {
        bind(context);
        attached++;
        keepRunning();
    }

    /** A Navigation screen closed: guiding goes on, but nothing else needs the GPS. */
    public void detach() {
        attached = Math.max(0, attached - 1);
        keepRunning();
    }

    private boolean running() {
        return attached > 0 || phase != Phase.IDLE;
    }

    /** What the HUD card shows: the next turn, how far, what to do, and the trip. */
    public static final class HudLine {
        public final Maneuver maneuver;
        public final String distance;
        public final String text;
        /** "Arrive 21:42 · 23%"; empty when there is nothing to say. */
        public final String trip;
        public final Arrival.Level tripLevel;

        HudLine(Maneuver maneuver, String distance, String text, String trip, Arrival.Level tripLevel) {
            this.maneuver = maneuver;
            this.distance = distance;
            this.text = text;
            this.trip = trip;
            this.tripLevel = tripLevel;
        }
    }

    /** A made-up card for the HUD preview when nothing is being guided. */
    public static HudLine sampleHudLine(Context context) {
        return new HudLine(Maneuver.TURN_RIGHT, context.getString(R.string.nav_m, 300),
                context.getString(R.string.nav_hud_sample_text),
                context.getString(R.string.nav_hud_trip, "21:42", "64%"), Arrival.Level.OK);
    }

    /** The HUD card for this moment; null when nothing is being guided. */
    public static HudLine hudLine(Context context, Snapshot s) {
        if (s.phase == Phase.ARRIVED) {
            return new HudLine(Maneuver.ARRIVE, context.getString(R.string.nav_arrived),
                    s.destination == null ? "" : s.destination.name, "", Arrival.Level.OK);
        }
        if (s.phase != Phase.GUIDING || s.route == null || s.progress == null) {
            return null;
        }
        int next = s.progress.next;
        Maneuver m = next < 0 ? Maneuver.ARRIVE : s.route.steps.get(next).maneuver;
        String eta = NavText.eta(context, s.progress.remainingSeconds);
        String trip = s.battery == null ? context.getString(R.string.nav_hud_eta, eta)
                : context.getString(R.string.nav_hud_trip, eta, TripLine.battery(s.battery));
        return new HudLine(m, NavText.distance(context, s.progress.toNext),
                s.rerouting ? context.getString(R.string.nav_rerouting) : NavText.headline(context, s.route, next),
                trip, s.battery == null ? Arrival.Level.OK : s.battery.level);
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
                error, errorDetail, carLat, carLng, carBearing, carSpeed, battery, muted, java.util.Collections.unmodifiableList(new ArrayList<>(choices)));
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
        choices = new ArrayList<>();
        startWhenRouted = false;
        voicePlan.reset();
        phase = Phase.ROUTING;
        AppLog.i(TAG, "planning" + (place.name.isEmpty() ? "" : " to " + place.name));
        if (wasGuiding) {
            hud.hide();
            hudMap.hide();
        }
        keepRunning();
        askRoute(false);
        changed();
    }

    /** Before starting: drive {@code route} instead (one of {@link Snapshot#choices}). */
    public void choose(NavRoute route) {
        if (phase != Phase.PREVIEW || route == null || !choices.contains(route)
                || (guidance != null && guidance.route() == route)) {
            return;
        }
        guidance = new Guidance(route);
        AppLog.i(TAG, String.format(Locale.US, "chose the route %.1f km, %.0f min", route.meters / 1000, route.seconds / 60));
        onTick();
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
        choices = new ArrayList<>();
        rerouting = false;
        startWhenRouted = false;
        // the place from the iPhone is done with too: no trip left on the Dash panel
        Trip.get().clear();
        error = 0;
        errorDetail = null;
        ask++;
        hud.hide();
        hudMap.hide();
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
            List<TomTomJson.Found> found = null;
            String why = null;
            try {
                found = api.search(query, lat, lng);
            } catch (TomTomApi.Failure f) {
                why = f.getMessage();
            }
            List<TomTomJson.Found> result = found;
            String failure = why;
            main.post(() -> callback.onResult(result, failure == null ? 0 : errorText(failure)));
        });
    }

    public interface SearchCallback {
        /** {@code found} is null on failure, with {@code error} a string resource. */
        void onResult(List<TomTomJson.Found> found, int error);
    }

    private void bind(Context context) {
        if (app == null) {
            app = context.getApplicationContext();
            api = new TomTomApi(app);
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
        // the other good routes only when planning, to choose from; never for a reroute while driving
        boolean alternatives = !reroute && phase != Phase.GUIDING && !startWhenRouted;
        worker.execute(() -> {
            List<NavRoute> routes = new ArrayList<>();
            String why = null;
            try {
                routes = api.routes(fromLat, fromLng, heading, to.lat, to.lng, alternatives);
                if (routes.isEmpty()) {
                    why = "no route";
                }
            } catch (TomTomApi.Failure f) {
                why = f.getMessage();
            }
            List<NavRoute> got = routes;
            String failure = why;
            main.post(() -> onRoute(mine, got, failure, reroute));
        });
    }

    private void onRoute(int mine, List<NavRoute> all, String failure, boolean reroute) {
        if (mine != ask || phase == Phase.IDLE) {
            return;
        }
        NavRoute route = all.isEmpty() ? null : all.get(0);
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
        choices = phase == Phase.GUIDING ? new ArrayList<>(java.util.Collections.singletonList(route)) : all;
        voicePlan.reset();
        error = 0;
        errorDetail = null;
        if (phase != Phase.GUIDING) {
            phase = Phase.PREVIEW;
            if (startWhenRouted) {
                startWhenRouted = false;
                start();
                return;
            }
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
        carSpeed = s.speedKmh;
        if (carLat != null && standing(s.speedKmh, Geo.meters(carLat, carLng, s.latitude, s.longitude))) {
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

    /** A standing car stays where it is: its wandering fixes must not move or turn it on the map. */
    static boolean standing(Float kmh, double movedM) {
        return kmh != null && kmh < STANDING_KMH && movedM < WANDER_M;
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
            showHud();
            return;
        }
        if (progress.offRoute && !rerouting && now - routedAt > REROUTE_MS) {
            AppLog.i(TAG, String.format(Locale.US, "off the route by %.0f m: rerouting", progress.offMeters));
            askRoute(true);
        } else if (!rerouting && now - routedAt > REFRESH_MS) {
            askRoute(true);
        }
        NavRoute route = guidance.route();
        showHud();

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

    /** The HUD card for now, or none when it is switched off. While it is being placed: a sample if nothing is guided. */
    private void showHud() {
        if (app == null) {
            return;
        }
        HudLine line = hudLine(app, snapshot());
        AppConfig config = new AppConfig(app);
        if (hudPlacing) {
            hud.show(app, line != null ? line : sampleHudLine(app));
        } else if (line != null && config.isNavHudEnabled()) {
            hud.show(app, line);
        } else {
            hud.hide();
        }
        if ((hudPlacing || line != null) && config.isNavHudMapEnabled()) {
            hudMap.show(app);
        } else {
            hudMap.hide();
        }
    }

    /** The turn card is being placed by hand: keep it on the real HUD, a sample when nothing is guided. */
    public void setHudPlacing(Context context, boolean on) {
        bind(context);
        hudPlacing = on;
        hud.hide();
        showHud();
    }

    /** Its place or size changed (the card's or the map's), or the map was switched: draw them again. */
    public void relayoutHud() {
        if (app == null) {
            return;
        }
        hud.hide();
        hudMap.reset();
        hudMap.relayout(app);
        showHud();
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
