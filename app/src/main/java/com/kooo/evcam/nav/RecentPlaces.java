package com.kooo.evcam.nav;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.trip.DestinationLink;

import java.util.ArrayList;
import java.util.List;

/**
 * Places the driver looked for or the phone sent, newest first. A place from the phone waits here
 * until the driver picks it on the Navigation screen: nothing is routed on its own. Saved as JSON.
 */
public final class RecentPlaces {

    /** Kept at most. */
    static final int KEEP = 8;
    /** Closer than this (about 30 m) is the same place: the older one goes. */
    private static final double SAME_DEG = 0.0003;

    /** One place. */
    public static final class Place {
        public String name;
        public double lat;
        public double lng;
        /** Sent from the phone (or 7xDash web), not looked up here. */
        public boolean shared;
        public long at;

        public DestinationLink.Place destination() {
            return new DestinationLink.Place(lat, lng, name == null ? "" : name);
        }
    }

    private static final Gson GSON = new Gson();

    private RecentPlaces() {
    }

    public static List<Place> load(Context context) {
        return parse(new AppConfig(context).getRecentPlaces());
    }

    /** Puts {@code place} on top. */
    public static void add(Context context, DestinationLink.Place place, boolean shared) {
        AppConfig config = new AppConfig(context);
        List<Place> places = parse(config.getRecentPlaces());
        add(places, place, shared, System.currentTimeMillis());
        config.setRecentPlaces(GSON.toJson(places));
    }

    static void add(List<Place> places, DestinationLink.Place place, boolean shared, long now) {
        if (place == null) {
            return;
        }
        for (int i = places.size() - 1; i >= 0; i--) {
            Place p = places.get(i);
            if (Math.abs(p.lat - place.lat) < SAME_DEG && Math.abs(p.lng - place.lng) < SAME_DEG) {
                places.remove(i);
            }
        }
        Place p = new Place();
        p.name = place.name;
        p.lat = place.lat;
        p.lng = place.lng;
        p.shared = shared;
        p.at = now;
        places.add(0, p);
        while (places.size() > KEEP) {
            places.remove(places.size() - 1);
        }
    }

    /** A damaged or empty string gives none. */
    static List<Place> parse(String json) {
        List<Place> out = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return out;
        }
        try {
            List<Place> read = GSON.fromJson(json, new TypeToken<List<Place>>() { }.getType());
            if (read != null) {
                for (Place place : read) {
                    if (place != null) {
                        out.add(place);
                    }
                }
            }
        } catch (JsonParseException e) {
            out.clear();
        }
        return out;
    }
}
