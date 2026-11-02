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
    public void unreadableLearnedSideNeverWarns() {
        BlindSpotWatch.Learned l = new BlindSpotWatch.Learned(0x2808_1700, 1, 0x2808_1800, 1);
        assertEquals(0, BlindSpotWatch.sides(l, null, null));
    }

    @Test
    public void nothingLearnedUsesTheRctaFlags() {
        BlindSpotWatch.Learned none = new BlindSpotWatch.Learned(0, 0, 0, 0);
        // 255 is what the car reads with nothing to report, 0 is quiet
        assertEquals(0, BlindSpotWatch.sides(none, 255, 255));
        assertEquals(0, BlindSpotWatch.sides(none, 0, 0));
        assertEquals(0, BlindSpotWatch.sides(none, null, null));
        assertEquals(VehicleState.TURN_LEFT, BlindSpotWatch.sides(none, 1, 0));
        assertEquals(VehicleState.TURN_RIGHT, BlindSpotWatch.sides(none, 255, 2));
        assertEquals(3, BlindSpotWatch.sides(none, 1, 1));
    }

    @Test
    public void aLearnedSideWinsOverRcta() {
        BlindSpotWatch.Learned left = new BlindSpotWatch.Learned(0x2808_1700, 3, 0, 0);
        assertEquals(0, BlindSpotWatch.sides(left, 1, 0));
        assertEquals(VehicleState.TURN_LEFT | VehicleState.TURN_RIGHT, BlindSpotWatch.sides(left, 3, 1));
    }
}
