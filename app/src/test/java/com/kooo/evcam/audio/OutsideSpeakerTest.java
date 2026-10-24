package com.kooo.evcam.audio;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class OutsideSpeakerTest {

    @Test
    public void volumeIsSquaredAndClamped() {
        assertEquals(0f, OutsideSpeaker.gain(0), 0f);
        assertEquals(0.25f, OutsideSpeaker.gain(50), 1e-6f);
        assertEquals(1f, OutsideSpeaker.gain(100), 0f);
        assertEquals(1f, OutsideSpeaker.gain(150), 0f);
        assertEquals(0f, OutsideSpeaker.gain(-5), 0f);
    }
}
