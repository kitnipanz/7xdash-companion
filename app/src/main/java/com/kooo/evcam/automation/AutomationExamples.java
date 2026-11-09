package com.kooo.evcam.automation;

import com.kooo.evcam.nav.SavedPlaces;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Ready-made automations for the empty list, so the first one is a tap and a change, not a blank page. */
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
            out.add(rule(AutomationTrigger.PLACE_ARRIVE, place, step(AutomationAction.AC, AutomationAction.OFF)));
            out.add(rule(AutomationTrigger.PLACE_LEAVE, place, step(AutomationAction.MOOD_LIGHT, AutomationAction.ON)));
        }
        out.add(rule(AutomationTrigger.SPEED_ABOVE, 100, step(AutomationAction.DRIVE_MODE, "sport")));
        out.add(rule(AutomationTrigger.GEAR_P, 0, step(AutomationAction.DRIVE_MODE, "comfort")));
        out.add(rule(AutomationTrigger.DARK, 0, step(AutomationAction.DIM, "50")));
        out.add(rule(AutomationTrigger.LIGHT, 0, step(AutomationAction.DIM, AutomationAction.OFF)));
        out.add(rule(AutomationTrigger.RAIN_START, 0, step(AutomationAction.AC, AutomationAction.ON)));
        out.add(rule(AutomationTrigger.CAR_ON, 0, step(AutomationAction.MOOD_LIGHT, AutomationAction.ON)));
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
