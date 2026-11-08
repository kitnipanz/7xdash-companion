package com.kooo.evcam.automation;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.kooo.evcam.AppConfig;
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
import java.util.Calendar;
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
 */
public final class Automation implements Telemetry.Listener {

    private static final String USER = "automation";
    /** The first ignition reading counts as the car coming on this soon after the head unit started. */
    private static final long BOOT_MS = 5 * 60_000L;
    /** Time and place change without the car saying so: look this often while a rule needs them. */
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
            Automation me = installed;
            String what = AutomationAction.describeAll(me.app, steps);
            me.runner.run(steps, true, failed -> me.remember("", what, failed, true));
        }
    }

    /** When a rule last ran, or null. */
    public static Long lastRan(String id) {
        return installed == null ? null : installed.ranAt.get(id);
    }

    @Override
    public void onReadingsChanged(Readings readings) {
        boolean active = config.isAutomationOn();
        boolean polled = active && wants(false);
        boolean gps = active && wants(true);
        holdFloats(active && (gps || wantsFloats()));
        holdGps(gps);
        if (polled && !looking) {
            looking = true;
            main.postDelayed(look, LOOK_MS);
        }
        List<AutomationRule> fired = watch.step(active ? rules : Collections.emptyList(), sample(readings, polled));
        if (fired.isEmpty() || UserExit.isExited(app)) {
            return;
        }
        for (AutomationRule rule : new ArrayList<>(fired)) {
            BlackBox.note("Automation: " + rule.trigger + " " + rule.value + " ran " + rule.steps.size() + " steps");
            Analytics.event("automation_ran", "trigger", rule.trigger);
            ranAt.put(rule.id, System.currentTimeMillis());
            String when = AutomationActivity.whenText(app, rule);
            String what = AutomationAction.describeAll(app, rule.steps);
            runner.run(rule.steps, false, failed -> remember(when, what, failed, false));
        }
    }

    private void remember(String when, String what, String failed, boolean tried) {
        config.setAutomationHistory(AutomationHistory.add(config.getAutomationHistory(),
                new AutomationHistory.Entry(System.currentTimeMillis(), when, what, failed, tried)));
    }

    private AutomationWatch.Sample sample(Readings readings, boolean polled) {
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
        if (polled) {
            Calendar now = Calendar.getInstance();
            s.minuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
            s.dayBit = AutomationClock.dayBit(now.get(Calendar.DAY_OF_WEEK));
            s.lat = car.latitude;
            s.lng = car.longitude;
            s.places = places;
        }
        return s;
    }

    /** Does a rule that is on need the clock, or with {@code placeOnly} the GPS? */
    private boolean wants(boolean placeOnly) {
        for (AutomationRule rule : rules) {
            for (AutomationRule.Condition condition : rule.conditions()) {
                AutomationTrigger trigger = condition.trigger();
                if (rule.enabled && (placeOnly ? trigger.group == AutomationTrigger.Group.PLACE : trigger.polled())) {
                    return true;
                }
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
            for (AutomationRule.Condition condition : rule.conditions()) {
                if (rule.enabled && condition.trigger().measure != null) {
                    return true;
                }
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
