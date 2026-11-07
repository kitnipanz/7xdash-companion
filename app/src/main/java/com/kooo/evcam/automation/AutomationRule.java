package com.kooo.evcam.automation;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One automation: when {@link #trigger} happens, run {@link #steps} in order. Saved as JSON. */
public final class AutomationRule {

    /** One thing to do. {@code choice} depends on the action ({@link AutomationAction}). */
    public static final class Step {
        public String action;
        public String choice;

        public Step() {
        }

        public Step(String action, String choice) {
            this.action = action;
            this.choice = choice;
        }
    }

    private static final Gson GSON = new Gson();

    public String id = UUID.randomUUID().toString();
    public boolean enabled = true;
    public String trigger;
    /** The number for a trigger with a {@link AutomationTrigger#measure}. */
    public int value;
    public List<Step> steps = new ArrayList<>();

    public AutomationTrigger trigger() {
        return AutomationTrigger.fromKey(trigger);
    }

    AutomationRule copy() {
        return GSON.fromJson(GSON.toJson(this), AutomationRule.class);
    }

    /** A rule still being edited, from {@link #toDraft}: unlike a saved one it may have no trigger yet. */
    static AutomationRule fromDraft(String json) {
        return GSON.fromJson(json, AutomationRule.class);
    }

    String toDraft() {
        return GSON.toJson(this);
    }

    /** Saved rules; a damaged or empty string gives none, and rules this version cannot read are dropped. */
    public static List<AutomationRule> parseAll(String json) {
        List<AutomationRule> out = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return out;
        }
        try {
            List<AutomationRule> read = GSON.fromJson(json,
                    new TypeToken<List<AutomationRule>>() { }.getType());
            if (read != null) {
                for (AutomationRule rule : read) {
                    if (rule != null && rule.id != null && rule.trigger() != null && rule.steps != null) {
                        rule.steps.removeIf(step -> step == null || AutomationAction.fromKey(step.action) == null);
                        out.add(rule);
                    }
                }
            }
        } catch (JsonParseException e) {
            out.clear();
        }
        return out;
    }

    public static String toJson(List<AutomationRule> rules) {
        return GSON.toJson(rules);
    }
}
