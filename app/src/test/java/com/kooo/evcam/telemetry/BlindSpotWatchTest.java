package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class BlindSpotWatchTest {

    @Test
    public void eachSideWarnsOnItsLearnedValue() {
        BlindSpotWatch.Learned l = new BlindSpotWatch.Learned(0x2808_1700, 1, 0x2808_1800, 1);
        assertEquals(0, BlindSpotWatch.sides(l, 0, 0));
        assertEquals(VehicleState.TURN_LEFT, BlindSpotWatch.sides(l, 1, 0));
        assertEquals(VehicleState.TURN_RIGHT, BlindSpotWatch.sides(l, 0, 1));
        assertEquals(3, BlindSpotWatch.sides(l, 1, 1));
    }

    @Test
    public void oneSettingCanCarryBothSidesByValue() {
        BlindSpotWatch.Learned l = new BlindSpotWatch.Learned(0x2808_1700, 1, 0x2808_1700, 2);
        assertEquals(VehicleState.TURN_LEFT, BlindSpotWatch.sides(l, 1, 1));
        assertEquals(VehicleState.TURN_RIGHT, BlindSpotWatch.sides(l, 2, 2));
    }

    @Test
    public void unlearnedOrUnreadableSideNeverWarns() {
        BlindSpotWatch.Learned l = new BlindSpotWatch.Learned(0x2808_1700, 1, 0, 0);
        assertEquals(0, BlindSpotWatch.sides(l, null, 1));
    }
}
