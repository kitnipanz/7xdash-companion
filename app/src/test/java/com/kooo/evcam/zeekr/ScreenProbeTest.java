package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ScreenProbeTest {

    @Test
    public void shortScreenGetsATallBand() {
        assertEquals(268, ScreenProbe.bandHeight(536));
    }

    @Test
    public void tallScreenGetsAShortBand() {
        assertEquals(220, ScreenProbe.bandHeight(2000));
    }

    @Test
    public void flagsAndStateUseNames() {
        assertEquals("on", ScreenProbe.stateName(2));
        assertEquals("presentation", ScreenProbe.flagNames(DisplayFlag.PRESENTATION));
        assertEquals("secure private", ScreenProbe.flagNames(DisplayFlag.SECURE | DisplayFlag.PRIVATE));
    }

    /** 跟 {@link android.view.Display} 的公开标志同一套值，测试不启动系统。 */
    private static final class DisplayFlag {
        static final int SECURE = 2;
        static final int PRIVATE = 4;
        static final int PRESENTATION = 8;
    }
}
