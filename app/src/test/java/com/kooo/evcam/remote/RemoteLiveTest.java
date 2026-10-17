package com.kooo.evcam.remote;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RemoteLiveTest {

    @Test
    public void watchSourceMapsToSlot() {
        assertEquals(RemoteLive.DRIVE, RemoteLive.slotFor("drive"));
        assertEquals(RemoteLive.DRIVE, RemoteLive.slotFor(null));
        assertEquals(RemoteLive.DRIVE, RemoteLive.slotFor("nope"));
        assertEquals(RemoteLive.CH1, RemoteLive.slotFor("ch1"));
        assertEquals(RemoteLive.CH4, RemoteLive.slotFor("CH4"));
        assertEquals(RemoteLive.DRIVER, RemoteLive.slotFor("driver"));
        assertEquals(RemoteLive.BACKSEAT, RemoteLive.slotFor("backseat"));
    }

    @Test
    public void jpegForReturnsPutBytes() {
        RemoteLive live = new RemoteLive();
        byte[] jpeg = new byte[]{1, 2, 3};
        live.put(RemoteLive.CH2, jpeg, null);
        assertEquals(jpeg, live.jpegFor("ch2"));
        assertEquals(jpeg, live.jpegFor("CH2"));
    }

    @Test
    public void recordingWatchKeepsEveryChannelInMemory() {
        RemoteLive live = new RemoteLive();
        String[] slots = {
                RemoteLive.DRIVE,
                RemoteLive.CH1,
                RemoteLive.CH2,
                RemoteLive.CH3,
                RemoteLive.CH4,
                RemoteLive.DRIVER,
                RemoteLive.BACKSEAT,
        };
        for (int i = 0; i < slots.length; i++) {
            live.put(slots[i], new byte[]{(byte) (i + 1)}, null);
        }
        assertEquals(1, live.jpegFor("drive")[0]);
        assertEquals(2, live.jpegFor("ch1")[0]);
        assertEquals(3, live.jpegFor("ch2")[0]);
        assertEquals(4, live.jpegFor("ch3")[0]);
        assertEquals(5, live.jpegFor("ch4")[0]);
        assertEquals(6, live.jpegFor("driver")[0]);
        assertEquals(7, live.jpegFor("backseat")[0]);
    }
}
