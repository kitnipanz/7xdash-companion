package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class BlindSpotLogTest {

    @Test
    public void contextReadsGearSpeedTurnAndHazard() {
        VehicleState s = VehicleState.empty().edit().gear("D").speedKmh(42.4f)
                .turnSignal(VehicleState.TURN_LEFT).hazard(false).build();
        assertEquals("D, 42 km/h, turn L, hazard off", BlindSpotLog.context(s));
    }

    @Test
    public void unknownsShowAsQuestionMarks() {
        assertEquals("?, ? km/h, turn ?, hazard ?", BlindSpotLog.context(VehicleState.empty()));
        assertEquals("?", BlindSpotLog.context(null));
    }
}
