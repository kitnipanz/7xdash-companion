package com.kooo.evcam.nav;

import com.kooo.evcam.trip.DestinationLink;

import java.util.Locale;

/**
 * The trip on when the app stopped, so a crash does not lose the destination. It is kept until End or
 * arrival. {@link #tries} counts the times it was brought back without lasting a minute: a place that
 * itself makes the app crash must not take the app down at every start.
 */
final class SavedTrip {

    /** Older than this is yesterday's trip, not a crash. It is saved again each minute of the trip. */
    static final long KEEP_MS = 60 * 60_000L;
    static final int MAX_TRIES = 2;

    final DestinationLink.Place place;
    final boolean guiding;
    final int tries;
    final long savedAt;

    SavedTrip(DestinationLink.Place place, boolean guiding, int tries, long savedAt) {
        this.place = place;
        this.guiding = guiding;
        this.tries = tries;
        this.savedAt = savedAt;
    }

    /** "guiding,tries,lat,lng,savedAt,name": the form AppConfig keeps. */
    String store() {
        return String.format(Locale.US, "%d,%d,%s", guiding ? 1 : 0, tries, place.store(savedAt));
    }

    /** The other way round; null when there is nothing (or nothing readable) stored. */
    static SavedTrip restore(String stored) {
        if (stored == null) {
            return null;
        }
        String[] parts = stored.split(",", 3);
        if (parts.length < 3) {
            return null;
        }
        DestinationLink.Place place = DestinationLink.Place.restore(parts[2]);
        if (place == null) {
            return null;
        }
        try {
            return new SavedTrip(place, parts[0].equals("1"), Integer.parseInt(parts[1]),
                    DestinationLink.Place.storedAt(parts[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Should this trip come back at {@code nowMs}? */
    boolean worthResuming(long nowMs) {
        return tries < MAX_TRIES && nowMs - savedAt < KEEP_MS;
    }
}
