package com.kooo.evcam.automation;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;

/** What ran, newest first, so a rule that did nothing on the car can be seen. Saved as JSON. */
final class AutomationHistory {

    /** Worded when it ran, so it reads the same after the rule is changed or deleted. */
    static final class Entry {
        long at;
        String when;
        String what;
        /** Why steps failed; empty when none reported a failure. */
        String failed;
        /** The Try button, not the car. */
        boolean tried;

        Entry(long at, String when, String what, String failed, boolean tried) {
            this.at = at;
            this.when = when;
            this.what = what;
            this.failed = failed;
            this.tried = tried;
        }
    }

    static final int KEEP = 50;
    private static final Gson GSON = new Gson();

    private AutomationHistory() {
    }

    /** A damaged or empty string gives none. */
    static List<Entry> parse(String json) {
        List<Entry> out = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return out;
        }
        try {
            List<Entry> read = GSON.fromJson(json, new TypeToken<List<Entry>>() { }.getType());
            if (read != null) {
                for (Entry entry : read) {
                    if (entry != null) {
                        out.add(entry);
                    }
                }
            }
        } catch (JsonParseException e) {
            out.clear();
        }
        return out;
    }

    /** {@code json} with {@code entry} on top and the oldest past {@link #KEEP} gone. */
    static String add(String json, Entry entry) {
        List<Entry> entries = parse(json);
        entries.add(0, entry);
        return GSON.toJson(entries.size() > KEEP ? entries.subList(0, KEEP) : entries);
    }
}
