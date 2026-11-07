package com.kooo.evcam.trip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class RouteLookupTest {

    @Test
    public void searchAnswerGivesTheFirstPlaceWithoutItsName() {
        String answer = "{\"results\":[{\"poi\":{\"name\":\"Siam Paragon\"},\"position\":{\"lat\":13.7461302,\"lon\":100.5347792}},"
                + "{\"poi\":{\"name\":\"Other\"},\"position\":{\"lat\":13.7473,\"lon\":100.5396}}]}";
        DestinationLink.Place p = RouteLookup.fromSearch(answer);
        assertEquals(13.7461302, p.lat, 1e-7);
        assertEquals(100.5347792, p.lng, 1e-7);
        assertEquals("", p.name);
        assertNull(RouteLookup.fromSearch("{\"results\":[]}"));
        assertNull(RouteLookup.fromSearch("not json"));
        assertNull(RouteLookup.fromSearch(null));
    }

    @Test
    public void staticMapCentreIsNotThePlace() {
        // Google's page for a link without a pin: only the area shown, often where the reader is
        String page = "<meta content=\"https://maps.google.com/maps/api/staticmap?center=13.7658368%2C100.564992"
                + "&amp;zoom=10&amp;size=900x900\" itemprop=\"image\">";
        assertNull(RouteLookup.fromPage(page));
    }
}
