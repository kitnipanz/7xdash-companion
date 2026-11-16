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
     * In order: in D after 18:00, open the sunroof shade and dim the screen to 30%; arrive home,
     * tailgate opens all the way; leave home, tailgate stops at 60% (low garages, car parks); P away
     * from home, remember where the car is. Then rain in the dark turns on the rear fog light, and
     * dark turns on the mood light. The home ones need a saved place ({@link #home}); with none
     * saved, P remembers where the car is every time.
     */
    static List<AutomationRule> all(List<SavedPlaces.Place> places) {
        List<AutomationRule> out = new ArrayList<>();
        AutomationRule evening = rule(AutomationTrigger.GEAR_D, 0,
                step(AutomationAction.SUNROOF_SHADE, "100"), step(AutomationAction.DIM, "30"));
        evening.setCondition(AutomationCondition.Group.TIME, AutomationCondition.AFTER_TIME, 18 * 60);
        out.add(evening);
        SavedPlaces.Place home = home(places);
        if (home != null) {
            out.add(rule(AutomationTrigger.PLACE_ARRIVE, home.id, step(AutomationAction.TAILGATE_HEIGHT, "100")));
            out.add(rule(AutomationTrigger.PLACE_LEAVE, home.id, step(AutomationAction.TAILGATE_HEIGHT, "60")));
            AutomationRule parked = rule(AutomationTrigger.GEAR_P, 0,
                    step(AutomationAction.SHORTCUT, com.kooo.evcam.input.ShortcutAction.WHERE_PARKED.key));
            parked.setCondition(AutomationCondition.Group.PLACE, AutomationCondition.AWAY_FROM_PLACE, home.id);
            out.add(parked);
        } else {
            // No home saved: remember where the car is every time it goes into P
            out.add(rule(AutomationTrigger.GEAR_P, 0,
                    step(AutomationAction.SHORTCUT, com.kooo.evcam.input.ShortcutAction.WHERE_PARKED.key)));
        }
        AutomationRule fog = rule(AutomationTrigger.RAIN_START, 0, step(AutomationAction.REAR_FOG, AutomationAction.ON));
        fog.setCondition(AutomationCondition.Group.DAYLIGHT, AutomationCondition.DARK, 0);
        out.add(fog);
        out.add(rule(AutomationTrigger.DARK, 0, step(AutomationAction.MOOD_LIGHT, AutomationAction.ON)));
        return out;
    }

    /** The saved place called Home (or บ้าน), else the first one; null with none saved. */
    static SavedPlaces.Place home(List<SavedPlaces.Place> places) {
        for (SavedPlaces.Place place : places) {
            String name = place.name == null ? "" : place.name.trim().toLowerCase(java.util.Locale.ROOT);
            if (name.equals("home") || name.equals("บ้าน") || name.startsWith("home ") || name.startsWith("บ้าน")) {
                return place;
            }
        }
        return places.isEmpty() ? null : places.get(0);
    }

    /** Does the driver already have this example (same when, conditions and steps)? */
    static boolean alreadyHave(List<AutomationRule> rules, AutomationRule example) {
        for (AutomationRule rule : rules) {
            if (same(rule, example)) {
                return true;
            }
        }
        return false;
    }

    private static boolean same(AutomationRule a, AutomationRule b) {
        if (!java.util.Objects.equals(a.trigger, b.trigger) || a.value != b.value
                || a.steps.size() != b.steps.size() || a.conditions.size() != b.conditions.size()) {
            return false;
        }
        for (int i = 0; i < a.steps.size(); i++) {
            if (!java.util.Objects.equals(a.steps.get(i).action, b.steps.get(i).action)
                    || !java.util.Objects.equals(a.steps.get(i).choice, b.steps.get(i).choice)) {
                return false;
            }
        }
        for (AutomationRule.Condition c : b.conditions) {
            AutomationRule.Condition mine = c.kind() == null ? null : a.condition(c.kind().group);
            if (mine == null || !java.util.Objects.equals(mine.key, c.key) || mine.value != c.value) {
                return false;
            }
        }
        return true;
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
