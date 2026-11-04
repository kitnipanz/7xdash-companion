package com.kooo.evcam.trip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RouteLineTest {

    @Test
    public void decodesGooglesExample() {
        // the example from Google's encoded polyline documentation
        RouteLine line = RouteLine.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 0);
        assertNotNull(line);
        assertEquals(3, line.size());
        RouteLine.Fix start = line.locate(38.5, -120.2, -1);
        assertEquals(0, start.offRouteM, 1);
        assertEquals(line.totalM, start.remainingM, 1);
        RouteLine.Fix end = line.locate(43.252, -126.453, -1);
        assertEquals(0, end.remainingM, 1);
    }

    @Test
    public void notAPolyline() {
        assertNull(RouteLine.decode("", 0));
        assertNull(RouteLine.decode("_p~iF", 0));
    }

    /** An L: 1 km east, then 1 km north. */
    private static final double KM_LNG = 1.0 / (111.195 * Math.cos(Math.toRadians(13.0)));
    private static final double KM_LAT = 1.0 / 111.195;
    private static final double[] ELL_LAT = {13.0, 13.0, 13.0 + KM_LAT};
    private static final double[] ELL_LNG = {100.0, 100.0 + KM_LNG, 100.0 + KM_LNG};

    private static RouteLine ell() {
        return new RouteLine(ELL_LAT, ELL_LNG, 0);
    }

    @Test
    public void countsAlongTheRoadNotTheStraightLine() {
        RouteLine line = ell();
        assertEquals(2000, line.totalM, 5);
        // halfway along the first leg, 20 m beside the road: 1.5 km left along the road
        double dLng = 0.5 / (111.195 * Math.cos(Math.toRadians(13.0)));
        RouteLine.Fix f = line.locate(13.0 + 0.020 / 111.195, 100.0 + dLng, -1);
        assertEquals(1500, f.remainingM, 5);
        assertEquals(20, f.offRouteM, 1);
        assertEquals(0, f.segment);
    }

    @Test
    public void usesTheRouteServiceLength() {
        RouteLine line = new RouteLine(ELL_LAT, ELL_LNG, 2100);
        assertEquals(2100, line.locate(13.0, 100.0, -1).remainingM, 1);
    }

    @Test
    public void doesNotJumpBackWhereTheRoadPassesItself() {
        // out 1 km east along one side of a highway and back along the other side, 10 m apart
        double dLng = 1.0 / (111.195 * Math.cos(Math.toRadians(13.0)));
        double side = 0.010 / 111.195;
        RouteLine line = new RouteLine(
                new double[] {13.0, 13.0, 13.0 + side, 13.0 + side},
                new double[] {100.0, 100.0 + dLng, 100.0 + dLng, 100.0}, 0);
        // on the way back, 300 m from the far end, matched to the return leg (segment 2)
        RouteLine.Fix back = line.locate(13.0 + side, 100.0 + 0.7 * dLng, 2);
        assertEquals(2, back.segment);
        assertTrue(back.remainingM < 800);
    }

    @Test
    public void offTheRoute() {
        RouteLine line = ell();
        RouteLine.Fix f = line.locate(13.0 - 0.5 / 111.195, 100.0, -1);
        assertEquals(500, f.offRouteM, 5);
    }
}
