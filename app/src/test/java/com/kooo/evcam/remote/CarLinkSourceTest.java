package com.kooo.evcam.remote;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** {@link CarLink} source normalisation must match the hub and {@link RemoteLive#slotFor}. */
public class CarLinkSourceTest {

    @Test
    public void knownSourcesPassThrough() {
        assertEquals("drive", CarLink.normalizeSource("drive"));
        assertEquals("ch1", CarLink.normalizeSource("CH1"));
        assertEquals("ch4", CarLink.normalizeSource("ch4"));
        assertEquals("driver", CarLink.normalizeSource("driver"));
        assertEquals("backseat", CarLink.normalizeSource("backseat"));
    }

    @Test
    public void junkFallsBackToDrive() {
        assertEquals("drive", CarLink.normalizeSource(null));
        assertEquals("drive", CarLink.normalizeSource(""));
        assertEquals("drive", CarLink.normalizeSource("ch99"));
        assertEquals("drive", CarLink.normalizeSource("cheese"));
    }
}
