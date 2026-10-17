package com.kooo.evcam.airplay;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class MdnsResponderTest {

    @Test
    public void answersOnWifiAndHotspotNotOnTheCarsInternalLinks() {
        // the 7X: wlan2 is Wi-Fi, vt3 carries 192.168.118.1; vlan*/veth are internal
        assertTrue(MdnsResponder.answersOn("wlan2"));
        assertTrue(MdnsResponder.answersOn("vt3"));
        assertTrue(MdnsResponder.answersOn("ap0"));
        assertFalse(MdnsResponder.answersOn("vlan11"));
        assertFalse(MdnsResponder.answersOn("veth"));
        assertFalse(MdnsResponder.answersOn("dummy0"));
        assertFalse(MdnsResponder.answersOn("rmnet_data0"));
    }

    @Test
    public void namesRoundTrip() {
        byte[] wire = MdnsResponder.name(new String[] {"7xDash", "_airplay", "_tcp", "local"});
        Object[] read = MdnsResponder.readName(wire, 0);
        @SuppressWarnings("unchecked")
        List<String> labels = (List<String>) read[0];
        assertEquals(Arrays.asList("7xDash", "_airplay", "_tcp", "local"), labels);
        assertEquals(wire.length, (int) (Integer) read[1]);
    }

    @Test
    public void followsCompressionPointers() {
        // "local" at 0, then "_tcp" + pointer to 0
        byte[] d = {5, 'l', 'o', 'c', 'a', 'l', 0, 4, '_', 't', 'c', 'p', (byte) 0xC0, 0};
        Object[] read = MdnsResponder.readName(d, 7);
        assertEquals(Arrays.asList("_tcp", "local"), read[0]);
        assertEquals(14, (int) (Integer) read[1]);
        assertArrayEquals(new byte[] {0}, MdnsResponder.name(new String[0]));
    }
}
