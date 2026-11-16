package com.kooo.evcam.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EqToneTest {

    @Test
    public void neutralLeavesTheCurveAlone() {
        float[] gains = CabinEq.Builtin.ROCK.gains();
        float[] shaped = EqTone.NEUTRAL.shapeSliders(gains);
        for (int i = 0; i < CabinEq.BANDS; i++) {
            assertEquals(gains[i], shaped[i], 1e-4);
        }
    }

    @Test
    public void bassShelfLiftsLowsNotHighs() {
        float[] shaped = EqTone.NEUTRAL.withBass(10).shapeSliders(CabinEq.flat());
        assertTrue("25 Hz " + shaped[0], shaped[0] > 9);
        assertTrue("16 kHz " + shaped[14], Math.abs(shaped[14]) < 0.1);
    }

    @Test
    public void trebleShelfLiftsHighsNotLows() {
        float[] shaped = EqTone.NEUTRAL.withTreble(-8).shapeSliders(CabinEq.flat());
        assertTrue(shaped[14] < -7);
        assertTrue(Math.abs(shaped[0]) < 0.1);
    }

    @Test
    public void loudnessAddsBassAndTreble() {
        float[] shaped = EqTone.NEUTRAL.withLoudness(100).shapeSliders(CabinEq.flat());
        assertTrue(shaped[0] > 8);
        assertTrue(shaped[14] > 2);
        assertTrue(Math.abs(shaped[8]) < 1);
    }

    @Test
    public void shapedGainIsCapped() {
        float[] shaped = new EqTone(12, 0, 100, 0, 0, 0).shapeSliders(CabinEq.Builtin.BASS.gains());
        assertEquals(EqTone.SHAPED_MAX, shaped[0], 1e-4);
    }

    @Test
    public void balanceCutsTheOtherSide() {
        EqTone right = EqTone.NEUTRAL.withBalance(50);
        assertTrue(right.balanceDb(0) < -5);
        assertEquals(0f, right.balanceDb(1), 1e-6);
        assertEquals(-60f, EqTone.NEUTRAL.withBalance(-100).balanceDb(1), 1e-6);
        assertEquals(0f, EqTone.NEUTRAL.balanceDb(0), 1e-6);
    }

    @Test
    public void compressorZeroIsTheSafetyLimiter() {
        assertEquals(-1f, EqTone.NEUTRAL.compressorThresholdDb(), 1e-6);
        assertEquals(0f, EqTone.NEUTRAL.compressorMakeupDb(), 1e-6);
        EqTone night = EqTone.NEUTRAL.withCompressor(100);
        assertEquals(-30f, night.compressorThresholdDb(), 1e-6);
        assertTrue(night.compressorMakeupDb() > 5 && night.compressorMakeupDb() < 15);
    }

    @Test
    public void roundTripsAndClamps() {
        EqTone t = new EqTone(3, -4, 50, 20, -6, -30);
        assertEquals(t.write(), EqTone.read(t.write()).write());
        assertEquals(EqTone.NEUTRAL.write(), EqTone.read("junk").write());
        assertEquals(EqTone.PREAMP_MAX, new EqTone(0, 0, 0, 0, 99, 0).preamp);
    }

    @Test
    public void everyPresetHasFifteenBandsInRange() {
        for (CabinEq.Builtin b : CabinEq.Builtin.values()) {
            float[] g = b.gains();
            assertEquals(CabinEq.BANDS, g.length);
            for (float v : g) {
                assertTrue(v >= CabinEq.GAIN_MIN && v <= CabinEq.GAIN_MAX);
            }
        }
    }
}
