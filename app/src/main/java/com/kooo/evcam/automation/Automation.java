package com.kooo.evcam.automation;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.UserExit;
import com.kooo.evcam.analytics.Analytics;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.nav.Navigator;
import com.kooo.evcam.nav.SavedPlaces;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;
import com.kooo.evcam.telemetry.VehicleState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the person's automations: when something happens on the car, do what they picked
 * ({@link AutomationWatch}, {@link AutomationRunner}). Off by default: it writes to the car by
 * itself.
 *
 * <p>Gear, ignition, day or night and rain are core telemetry, read for as long as the process lives.
 * Speed, battery and temperature are float sensors, so this registers as a full telemetry user
 * only while a rule that is on needs one. Installed after {@code GearLight}, so a colour set when
 * the gear changes lands on top of what the gear picks. Main thread only.</p>
 *
 * <p>Arriving at or leaving a saved place needs the GPS: while such a rule is on, telemetry is held,
 * {@link Navigator#holdGps} keeps the position coming in the background, and the position is
 * looked at every {@link #LOOK_MS} as well as on every change of the car's readings.</p>
 */
public final class Automation implements Telemetry.Listener {

    private static final String USER = "automation";
    /** The first ignition reading counts as the car coming on this soon after the head unit started. */
    private static final long BOOT_MS = 5 * 60_000L;
    /** The car's position changes without the car saying so: look this often while a rule needs it. */
    private static final long LOOK_MS = 15_000L;

    private static Automation installed;

    private final Context app;
    private final AppConfig config;
    private final AutomationRunner runner;
    private final AutomationWatch watch = new AutomationWatch();
    /** When each rule last ran (wall clock), for the list. Not kept across restarts. */
    private final Map<String, Long> ranAt = new HashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable look = () -> {
        looking = false;
        onReadingsChanged(Telemetry.get().readings());
    };
    private List<AutomationRule> rules;
    private Map<Integer, SavedPlaces.Place> places = new HashMap<>();
    private boolean floatsHeld;
    private boolean gpsHeld;
    private boolean looking;

    private Automation(Context context) {
        app = context.getApplicationContext();
        config = new AppConfig(app);
        runner = new AutomationRunner(app);
        rules = AutomationRule.parseAll(config.getAutomationRules());
        readPlaces();
    }

    private void readPlaces() {
        places = new HashMap<>();
        for (SavedPlaces.Place place : SavedPlaces.load(app)) {
            places.put(place.id, place);
        }
    }

    /** Application start. Repeat calls do nothing. */
    public static void install(Context context) {
        if (installed != null) {
            return;
        }
        installed = new Automation(context);
        Telemetry.get().addListener(installed);
        installed.onReadingsChanged(Telemetry.get().readings());
    }

    /** The switch, the rules or the saved places changed. */
    public static void reload() {
        if (installed != null) {
            installed.rules = AutomationRule.parseAll(installed.config.getAutomationRules());
            installed.readPlaces();
            installed.onReadingsChanged(Telemetry.get().readings());
        }
    }

    /** The Try button: run these steps now, whatever the car is doing. */
    public static void tryNow(List<AutomationRule.Step> steps) {
        if (installed != null) {
            installed.runner.run(steps, true, null);
        }
    }

    /** When a rule last ran, or null. */
    public static Long lastRan(String id) {
        return installed == null ? null : installed.ranAt.get(id);
    }

    @Override
    public void onReadingsChanged(Readings readings) {
        boolean active = config.isAutomationOn();
        boolean gps = active && wantsPlace();
        holdFloats(active && (gps || wantsFloats()));
        holdGps(gps);
        if (gps && !looking) {
            looking = true;
            main.postDelayed(look, LOOK_MS);
        }
        List<AutomationRule> fired = watch.step(active ? rules : Collections.emptyList(), sample(readings, gps));
        if (fired.isEmpty() || UserExit.isExited(app)) {
            return;
        }
        for (AutomationRule rule : new ArrayList<>(fired)) {
            BlackBox.note("Automation: " + rule.trigger + " " + rule.value + " if " + rule.conditions.size()
                    + " conditions ran " + rule.steps.size() + " steps");
            Analytics.event("automation_ran", "trigger", rule.trigger);
            ranAt.put(rule.id, System.currentTimeMillis());
            runner.run(rule.steps, false, app.getString(R.string.automation_sentence,
                    AutomationActivity.whenText(app, rule), AutomationAction.describeAll(app, rule.steps)));
        }
    }

    private AutomationWatch.Sample sample(Readings readings, boolean located) {
        VehicleState car = Telemetry.get().latest();
        AutomationWatch.Sample s = new AutomationWatch.Sample();
        s.ignition = readings.code(Signal.IGNITION);
        s.gear = car.gear;
        s.speedKmh = car.speedKmh;
        s.dayNight = readings.code(Signal.DAY_NIGHT);
        s.battery = readings.number(Signal.BATTERY);
        s.outside = readings.number(Signal.TEMP_OUTSIDE);
        s.rain = readings.bool(Signal.RAIN);
        s.at = SystemClock.elapsedRealtime();
        s.justBooted = s.at < BOOT_MS;
        java.util.Calendar now = java.util.Calendar.getInstance();
        s.dayBit = AutomationCondition.dayBit(now.get(java.util.Calendar.DAY_OF_WEEK));
        s.minute = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE);
        if (located) {
            s.lat = car.latitude;
            s.lng = car.longitude;
            s.places = places;
        }
        return s;
    }

    /** Does a rule that is on wait to arrive at or leave a place, or only run at or away from one? */
    private boolean wantsPlace() {
        for (AutomationRule rule : rules) {
            AutomationTrigger trigger = rule.trigger();
            if (rule.enabled && trigger != null && trigger.isPlace()) {
                return true;
            }
            if (rule.enabled && rule.condition(AutomationCondition.Group.PLACE) != null) {
                return true;
            }
        }
        return false;
    }

    private void holdGps(boolean on) {
        if (on != gpsHeld) {
            gpsHeld = on;
            Navigator.get().holdGps(app, on);
        }
    }

    private boolean wantsFloats() {
        for (AutomationRule rule : rules) {
            AutomationTrigger trigger = rule.trigger();
            if (rule.enabled && trigger != null && trigger.measure != null) {
                return true;
            }
        }
        return false;
    }

    private void holdFloats(boolean on) {
        if (on == floatsHeld) {
            return;
        }
        floatsHeld = on;
        if (on) {
            Telemetry.get().acquire(app, USER);
        } else {
            Telemetry.get().release(USER);
        }
    }
}
