package com.kooo.evcam.nav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.trip.DestinationLink;

import org.junit.Test;

public class SavedTripTest {

    private static final long AT = 1_800_000_000_000L;

    private static SavedTrip trip(int tries) {
        return new SavedTrip(new DestinationLink.Place(13.7563, 100.5018, "Central, Bangna"), true, tries, AT);
    }

    @Test
    public void comesBackAsItWasStored() {
        SavedTrip back = SavedTrip.restore(trip(1).store());
        assertEquals(13.7563, back.place.lat, 0.000001);
        assertEquals(100.5018, back.place.lng, 0.000001);
        assertEquals("Central, Bangna", back.place.name);
        assertTrue(back.guiding);
        assertEquals(1, back.tries);
        assertEquals(AT, back.savedAt);
    }

    @Test
    public void aPlanNotStartedStaysNotStarted() {
        SavedTrip plan = new SavedTrip(new DestinationLink.Place(1, 2, ""), false, 0, AT);
        assertFalse(SavedTrip.restore(plan.store()).guiding);
    }

    @Test
    public void unreadableIsNothing() {
        assertNull(SavedTrip.restore(null));
        assertNull(SavedTrip.restore(""));
        assertNull(SavedTrip.restore("1,0"));
        assertNull(SavedTrip.restore("1,x,1.0,2.0,5,a"));
        assertNull(SavedTrip.restore("1,0,north,2.0,5,a"));
    }

    @Test
    public void anOldTripStaysGone() {
        assertTrue(trip(0).worthResuming(AT + SavedTrip.KEEP_MS - 1));
        assertFalse(trip(0).worthResuming(AT + SavedTrip.KEEP_MS));
    }

    @Test
    public void aPlaceThatKeepsCrashingIsDropped() {
        assertTrue(trip(SavedTrip.MAX_TRIES - 1).worthResuming(AT));
        assertFalse(trip(SavedTrip.MAX_TRIES).worthResuming(AT));
    }
}
