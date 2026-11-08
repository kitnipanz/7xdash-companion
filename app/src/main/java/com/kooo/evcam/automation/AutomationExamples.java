package com.kooo.evcam.automation;

import com.kooo.evcam.nav.SavedPlaces;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Ready-made automations to start from: a tap and a change, not a blank page. */
final class AutomationExamples {

    private AutomationExamples() {
    }

    static List<AutomationRule> all() {
        return all(Collections.emptyList());
    }

    /** With saved places there are examples about the first of them. */
    static List<AutomationRule> all(List<SavedPlaces.Place> places) {
        List<AutomationRule> out = new ArrayList<>();
        if (!places.isEmpty()) {
            int place = places.get(0).id;
            AutomationRule commute = rule(AutomationTrigger.CAR_ON, 0,
                    step(AutomationAction.NAVIGATE, Integer.toString(place)));
            commute.also.add(new AutomationRule.Condition(AutomationTrigger.DAYS.key, AutomationClock.WEEKDAYS));
            commute.also.add(new AutomationRule.Condition(AutomationTrigger.TIME_BETWEEN.key,
                    AutomationClock.span(7 * 60, 9 * 60)));
            out.add(commute);
            out.add(rule(AutomationTrigger.PLACE_ARRIVE, place, step(AutomationAction.AC, AutomationAction.OFF)));
        }
        out.add(rule(AutomationTrigger.SPEED_ABOVE, 100, step(AutomationAction.DRIVE_MODE, "sport")));
        out.add(rule(AutomationTrigger.GEAR_P, 0, step(AutomationAction.DRIVE_MODE, "comfort")));
        out.add(rule(AutomationTrigger.DARK, 0, step(AutomationAction.DIM, "50")));
        out.add(rule(AutomationTrigger.LIGHT, 0, step(AutomationAction.DIM, AutomationAction.OFF)));
        out.add(rule(AutomationTrigger.RAIN_START, 0, step(AutomationAction.AC, AutomationAction.ON)));
        AutomationRule darkRain = rule(AutomationTrigger.DARK, 0, step(AutomationAction.DRIVE_MODE, "comfort"));
        darkRain.also.add(new AutomationRule.Condition(AutomationTrigger.RAIN_START.key, 0));
        out.add(darkRain);
        out.add(rule(AutomationTrigger.CAR_ON, 0, step(AutomationAction.MOOD_LIGHT, AutomationAction.ON)));
        AutomationRule morning = rule(AutomationTrigger.CAR_ON, 0, step(AutomationAction.DRIVE_MODE, "comfort"));
        morning.also.add(new AutomationRule.Condition(AutomationTrigger.DAYS.key, AutomationClock.WEEKDAYS));
        morning.also.add(new AutomationRule.Condition(AutomationTrigger.TIME_BETWEEN.key,
                AutomationClock.span(6 * 60, 10 * 60)));
        out.add(morning);
        out.add(rule(AutomationTrigger.TIME_AT, 22 * 60, step(AutomationAction.DIM, "70")));
        AutomationRule reverse = rule(AutomationTrigger.GEAR_R, 0, step(AutomationAction.CAR_AMBIENT, "ffffff"));
        reverse.also.add(new AutomationRule.Condition(AutomationTrigger.DARK.key, 0));
        out.add(reverse);
        AutomationRule fastRain = rule(AutomationTrigger.RAIN_START, 0, step(AutomationAction.DRIVE_MODE, "comfort"));
        fastRain.also.add(new AutomationRule.Condition(AutomationTrigger.SPEED_ABOVE.key, 100));
        out.add(fastRain);
        out.add(rule(AutomationTrigger.BATTERY_BELOW, 15, step(AutomationAction.AC, AutomationAction.OFF)));
        AutomationRule hot = rule(AutomationTrigger.CAR_ON, 0, step(AutomationAction.AC, AutomationAction.ON));
        hot.also.add(new AutomationRule.Condition(AutomationTrigger.OUTSIDE_ABOVE.key, 35));
        out.add(hot);
        return out;
    }

    private static AutomationRule.Step step(AutomationAction action, String choice) {
        return new AutomationRule.Step(action.key, choice);
    }

    private static AutomationRule rule(AutomationTrigger trigger, int value, AutomationRule.Step... steps) {
        AutomationRule rule = new AutomationRule();
        rule.trigger = trigger.key;
        rule.value = value;
        rule.steps.addAll(Arrays.asList(steps));
        return rule;
    }
}
