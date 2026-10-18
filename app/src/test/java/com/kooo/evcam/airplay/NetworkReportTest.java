package com.kooo.evcam.airplay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NetworkReportTest {

    @Test
    public void iphoneHotspotIsRecognisedByItsAddress() {
        assertTrue(NetworkReport.wifiKind("172.20.10.1", null).startsWith("iPhone Personal Hotspot"));
    }

    @Test
    public void megabytes() {
        assertEquals("1.5 MB", NetworkReport.megabytes(1572864));
    }
}
