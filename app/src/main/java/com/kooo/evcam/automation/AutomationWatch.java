package com.kooo.evcam.automation;

import com.kooo.evcam.nav.Geo;
import com.kooo.evcam.nav.SavedPlaces;
import com.kooo.evcam.telemetry.Signal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Decides which automations fire. Pure logic, fed one sample per change of the car's readings.
 *
 * <p>Automations fire on a change, not on a state: what the car already was when this first saw
 * it does nothing, so restarting the app in D does not run "Shift to D" again. The one exception
 * is the car coming on right after the head unit started: the head unit wakes with the car, so
 * the first reading is the only one there is. An unknown reading (null) never counts as a change.</p>
 *
 * <p>A number has to come back by {@link AutomationTrigger.Measure#hysteresis} before it can fire
 * again, and at least {@link #NUMBER_GAP_MS} passes between two runs, so driving around 80 km/h
 * does not flip the drive mode over and over. Rain gets the same gap.</p>
 *
 * <p>A saved place is entered within {@link #PLACE_RADIUS_M} and left only {@link #PLACE_MARGIN_M}
 * further out, so GPS wander at the edge is not leaving and arriving over and over.</p>
 */
final class AutomationWatch {

    /** One reading of the car; null is what the car did not give. */
    static final class Sample {
        Integer ignition;
        String gear;
        Float speedKmh;
        Integer dayNight;
        Float battery;
        Float outside;
        Boolean rain;
        /** The head unit started moments ago. */
        boolean justBooted;
        long at;
        /** The car's GPS position; given only while a rule needs a place. */
        Double lat;
        Double lng;
        /** Today, as a {@link AutomationCondition} day bit; 0 when not known. */
        int dayBit;
        /** Now, in minutes after midnight; -1 when not known. */
        int minute = -1;
        /** The saved places, by id. */
        Map<Integer, SavedPlaces.Place> places = Collections.emptyMap();
    }

    static final long NUMBER_GAP_MS = 30_000L;
    /** Inside a place within this of it. */
    static final double PLACE_RADIUS_M = 150;
    /** Outside again only this much further on. */
    static final double PLACE_MARGIN_M = 100;
    /** {@link com.kooo.evcam.screen.VehicleScreen}: codes from here up to ACC are "off or lower". */
    private static final int IGNITION_LOW_MIN = 0x00200100;

    private Boolean carOn;
    private String gear;
    private Integer dayNight;
    private Boolean rain;
    /** Per rule and number: past the number now. */
    private final Map<String, Boolean> past = new HashMap<>();
    private final Map<String, Long> lastRun = new HashMap<>();
    /** Per saved place: the car is inside it. */
    private final Map<Integer, Boolean> inside = new HashMap<>();
    /** Per rule and place: what the rule last saw, so it fires on the change only. */
    private final Map<String, Boolean> seenInside = new HashMap<>();

    /** The rules to run now, in list order. Call with no rules to keep up while nothing should run. */
    List<AutomationRule> step(List<AutomationRule> rules, Sample s) {
        Set<AutomationTrigger> events = events(s);
        locate(s);
        Set<String> placeKeys = new HashSet<>();
        List<AutomationRule> out = new ArrayList<>();
        for (AutomationRule rule : rules) {
            AutomationTrigger trigger = rule.trigger();
            if (!rule.enabled || trigger == null || rule.steps.isEmpty()) {
                continue;
            }
            boolean fires = trigger.isPlace() ? movedPast(rule, trigger, placeKeys)
                    : trigger.measure == null
                    ? events.contains(trigger) && settled(rule, trigger, s.at)
                    : crossed(rule, trigger, reading(trigger.measure, s), s.at);
            if (fires && holds(rule, s)) {
                out.add(rule);
            }
        }
        // a rule that is off, or was not looking, starts again from where the car is
        seenInside.keySet().retainAll(placeKeys);
        return out;
    }

    private Set<AutomationTrigger> events(Sample s) {
        Set<AutomationTrigger> events = EnumSet.noneOf(AutomationTrigger.class);
        Boolean on = isOn(s.ignition);
        if (on != null) {
            if (carOn == null) {
                if (on && s.justBooted && (s.gear == null || "P".equals(s.gear))) {
                    events.add(AutomationTrigger.CAR_ON);
                }
            } else if (!on.equals(carOn)) {
                events.add(on ? AutomationTrigger.CAR_ON : AutomationTrigger.CAR_OFF);
            }
            carOn = on;
        }
        if (s.gear != null) {
            if (gear != null && !s.gear.equals(gear)) {
                AutomationTrigger shifted = forGear(s.gear);
                if (shifted != null) {
                    events.add(shifted);
                }
            }
            gear = s.gear;
        }
        if (s.dayNight != null) {
            if (dayNight != null && !Objects.equals(s.dayNight, dayNight)) {
                events.add(s.dayNight == Signal.NIGHT ? AutomationTrigger.DARK : AutomationTrigger.LIGHT);
            }
            dayNight = s.dayNight;
        }
        if (s.rain != null) {
            if (rain != null && !s.rain.equals(rain)) {
                events.add(s.rain ? AutomationTrigger.RAIN_START : AutomationTrigger.RAIN_STOP);
            }
            rain = s.rain;
        }
        return events;
    }

    /**
     * Every condition of the rule is true now. A state not known yet (no day/night reading, no GPS
     * fix) does not hold, so a rule never runs on a guess.
     */
    private boolean holds(AutomationRule rule, Sample s) {
        for (AutomationRule.Condition condition : rule.conditions) {
            AutomationCondition kind = condition.kind();
            if (kind == null || !holds(kind, condition.value, s)) {
                return false;
            }
        }
        return true;
    }

    private boolean holds(AutomationCondition kind, int value, Sample s) {
        switch (kind) {
            case DAYS:
                return (value & s.dayBit) != 0;
            case AFTER_TIME:
            case BEFORE_TIME:
                return AutomationCondition.timeHolds(kind, value, s.minute);
            case DARK:
                return dayNight != null && dayNight == Signal.NIGHT;
            case LIGHT:
                return dayNight != null && dayNight != Signal.NIGHT;
            case RAINING:
                return Boolean.TRUE.equals(rain);
            case DRY:
                return Boolean.FALSE.equals(rain);
            case AT_PLACE:
                return Boolean.TRUE.equals(inside.get(value));
            case AWAY_FROM_PLACE:
                return Boolean.FALSE.equals(inside.get(value));
            default:
                return false;
        }
    }

    /** Where the car is against each saved place; a place stays as it was while there is no fix. */
    private void locate(Sample s) {
        inside.keySet().retainAll(s.places.keySet());
        if (s.lat == null || s.lng == null) {
            return;
        }
        for (SavedPlaces.Place place : s.places.values()) {
            boolean was = Boolean.TRUE.equals(inside.get(place.id));
            double meters = Geo.meters(s.lat, s.lng, place.lat, place.lng);
            inside.put(place.id, meters <= (was ? PLACE_RADIUS_M + PLACE_MARGIN_M : PLACE_RADIUS_M));
        }
    }

    /**
     * Arrive fires going from outside to inside, Leave the other way. Where the car already was when
     * the rule first saw it does nothing; a deleted place or no fix yet never fires.
     */
    private boolean movedPast(AutomationRule rule, AutomationTrigger trigger, Set<String> keys) {
        Boolean now = inside.get(rule.value);
        if (now == null) {
            return false;
        }
        String key = rule.id + ":" + rule.value;
        keys.add(key);
        Boolean was = seenInside.put(key, now);
        if (was == null || was.equals(now)) {
            return false;
        }
        return trigger == AutomationTrigger.PLACE_ARRIVE ? now : !now;
    }

    /** Light rain can read on and off every few seconds: rain gets the same gap between runs as a number. */
    private boolean settled(AutomationRule rule, AutomationTrigger trigger, long at) {
        if (trigger.group != AutomationTrigger.Group.RAIN) {
            return true;
        }
        Long last = lastRun.get(rule.id);
        if (last != null && at - last < NUMBER_GAP_MS) {
            return false;
        }
        lastRun.put(rule.id, at);
        return true;
    }

    private boolean crossed(AutomationRule rule, AutomationTrigger trigger, Float reading, long at) {
        if (reading == null) {
            return false;
        }
        String key = rule.id + ":" + trigger.key + ":" + rule.value;
        float limit = rule.value;
        boolean beyond = trigger.rising ? reading >= limit : reading <= limit;
        Boolean was = past.get(key);
        if (was == null) {
            past.put(key, beyond);
            return false;
        }
        if (!was) {
            Long last = lastRun.get(rule.id);
            if (!beyond || (last != null && at - last < NUMBER_GAP_MS)) {
                return false;
            }
            past.put(key, true);
            lastRun.put(rule.id, at);
            return true;
        }
        float back = trigger.measure.hysteresis;
        if (trigger.rising ? reading <= limit - back : reading >= limit + back) {
            past.put(key, false);
        }
        return false;
    }

    private static Float reading(AutomationTrigger.Measure measure, Sample s) {
        switch (measure) {
            case SPEED: return s.speedKmh;
            case BATTERY: return s.battery;
            case OUTSIDE: return s.outside;
            default: return null;
        }
    }

    private static AutomationTrigger forGear(String letter) {
        switch (letter) {
            case "P": return AutomationTrigger.GEAR_P;
            case "D": return AutomationTrigger.GEAR_D;
            case "R": return AutomationTrigger.GEAR_R;
            default: return null;
        }
    }

    /** True for ON and DRIVING, false for ACC or lower, null for anything else. */
    static Boolean isOn(Integer ignition) {
        if (ignition == null) {
            return null;
        }
        if (ignition == Signal.IGNITION_ON || ignition == Signal.IGNITION_DRIVING) {
            return true;
        }
        return ignition >= IGNITION_LOW_MIN && ignition <= Signal.IGNITION_ACC ? false : null;
    }
}
