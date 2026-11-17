package com.kooo.evcam.screen;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AppWindowTest {

    private static MultiWindowProbe.Result result(Boolean multi, Boolean split, Boolean free, Boolean freeSetting) {
        MultiWindowProbe.Result r = new MultiWindowProbe.Result();
        r.multiWindow = multi;
        r.splitScreen = split;
        r.freeform = free;
        r.freeformSetting = freeSetting;
        return r;
    }

    @Test
    public void floatingWinsWhenFreeformIsThere() {
        assertEquals(AppWindow.Mode.FLOATING, AppWindow.modeFor(result(true, true, true, null)));
        assertEquals(AppWindow.Mode.FLOATING, AppWindow.modeFor(result(true, false, false, true)));
    }

    @Test
    public void splitWhenOnlySplitScreen() {
        assertEquals(AppWindow.Mode.SPLIT, AppWindow.modeFor(result(true, true, false, false)));
        assertEquals(AppWindow.Mode.SPLIT, AppWindow.modeFor(result(null, true, false, null)));
    }

    @Test
    public void fullScreenOtherwise() {
        assertEquals(AppWindow.Mode.FULL, AppWindow.modeFor(result(false, true, false, false)));
        assertEquals(AppWindow.Mode.FULL, AppWindow.modeFor(result(null, null, false, null)));
    }
}
