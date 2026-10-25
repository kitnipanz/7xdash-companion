package com.kooo.evcam.light;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Named groups of mood lights, saved as one JSON string ({@code AppConfig}). Each group has its
 * own lights, colour and gear / speed rules; the lights in one group act together.
 *
 * <p>A light is in one group at most: each light takes one connection at a time. Before groups
 * there was one set of lights and one set of rules; {@link #migrate} turns that into the first
 * group. Pure, so it is tested ({@code MoodLightGroupsTest}).</p>
 */
public final class MoodLightGroups {

    public static final int MAX_GROUPS = 4;

    public static final class Group {
        public String id;
        public String name;
        public List<String> lights = new ArrayList<>();
        public GearLightRules rules;
        /** Last colour set on the screen, {@code 0xRRGGBB}. */
        public int color = 0x8000FF;
        /** Last brightness set on the screen, 0–100. */
        public int brightness = 80;

        public Group() {
        }

        Group(String id, String name) {
            this.id = id;
            this.name = name;
            this.rules = GearLightRules.defaults();
        }
    }

    private static final Gson GSON = new Gson();

    public List<Group> groups = new ArrayList<>();

    /**
     * @param json      the saved groups, empty before the first save
     * @param firstName name for the group made from the old setup, or for a first empty one
     */
    public static MoodLightGroups parse(String json, String firstName) {
        MoodLightGroups out = null;
        if (json != null && !json.isEmpty()) {
            try {
                out = GSON.fromJson(json, MoodLightGroups.class);
            } catch (JsonParseException ignored) {
                // fall through to one empty group
            }
        }
        if (out == null || out.groups == null) {
            out = new MoodLightGroups();
        }
        List<String> seen = new ArrayList<>();
        out.groups.removeIf(g -> g == null);
        for (Group g : out.groups) {
            if (g.id == null || g.id.isEmpty()) {
                g.id = newId();
            }
            if (g.name == null || g.name.trim().isEmpty()) {
                g.name = firstName;
            }
            if (g.lights == null) {
                g.lights = new ArrayList<>();
            }
            // One group per light: a later duplicate loses it
            g.lights.removeIf(a -> a == null || a.isEmpty() || seen.contains(a));
            seen.addAll(g.lights);
            if (g.rules == null) {
                g.rules = GearLightRules.defaults();
            }
            if (g.rules.bands == null) {
                g.rules.bands = new ArrayList<>();
            }
            g.rules.sortBands();
        }
        if (out.groups.isEmpty()) {
            out.groups.add(new Group(newId(), firstName));
        }
        return out;
    }

    /** The setup from before groups, as the first group. */
    public static MoodLightGroups migrate(String name, List<String> lights, String rulesJson, int color, int brightness) {
        MoodLightGroups out = new MoodLightGroups();
        Group g = new Group(newId(), name);
        g.lights.addAll(lights);
        g.rules = GearLightRules.parse(rulesJson);
        g.color = color;
        g.brightness = brightness;
        out.groups.add(g);
        return parse(out.toJson(), name);
    }

    public String toJson() {
        for (Group g : groups) {
            if (g.rules != null) {
                g.rules.sortBands();
            }
        }
        return GSON.toJson(this);
    }

    public Group find(String id) {
        for (Group g : groups) {
            if (g.id.equals(id)) {
                return g;
            }
        }
        return null;
    }

    /** Which group has this light, or null. */
    public Group owner(String address) {
        for (Group g : groups) {
            if (g.lights.contains(address)) {
                return g;
            }
        }
        return null;
    }

    public int lightCount() {
        int n = 0;
        for (Group g : groups) {
            n += g.lights.size();
        }
        return n;
    }

    public Group add(String name) {
        Group g = new Group(newId(), name);
        groups.add(g);
        return g;
    }

    /** Remove a group; the last one stays. Its lights are then in no group. */
    public boolean remove(String id) {
        if (groups.size() <= 1) {
            return false;
        }
        return groups.removeIf(g -> g.id.equals(id));
    }

    /** Put a light in this group, taking it out of any other. */
    public void moveLight(String address, Group to) {
        for (Group g : groups) {
            g.lights.remove(address);
        }
        to.lights.add(address);
    }

    /** Saved groups, or the setup from before groups as the first one. */
    public static MoodLightGroups load(com.kooo.evcam.AppConfig config, String firstName) {
        String json = config.getMoodLightGroups();
        if (json.isEmpty()) {
            return migrate(firstName, config.getMoodLightAddresses(), config.getMoodLightGearRules(),
                    config.getMoodLightColor(), config.getMoodLightBrightness());
        }
        return parse(json, firstName);
    }

    public void save(com.kooo.evcam.AppConfig config) {
        config.setMoodLightGroups(toJson());
    }

    private static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
