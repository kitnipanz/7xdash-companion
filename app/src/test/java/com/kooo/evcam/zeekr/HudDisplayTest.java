package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class HudDisplayTest {

    @Test
    public void nameHudWinsOverTheKnownId() {
        assertEquals(2, HudDisplay.pick(Arrays.asList(
                main(0, "built-in", 3200, 2000),
                screen(5, "cluster", 1920, 720),
                screen(2, "Windscreen HUD", 2560, 536))));
    }

    @Test
    public void thisCarUsesDisplayThreeAt1440By480() {
        assertEquals(3, HudDisplay.pick(Arrays.asList(
                main(0, "built-in", 3200, 2000),
                screen(3, "", HudDisplay.ZEEKR_7X_WIDTH, HudDisplay.ZEEKR_7X_HEIGHT),
                screen(5, "", 2560, 536),
                screen(1, "passenger", 1920, 720))));
    }

    @Test
    public void sameSizeStillCountsWhenTheIdMoved() {
        assertEquals(4, HudDisplay.pick(Arrays.asList(
                main(0, "built-in", 3200, 2000),
                screen(4, "panel", HudDisplay.ZEEKR_7X_WIDTH, HudDisplay.ZEEKR_7X_HEIGHT),
                screen(5, "", 2560, 536))));
    }

    @Test
    public void displayFiveIsNotTheHud() {
        assertEquals(-1, HudDisplay.pick(Arrays.asList(
                main(0, "built-in", 3200, 2000),
                screen(5, "", 2560, 536))));
    }

    @Test
    public void theCentreScreenIsNeverTheHud() {
        assertEquals(-1, HudDisplay.pick(Collections.singletonList(
                main(HudDisplay.ZEEKR_7X_ID, "HUD", 3200, 2000))));
    }

    @Test
    public void noMatchStaysOffTheOtherScreens() {
        assertEquals(-1, HudDisplay.pick(Arrays.asList(
                main(0, "built-in", 3200, 2000),
                screen(1, "passenger", 1920, 720))));
    }

    private static HudDisplay.Screen main(int id, String name, int width, int height) {
        return new HudDisplay.Screen(id, name, width, height, true);
    }

    private static HudDisplay.Screen screen(int id, String name, int width, int height) {
        return new HudDisplay.Screen(id, name, width, height, false);
    }
}
