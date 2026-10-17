package com.kooo.evcam.airplay;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MirrorDecoderTest {

    @Test
    public void idrAndSpsAreKeyframes() {
        assertTrue(MirrorDecoder.isKeyframe(new byte[] {0, 0, 0, 1, 0x65, 1, 2}));
        assertTrue(MirrorDecoder.isKeyframe(new byte[] {9, 0, 0, 0, 1, 0x67, 1}));
    }

    @Test
    public void otherSlicesAreNot() {
        assertFalse(MirrorDecoder.isKeyframe(new byte[] {0, 0, 0, 1, 0x41, 1, 2}));
        assertFalse(MirrorDecoder.isKeyframe(new byte[] {0, 0, 1}));
    }
}
