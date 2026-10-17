package com.kooo.evcam.overlay;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DimShadeTest {

    @Test
    public void defaultVeilIsNinetyPercentBlack() {
        assertEquals(0xE5000000, DimShade.argb(90, 0, 0));
    }

    @Test
    public void fullBrightnessWithNoWarmthIsWhite() {
        assertEquals(0xFFFFFFFF, DimShade.argb(100, 100, 0));
    }

    @Test
    public void fullWarmthPullsBlackTowardAmber() {
        assertEquals(0xFFFFB040, DimShade.argb(100, 0, 100));
    }

    @Test
    public void fullBlackoutTakesTouchesSoTheVeilCanReachOpaque() {
        assertEquals(true, DimShade.passTouches(true, 90));
        assertEquals(false, DimShade.passTouches(true, 100));
        assertEquals(false, DimShade.passTouches(false, 40));
    }

    @Test
    public void passThroughWindowAlphaStaysUnderTheTouchLimit() {
        assertEquals(0.72f, DimShade.windowAlpha(true, 72), 0.001f);
        assertEquals(0.8f, DimShade.windowAlpha(true, 90), 0.001f);
        assertEquals(1f, DimShade.windowAlpha(true, 100), 0.001f);
        assertEquals(1f, DimShade.windowAlpha(false, 40), 0.001f);
    }

    @Test
    public void percentsOutsideZeroToHundredAreClamped() {
        assertEquals(DimShade.argb(100, 0, 0), DimShade.argb(150, -5, -1));
    }
}
