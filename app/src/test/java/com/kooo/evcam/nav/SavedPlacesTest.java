package com.kooo.evcam.nav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class SavedPlacesTest {

    @Test
    public void idsAreNotReusedWhileOthersRemain() {
        List<SavedPlaces.Place> places = new ArrayList<>();
        SavedPlaces.Place home = SavedPlaces.add(places, "Home", 13.7, 100.5);
        SavedPlaces.Place work = SavedPlaces.add(places, "Work", 13.8, 100.6);
        places.remove(home);
        SavedPlaces.Place gym = SavedPlaces.add(places, "Gym", 13.9, 100.7);
        assertNotEquals(work.id, gym.id);
        assertEquals("Gym", SavedPlaces.find(places, gym.id).name);
        assertNull(SavedPlaces.find(places, 99));
    }

    @Test
    public void damagedListIsEmpty() {
        assertTrue(SavedPlaces.parse("{").isEmpty());
        assertTrue(SavedPlaces.parse(null).isEmpty());
    }

    @Test
    public void aDestinationCarriesTheName() {
        SavedPlaces.Place home = SavedPlaces.add(new ArrayList<>(), "Home", 13.7, 100.5);
        assertEquals("Home", home.destination().name);
        assertEquals(13.7, home.destination().lat, 0);
    }
}
