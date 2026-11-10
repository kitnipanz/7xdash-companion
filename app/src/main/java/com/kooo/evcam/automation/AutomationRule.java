package com.kooo.evcam.automation;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One automation: when {@link #trigger} happens and every one of {@link #conditions} holds, run
 * {@link #steps} in order. Saved as JSON.
 */
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

    /** Something that must be true too ({@link AutomationCondition}); {@code value} depends on it. */
    public static final class Condition {
        public String key;
        public int value;

        public Condition() {
        }

        public Condition(String key, int value) {
            this.key = key;
            this.value = value;
        }

        public AutomationCondition kind() {
            return AutomationCondition.fromKey(key);
        }
    }

    private static final Gson GSON = new Gson();

    public String id = UUID.randomUUID().toString();
    public boolean enabled = true;
    public String trigger;
    /** The number for a trigger with a {@link AutomationTrigger#measure}. */
    public int value;
    public List<Step> steps = new ArrayList<>();
    /** All must hold. Rules saved before there were conditions have none. */
    public List<Condition> conditions = new ArrayList<>();

    public AutomationTrigger trigger() {
        return AutomationTrigger.fromKey(trigger);
    }

    /** This rule's condition from the group, or null. */
    public Condition condition(AutomationCondition.Group group) {
        for (Condition condition : conditions) {
            AutomationCondition kind = condition.kind();
            if (kind != null && kind.group == group) {
                return condition;
            }
        }
        return null;
    }

    /** Sets the rule's one condition for that group, or takes it away when {@code kind} is null. */
    public void setCondition(AutomationCondition.Group group, AutomationCondition kind, int value) {
        conditions.removeIf(c -> c.kind() == null || c.kind().group == group);
        if (kind != null) {
            conditions.add(new Condition(kind.key, value));
        }
    }

    /** A rule that came from an older version, or by hand, without conditions or with ones this version does not know. */
    void tidy() {
        if (conditions == null) {
            conditions = new ArrayList<>();
        }
        conditions.removeIf(c -> c == null || c.kind() == null);
    }

    AutomationRule copy() {
        return GSON.fromJson(GSON.toJson(this), AutomationRule.class);
    }

    /** A rule still being edited, from {@link #toDraft}: unlike a saved one it may have no trigger yet. */
    static AutomationRule fromDraft(String json) {
        AutomationRule rule = GSON.fromJson(json, AutomationRule.class);
        rule.tidy();
        return rule;
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
                        rule.tidy();
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
