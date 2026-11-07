package com.kooo.evcam.nav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NavMapColorsTest {

    @Test
    public void landAndWaterAreRepainted() {
        assertEquals("#AADAFF", NavMapColors.paintFor("Water - Fill", false));
        assertEquals("#17263C", NavMapColors.paintFor("Water - Fill", true));
        assertNotEquals(NavMapColors.paintFor("background", false), NavMapColors.paintFor("background", true));
    }

    @Test
    public void everyBridgeAndTunnelRoadIsPaintedByItsClass() {
        for (String id : new String[] {"Surface - Motorway & Trunk", "Tunnel - Road line", "Bridge - Z1 Road line",
                "Bridge  - Z3+ Road line", "Surface - Street", "Surface - Link road"}) {
            String paint = NavMapColors.paintFor(id, false);
            assertTrue(id, paint.startsWith("[") && paint.contains("#FDD663") && paint.contains("#FFFFFF"));
        }
    }

    @Test
    public void outlinesTakeTheEdgeColours() {
        String edge = NavMapColors.paintFor("Bridge  - Z2 Road outline", false);
        assertTrue(edge.contains("#E8B23A") && !edge.contains("#FDD663"));
    }

    @Test
    public void trafficRailwaysAndRestrictionsKeepTheirLook() {
        for (String id : new String[] {"TrafficIncidents - Major jam", "Surface - Railway fill", "Bridge - Railway dash",
                "Surface - Restricted road dash", "Surface - Road arrow", "Surface - Road under construction",
                "POI", "Borders - Country", "nav-rest-line"}) {
            assertNull(id, NavMapColors.paintFor(id, false));
        }
    }

    @Test
    public void landUseTintsStayByDayOnly() {
        assertNull(NavMapColors.paintFor("LULC - Landuse", false));
        assertTrue(NavMapColors.paintFor("LULC - Landuse", true).startsWith("["));
    }
}
