package com.kooo.evcam.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.AudioDeviceInfo;

import org.junit.Test;

public class MegaphoneTest {

    @Test
    public void outerNotifyIsTheBusAddressNotADeviceId() {
        assertTrue(Megaphone.isOuterNotify(AudioDeviceInfo.TYPE_BUS, "BUS12_OUTER_NOTIFY"));
        assertFalse(Megaphone.isOuterNotify(AudioDeviceInfo.TYPE_BUS, "BUS11_OUTER_SPEAKER_PLAYBACK"));
        assertFalse(Megaphone.isOuterNotify(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "BUS12_OUTER_NOTIFY"));
        assertFalse(Megaphone.isOuterNotify(AudioDeviceInfo.TYPE_BUS, null));
    }

    @Test
    public void percentIsZeroWhenSilentAndFullAtFullScale() {
        assertEquals(0, Megaphone.percent(null, 4));
        assertEquals(0, Megaphone.percent(new byte[] {0, 0, 0, 0}, 4));
        assertEquals(50, Megaphone.percent(new byte[] {0x00, 0x40}, 2));
        assertEquals(100, Megaphone.percent(new byte[] {(byte) 0xFF, 0x7F}, 2));
        assertEquals(100, Megaphone.percent(new byte[] {0x00, (byte) 0x80}, 2));
    }

    @Test
    public void boostLiftsAQuietCabinMicAndClipsFullScale() {
        byte[] quiet = new byte[] {0x00, 0x02};
        assertEquals(31, Megaphone.boost(quiet, 2, 20));
        assertEquals(31, Megaphone.percent(quiet, 2));
        byte[] hot = new byte[] {(byte) 0xFF, 0x7F};
        assertEquals(100, Megaphone.boost(hot, 2, 20));
        assertEquals(0, Megaphone.boost(new byte[] {0, 0}, 2, 0));
    }
}
