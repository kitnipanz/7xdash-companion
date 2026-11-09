package com.kooo.evcam.overlay;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class MacroPadDockTest {

    @Test
    public void sitsRightUnderThePanelLeftEdgesLinedUp() {
        assertArrayEquals(new int[]{300, 310}, MacroPadDock.place(new int[]{300, 100, 500, 200}, 500, 150, 1000, 10));
    }

    @Test
    public void goesAboveWhenThereIsNoRoomUnder() {
        assertArrayEquals(new int[]{0, 540}, MacroPadDock.place(new int[]{0, 700, 500, 200}, 500, 150, 1000, 10));
    }

    @Test
    public void staysOnScreenWhenNeitherFits() {
        assertArrayEquals(new int[]{0, 600}, MacroPadDock.place(new int[]{0, 100, 500, 800}, 500, 400, 1000, 10));
    }
}
