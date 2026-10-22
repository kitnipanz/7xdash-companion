package com.kooo.evcam.parking;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class ParkingTextTest {

    private static ParkingText.Spot pick(String... lines) {
        return ParkingText.pick(Arrays.asList(lines));
    }

    @Test
    public void basementCodeAndZonedBay() {
        ParkingText.Spot s = pick("EXIT", "B2", "A-12");
        assertEquals("B2", s.floor);
        assertEquals("A-12", s.bay);
        assertEquals("B2 · A-12", s.label());
    }

    @Test
    public void levelWordBeatsALooseCode() {
        ParkingText.Spot s = pick("P3", "LEVEL 4");
        assertEquals("4", s.floor);
    }

    @Test
    public void storeyStyleFloor() {
        assertEquals("5F", pick("5F", "C 045").floor);
        assertEquals("C-045", pick("5F", "C 045").bay);
    }

    @Test
    public void floorPrefixedBayIsABayNotAFloor() {
        ParkingText.Spot s = pick("B2-045");
        assertEquals("", s.floor);
        assertEquals("B2-045", s.bay);
    }

    @Test
    public void bayWordWins() {
        assertEquals("125", pick("NO. 125").bay);
        assertEquals("45", pick("Bay 45", "Z9").bay);
    }

    @Test
    public void zonePrefix() {
        assertEquals("C-12", pick("ZONE C 12").bay);
    }

    @Test
    public void bareNumberOnlyWhenNothingBetter() {
        assertEquals("318", pick("318").bay);
        assertEquals("D-7", pick("318", "D7").bay);
    }

    @Test
    public void nothingUseful() {
        assertTrue(pick("EXIT", "SLOW", "", null).isEmpty());
        assertTrue(ParkingText.pick(Collections.emptyList()).isEmpty());
        assertTrue(ParkingText.pick(null).isEmpty());
    }
}
