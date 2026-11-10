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

    /**
     * Leave the first saved place (home, most likely) and set the tailgate to open to 50%; rain
     * starts while it is dark and the rear fog light comes on;
     * it gets dark and the mood light comes on. The first needs a saved place.
     */
    static List<AutomationRule> all(List<SavedPlaces.Place> places) {
        List<AutomationRule> out = new ArrayList<>();
        if (!places.isEmpty()) {
            out.add(rule(AutomationTrigger.PLACE_LEAVE, places.get(0).id,
                    step(AutomationAction.TAILGATE_HEIGHT, "50")));
        }
        AutomationRule fog = rule(AutomationTrigger.RAIN_START, 0, step(AutomationAction.REAR_FOG, AutomationAction.ON));
        fog.setCondition(AutomationCondition.Group.DAYLIGHT, AutomationCondition.DARK, 0);
        out.add(fog);
        out.add(rule(AutomationTrigger.DARK, 0, step(AutomationAction.MOOD_LIGHT, AutomationAction.ON)));
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
