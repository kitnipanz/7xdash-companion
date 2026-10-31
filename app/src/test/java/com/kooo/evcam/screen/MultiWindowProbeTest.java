package com.kooo.evcam.screen;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MultiWindowProbeTest {

    private static MultiWindowProbe.Result result(Boolean multi, Boolean split, Boolean free, Boolean freeSetting) {
        MultiWindowProbe.Result r = new MultiWindowProbe.Result();
        r.multiWindow = multi;
        r.splitScreen = split;
        r.freeform = free;
        r.freeformSetting = freeSetting;
        return r;
    }

    @Test
    public void verdicts() {
        assertTrue(MultiWindowProbe.verdict(result(true, true, true, null)).startsWith("split screen and floating windows"));
        assertTrue(MultiWindowProbe.verdict(result(true, true, false, null)).startsWith("split screen: supported"));
        assertTrue(MultiWindowProbe.verdict(result(true, false, false, true)).endsWith("floating windows: supported"));
        // multi-window off overrides a split-screen config left on
        assertTrue(MultiWindowProbe.verdict(result(false, true, false, null)).startsWith("neither"));
        assertTrue(MultiWindowProbe.verdict(result(null, null, false, null)).startsWith("could not tell"));
    }
}
