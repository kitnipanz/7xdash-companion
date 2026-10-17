package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HudFrameTest {

    private static final int WIDTH = 2560;
    private static final int HEIGHT = 536;

    @Test
    public void leftSignalSitsOnTheLeftHalf() {
        int[] frame = HudFrame.place(WIDTH, HEIGHT, true);
        assertTrue(frame[0] >= 0);
        assertTrue(frame[0] + frame[2] <= WIDTH / 2);
    }

    @Test
    public void rightSignalSitsOnTheRightHalf() {
        int[] frame = HudFrame.place(WIDTH, HEIGHT, false);
        assertTrue(frame[0] >= WIDTH / 2);
        assertTrue(frame[0] + frame[2] <= WIDTH);
    }

    @Test
    public void bothMirrorsStayOnOppositeEdges() {
        int[] left = HudFrame.place(1440, 480, true, 40, 45);
        int[] right = HudFrame.place(1440, 480, false, 40, 45);
        assertEquals(8, left[0]);
        assertEquals(1440 - 8, right[0] + right[2]);
        assertTrue(left[0] + left[2] < right[0]);
    }

    @Test
    public void frameStaysInsideTheStrip() {
        int[] frame = HudFrame.place(WIDTH, HEIGHT, true);
        assertTrue(frame[0] >= 0);
        assertTrue(frame[1] >= 0);
        assertTrue(frame[0] + frame[2] <= WIDTH);
        assertTrue(frame[1] + frame[3] <= HEIGHT);
    }

    @Test
    public void widerSettingStaysOnTheOuterEdge() {
        int[] narrow = HudFrame.place(1440, 480, false, 10);
        int[] wide = HudFrame.place(1440, 480, false, 27);
        assertTrue(wide[2] > narrow[2]);
        assertEquals(1440 - 8, wide[0] + wide[2]);
        assertEquals(1440 - 8, narrow[0] + narrow[2]);
        assertEquals(8, HudFrame.place(1440, 480, true, 27)[0]);
    }

    @Test
    public void tallerSettingStaysInsideAndFillsAtOneHundred() {
        int[] normal = HudFrame.place(1440, 480, true, 27, 45);
        int[] tall = HudFrame.place(1440, 480, true, 27, 100);
        assertEquals(216, normal[3]);
        assertTrue(tall[3] > normal[3]);
        assertEquals(0, tall[1]);
        assertEquals(480, tall[1] + tall[3]);
        assertEquals(8, tall[0]);
    }

    @Test
    public void bothCamerasCanPinToTheLeftEdge() {
        assertFalse(HudFrame.pinToLeftEdge(false, false));
        assertTrue(HudFrame.pinToLeftEdge(true, false));
        assertTrue(HudFrame.pinToLeftEdge(false, true));
        int[] rightOnLeft = HudFrame.place(1440, 480, HudFrame.pinToLeftEdge(false, true), 27, 45);
        assertEquals(8, rightOnLeft[0]);
    }
}
