package com.kooo.evcam.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CabinEqCurveTest {

    @Test
    public void engineBandsRiseAndEndAtTwentyKilohertz() {
        float[] edges = CabinEq.engineCutoffs();
        assertEquals(CabinEq.ENGINE_BANDS, edges.length);
        for (int i = 1; i < edges.length; i++) {
            assertTrue("edge " + i + " must rise", edges[i] > edges[i - 1]);
        }
        assertEquals(20000f, edges[edges.length - 1], 0.01f);
    }

    @Test
    public void engineCurveKeepsSliderGainsAndBlendsBetweenThem() {
        float[] gains = CabinEq.flat();
        gains[3] = 6f;
        gains[4] = -2f;
        float[] curve = CabinEq.engineGains(gains);
        assertEquals(6f, curve[6], 0.001f);
        assertEquals(2f, curve[7], 0.001f);
        assertEquals(-2f, curve[8], 0.001f);
    }

    @Test
    public void headroomEqualsTheBiggestBoostAndCutsNeedNone() {
        float[] gains = CabinEq.flat();
        gains[0] = 5f;
        gains[10] = 9f;
        gains[12] = -12f;
        assertEquals(9f, CabinEq.headroomDb(gains), 0.001f);
        float[] cuts = CabinEq.flat();
        cuts[2] = -6f;
        assertEquals(0f, CabinEq.headroomDb(cuts), 0.001f);
    }

    @Test
    public void builtInPresetsHaveEveryBandInsideTheSliderRange() {
        for (CabinEq.Builtin preset : CabinEq.Builtin.values()) {
            float[] gains = preset.gains();
            assertEquals(preset.name(), CabinEq.BANDS, gains.length);
            for (float g : gains) {
                assertTrue(preset.name(), g >= CabinEq.GAIN_MIN && g <= CabinEq.GAIN_MAX);
            }
        }
    }

    @Test
    public void choiceKeysParse() {
        assertEquals(CabinEq.Builtin.KPOP, CabinEqStore.builtinOf(CabinEqStore.BUILTIN + "KPOP"));
        assertNull(CabinEqStore.builtinOf(CabinEqStore.BUILTIN + "POLKA"));
        assertEquals(4, CabinEqStore.myIndexOf(CabinEqStore.MY + "4"));
        assertEquals(-1, CabinEqStore.myIndexOf(CabinEqStore.MY + "5"));
        assertEquals(-1, CabinEqStore.myIndexOf("my:x"));
        assertNotNull(CabinEq.Builtin.FLAT.gains());
    }
}
