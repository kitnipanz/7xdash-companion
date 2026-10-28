package com.kooo.evcam.light;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FogLampTest {

    @Test
    public void flipsWhatTheCarReads() {
        assertEquals(0, FogLamp.next(1));
        assertEquals(1, FogLamp.next(0));
    }

    @Test
    public void turnsOnWhenTheCarDoesNotSay() {
        assertEquals(1, FogLamp.next(null));
        assertEquals(1, FogLamp.next(2));
    }
}
