package com.kooo.evcam.trip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class DestinationLinkTest {

    private static void at(DestinationLink.Place p, double lat, double lng) {
        assertNotNull(p);
        assertEquals(lat, p.lat, 1e-6);
        assertEquals(lng, p.lng, 1e-6);
    }

    @Test
    public void googlePlaceLinkUsesThePinNotTheMapCentre() {
        DestinationLink.Place p = DestinationLink.parse("https://www.google.com/maps/place/Central+World/"
                + "@13.7470,100.5390,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d13.746600!4d100.539800");
        at(p, 13.7466, 100.5398);
        assertEquals("Central World", p.name);
    }

    @Test
    public void googleDirectionsLink() {
        at(DestinationLink.parse("https://www.google.com/maps/dir/?api=1&destination=12.9236,100.8825"),
                12.9236, 100.8825);
    }

    @Test
    public void appleMapsShareTextWithNameOnTop() {
        DestinationLink.Place p = DestinationLink.parse("Hua Hin Night Market\n"
                + "https://maps.apple.com/?address=Hua%20Hin&ll=12.5707,99.9573&q=Hua%20Hin%20Night%20Market");
        at(p, 12.5707, 99.9573);
        assertEquals("Hua Hin Night Market", p.name);
    }

    @Test
    public void appleCoordinateKey() {
        at(DestinationLink.parse("https://maps.apple.com/place?coordinate=18.7883,98.9853&name=Old%20City"),
                18.7883, 98.9853);
    }

    @Test
    public void plainCoordinates() {
        at(DestinationLink.parse(" 13.7563, 100.5018 "), 13.7563, 100.5018);
        at(DestinationLink.parse("-33.8688,151.2093"), -33.8688, 151.2093);
    }

    @Test
    public void shortLinksAndNonsenseHaveNoPlace() {
        assertNull(DestinationLink.parse("https://maps.app.goo.gl/AbCdEf123"));
        assertEquals("https://maps.app.goo.gl/AbCdEf123",
                DestinationLink.firstUrl("Somewhere nice https://maps.app.goo.gl/AbCdEf123"));
        assertNull(DestinationLink.parse("hello"));
        assertNull(DestinationLink.parse("95.0, 10.0"));
        assertNull(DestinationLink.parse(""));
        assertNull(DestinationLink.parse(null));
    }

    @Test
    public void storeAndRestore() {
        DestinationLink.Place p = new DestinationLink.Place(13.5, 100.25, "Home, sweet home");
        String stored = p.store(1234L);
        DestinationLink.Place back = DestinationLink.Place.restore(stored);
        at(back, 13.5, 100.25);
        assertEquals("Home, sweet home", back.name);
        assertEquals(1234L, DestinationLink.Place.storedAt(stored));
        assertNull(DestinationLink.Place.restore(""));
        assertNull(DestinationLink.Place.restore("x,y,z"));
    }

    @Test
    public void pageFallbacks() {
        at(RouteLookup.fromPage("<script>{\"latitude\":12.34,\"longitude\":101.5}</script>"), 12.34, 101.5);
        at(RouteLookup.fromPage("<a href=\"https://www.google.com/maps/place/X/data=!3d14.1!4d100.6\">"), 14.1, 100.6);
        assertNull(RouteLookup.fromPage("<html>nothing</html>"));
    }
}
