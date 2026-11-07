package com.kooo.evcam.automation;

import com.kooo.evcam.telemetry.Signal;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
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
 * <p>A rule with several conditions fires the moment the last of them comes true, in any order, and
 * can fire again once one has stopped being true. It uses the same gap as a number.</p>
 *
 * <p>A number has to come back by {@link AutomationTrigger.Measure#hysteresis} before it can fire
 * again, and at least {@link #NUMBER_GAP_MS} passes between two runs, so driving around 80 km/h
 * does not flip the drive mode over and over. Rain gets the same gap.</p>
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
    }

    static final long NUMBER_GAP_MS = 30_000L;
    /** {@link com.kooo.evcam.screen.VehicleScreen}: codes from here up to ACC are "off or lower". */
    private static final int IGNITION_LOW_MIN = 0x00200100;

    private Boolean carOn;
    private String gear;
    private Integer dayNight;
    private Boolean rain;
    /** Per rule and number: past the number now. */
    private final Map<String, Boolean> past = new HashMap<>();
    private final Map<String, Long> lastRun = new HashMap<>();
    /** Per rule and its set of conditions: all true at the last reading. */
    private final Map<String, Boolean> together = new HashMap<>();

    /** The rules to run now, in list order. Call with no rules to keep up while nothing should run. */
    List<AutomationRule> step(List<AutomationRule> rules, Sample s) {
        Set<AutomationTrigger> events = events(s);
        List<AutomationRule> out = new ArrayList<>();
        for (AutomationRule rule : rules) {
            AutomationTrigger trigger = rule.trigger();
            if (!rule.enabled || trigger == null || rule.steps.isEmpty()) {
                continue;
            }
            List<AutomationRule.Condition> conditions = rule.conditions();
            boolean fires = conditions.size() > 1 ? allCameTrue(rule, conditions, s)
                    : trigger.measure == null
                    ? events.contains(trigger) && settled(rule, trigger, s.at)
                    : crossed(rule, trigger, reading(trigger.measure, s), s.at);
            if (fires) {
                out.add(rule);
            }
        }
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

    private boolean allCameTrue(AutomationRule rule, List<AutomationRule.Condition> conditions, Sample s) {
        StringBuilder key = new StringBuilder(rule.id);
        Boolean all = Boolean.TRUE;
        for (AutomationRule.Condition condition : conditions) {
            key.append('|').append(condition.trigger).append(':').append(condition.value);
            Boolean now = holds(rule, condition, s);
            if (now == null) {
                all = null;
            } else if (!now && all != null) {
                all = Boolean.FALSE;
            }
        }
        if (all == null) {
            return false;
        }
        String id = key.toString();
        Boolean was = together.get(id);
        if (was == null || !all || was) {
            together.put(id, all);
            return false;
        }
        Long last = lastRun.get(rule.id);
        if (last != null && s.at - last < NUMBER_GAP_MS) {
            return false;
        }
        together.put(id, true);
        lastRun.put(rule.id, s.at);
        return true;
    }

    /** Whether a condition is true now; null while the car has not said. */
    private Boolean holds(AutomationRule rule, AutomationRule.Condition condition, Sample s) {
        AutomationTrigger trigger = condition.trigger();
        switch (trigger) {
            case CAR_ON: return carOn;
            case CAR_OFF: return carOn == null ? null : !carOn;
            case GEAR_P: return gear == null ? null : "P".equals(gear);
            case GEAR_D: return gear == null ? null : "D".equals(gear);
            case GEAR_R: return gear == null ? null : "R".equals(gear);
            case DARK: return dayNight == null ? null : dayNight == Signal.NIGHT;
            case LIGHT: return dayNight == null ? null : dayNight != Signal.NIGHT;
            case RAIN_START: return rain;
            case RAIN_STOP: return rain == null ? null : !rain;
            default: return past(rule, condition, reading(trigger.measure, s));
        }
    }

    /** Whether a number is past its limit, with the same margin as {@link #crossed}. */
    private Boolean past(AutomationRule rule, AutomationRule.Condition condition, Float reading) {
        AutomationTrigger trigger = condition.trigger();
        String key = rule.id + ":" + condition.trigger + ":" + condition.value;
        Boolean was = past.get(key);
        if (reading == null) {
            return was;
        }
        float limit = condition.value;
        boolean beyond = trigger.rising ? reading >= limit : reading <= limit;
        float back = trigger.measure.hysteresis;
        boolean now = was == null || !was ? beyond
                : !(trigger.rising ? reading <= limit - back : reading >= limit + back);
        past.put(key, now);
        return now;
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
