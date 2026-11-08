package com.kooo.evcam.nav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.trip.DestinationLink;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class RecentPlacesTest {

    @Test
    public void newestFirstAndKeepsOnlyAFew() {
        List<RecentPlaces.Place> list = new ArrayList<>();
        for (int i = 0; i < RecentPlaces.KEEP + 3; i++) {
            RecentPlaces.add(list, new DestinationLink.Place(13 + i * 0.01, 100, "p" + i), false, i);
        }
        assertEquals(RecentPlaces.KEEP, list.size());
        assertEquals("p" + (RecentPlaces.KEEP + 2), list.get(0).name);
    }

    @Test
    public void samePlaceMovesToTopOnce() {
        List<RecentPlaces.Place> list = new ArrayList<>();
        RecentPlaces.add(list, new DestinationLink.Place(13.7, 100.5, "Office"), true, 1);
        RecentPlaces.add(list, new DestinationLink.Place(13.8, 100.5, "Home"), false, 2);
        RecentPlaces.add(list, new DestinationLink.Place(13.70001, 100.50001, "Office"), false, 3);
        assertEquals(2, list.size());
        assertEquals("Office", list.get(0).name);
        assertFalse(list.get(0).shared);
    }

    @Test
    public void damagedJsonGivesNone() {
        assertTrue(RecentPlaces.parse("{oops").isEmpty());
        assertTrue(RecentPlaces.parse(null).isEmpty());
    }
}
