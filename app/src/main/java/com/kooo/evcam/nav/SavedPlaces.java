package com.kooo.evcam.nav;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.trip.DestinationLink;

import java.util.ArrayList;
import java.util.List;

/** Places the person named, to drive to and to use in automations. Saved as JSON. */
public final class SavedPlaces {

    /** One place. {@link #id} stays with it for good: automations keep the id, not the name. */
    public static final class Place {
        public int id;
        public String name;
        public double lat;
        public double lng;

        public DestinationLink.Place destination() {
            return new DestinationLink.Place(lat, lng, name);
        }
    }

    private static final Gson GSON = new Gson();

    private SavedPlaces() {
    }

    public static List<Place> load(Context context) {
        return parse(new AppConfig(context).getSavedPlaces());
    }

    public static void save(Context context, List<Place> places) {
        new AppConfig(context).setSavedPlaces(GSON.toJson(places));
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
                    if (place != null && place.name != null) {
                        out.add(place);
                    }
                }
            }
        } catch (JsonParseException e) {
            out.clear();
        }
        return out;
    }

    /** Adds a place with a new id, never one used before in this list. */
    public static Place add(List<Place> places, String name, double lat, double lng) {
        Place place = new Place();
        for (Place other : places) {
            place.id = Math.max(place.id, other.id);
        }
        place.id++;
        place.name = name;
        place.lat = lat;
        place.lng = lng;
        places.add(place);
        return place;
    }

    public static Place find(List<Place> places, int id) {
        for (Place place : places) {
            if (place.id == id) {
                return place;
            }
        }
        return null;
    }
}
