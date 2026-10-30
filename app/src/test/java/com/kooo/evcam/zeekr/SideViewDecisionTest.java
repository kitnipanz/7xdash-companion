package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

/**
 * {@link SideViewDecision} 的单元测试：左灯只弹左、右灯只弹右、灯灭就收、原厂画面优先。
 */
public class SideViewDecisionTest {

    private static SideViewDecision.Input driving(Integer turn) {
        SideViewDecision.Input in = new SideViewDecision.Input();
        in.turnSignal = turn;
        in.hazard = false;
        in.gear = "D";
        in.stock360Shown = false;
        in.stockPopupShown = false;
        in.parkAssistOn = false;
        in.speedKmh = 60f;
        in.minSpeedKmh = 30;
        return in;
    }

    @Test
    public void leftSignalShowsLeftOnly() {
        assertEquals(LaneCycle.LEFT, SideViewDecision.decide(driving(1)));
    }

    @Test
    public void rightSignalShowsRightOnly() {
        assertEquals(LaneCycle.RIGHT, SideViewDecision.decide(driving(2)));
    }

    @Test
    public void signalOffCloses() {
        SideViewDecision.Input in = driving(0);
        in.showing = LaneCycle.LEFT;
        assertEquals(SideViewDecision.NONE, SideViewDecision.decide(in));
    }

    @Test
    public void unreadableSignalShowsNothing() {
        assertEquals(SideViewDecision.NONE, SideViewDecision.decide(driving(null)));
    }

    @Test
    public void hazardsShowNothing() {
        SideViewDecision.Input in = driving(0);
        in.hazard = true;
        assertEquals(SideViewDecision.NONE, SideViewDecision.decide(in));
    }

    @Test
    public void switchingSidesFollowsTheSignal() {
        SideViewDecision.Input in = driving(2);
        in.showing = LaneCycle.LEFT;
        assertEquals(LaneCycle.RIGHT, SideViewDecision.decide(in));
    }

    @Test
    public void reverseGearWins() {
        SideViewDecision.Input in = driving(1);
        in.gear = "R";
        assertEquals(SideViewDecision.NONE, SideViewDecision.decide(in));
    }

    @Test
    public void stockPopupWinsEvenWhenAlreadyShowing() {
        SideViewDecision.Input in = driving(1);
        in.showing = LaneCycle.LEFT;
        in.stockPopupShown = true;
        assertEquals(SideViewDecision.NONE, SideViewDecision.decide(in));
    }

    @Test
    public void stock360AndParkAssistWin() {
        SideViewDecision.Input a = driving(2);
        a.stock360Shown = true;
        assertEquals(SideViewDecision.NONE, SideViewDecision.decide(a));
        SideViewDecision.Input b = driving(2);
        b.parkAssistOn = true;
        assertEquals(SideViewDecision.NONE, SideViewDecision.decide(b));
    }

    @Test
    public void belowSpeedThresholdDoesNotOpen() {
        SideViewDecision.Input in = driving(1);
        in.speedKmh = 20f;
        assertEquals(SideViewDecision.NONE, SideViewDecision.decide(in));
    }

    @Test
    public void slowingDownDoesNotCloseAnOpenPopup() {
        SideViewDecision.Input in = driving(1);
        in.speedKmh = 20f;
        in.showing = LaneCycle.LEFT;
        assertEquals(LaneCycle.LEFT, SideViewDecision.decide(in));
    }

    @Test
    public void staysReadyOnlyInDrive() {
        org.junit.Assert.assertTrue(SideViewDecision.shouldStayReady(driving(0)));
        for (String gear : new String[]{"P", "N", "R", null}) {
            SideViewDecision.Input in = driving(0);
            in.gear = gear;
            org.junit.Assert.assertFalse("gear " + gear, SideViewDecision.shouldStayReady(in));
        }
    }

    @Test
    public void zeroThresholdOrUnknownSpeedDoesNotBlock() {
        SideViewDecision.Input a = driving(1);
        a.speedKmh = 5f;
        a.minSpeedKmh = 0;
        assertEquals(LaneCycle.LEFT, SideViewDecision.decide(a));
        SideViewDecision.Input b = driving(1);
        b.speedKmh = null;
        assertEquals(LaneCycle.LEFT, SideViewDecision.decide(b));
    }

    @Test
    public void overFactoryViewStillShowsWithStockViews() {
        SideViewDecision.Input in = driving(2);
        in.overFactoryView = true;
        in.stock360Shown = true;
        in.stockPopupShown = true;
        in.parkAssistOn = true;
        assertEquals(LaneCycle.RIGHT, SideViewDecision.decide(in));
        assertFalse(SideViewDecision.factoryViewActive(in));
    }

    @Test
    public void overFactoryViewNeverInReverse() {
        SideViewDecision.Input in = driving(1);
        in.overFactoryView = true;
        in.gear = "R";
        assertEquals(SideViewDecision.NONE, SideViewDecision.decide(in));
    }

    @Test
    public void mainScreenRightLaneHugsRightEdgeLeftLaneSitsInMiddle() {
        assertEquals(1920 - 400, SideViewPopupView.mainScreenX(false, 1920, 400));
        assertEquals((1920 - 400) / 2, SideViewPopupView.mainScreenX(true, 1920, 400));
        assertEquals(0, SideViewPopupView.mainScreenX(true, 300, 400));
    }

    @Test
    public void wideBoxIsSixteenByNineAndAtMostHalfTheScreen() {
        int[] box = SideViewPopupView.wideBox(2560, 880);
        assertEquals(1056, box[0]);
        assertEquals(594, box[1]);
        assertEquals(1280, SideViewPopupView.wideBox(2560, 2000)[0]);
    }

    private static SideViewDecision.Input parkedWithDoor(int doors) {
        SideViewDecision.Input in = driving(0);
        in.gear = "P";
        in.speedKmh = 0f;
        in.doorCamera = true;
        in.doorsOpen = doors;
        return in;
    }

    @Test
    public void openDoorWhileParkedShowsThatSide() {
        assertEquals(LaneCycle.LEFT, SideViewDecision.decide(
                parkedWithDoor(com.kooo.evcam.telemetry.VehicleState.FRONT_LEFT)));
        assertEquals(LaneCycle.RIGHT, SideViewDecision.decide(
                parkedWithDoor(com.kooo.evcam.telemetry.VehicleState.REAR_RIGHT)));
    }

    @Test
    public void doorsClosedOrSettingOffOrMovingShowsNothing() {
        assertEquals(SideViewDecision.NONE, SideViewDecision.decide(parkedWithDoor(0)));
        SideViewDecision.Input off = parkedWithDoor(com.kooo.evcam.telemetry.VehicleState.FRONT_LEFT);
        off.doorCamera = false;
        assertEquals(SideViewDecision.NONE, SideViewDecision.decide(off));
        SideViewDecision.Input moving = parkedWithDoor(com.kooo.evcam.telemetry.VehicleState.FRONT_LEFT);
        moving.gear = "D";
        moving.speedKmh = 20f;
        assertEquals(SideViewDecision.NONE, SideViewDecision.decide(moving));
    }

    @Test
    public void signalBeatsDoor() {
        SideViewDecision.Input in = parkedWithDoor(com.kooo.evcam.telemetry.VehicleState.FRONT_LEFT);
        in.gear = "D";
        in.speedKmh = 60f;
        in.turnSignal = 2;
        assertEquals(LaneCycle.RIGHT, SideViewDecision.decide(in));
    }

    @Test
    public void slowSignalFallsBackToOpenDoor() {
        SideViewDecision.Input in = parkedWithDoor(com.kooo.evcam.telemetry.VehicleState.FRONT_RIGHT);
        in.gear = "D";
        in.speedKmh = 1f;
        in.turnSignal = 1;
        assertEquals(LaneCycle.RIGHT, SideViewDecision.decide(in));
    }
}
