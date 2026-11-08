package com.kooo.evcam.nav;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MapDrawWaitTest {

    @Test
    public void waitsForTheLastMoveToBeDrawn() {
        assertTrue(NavMapPainter.stillDrawing(1_000, 900, 1_100));
    }

    @Test
    public void movesAgainOnceDrawn() {
        assertFalse(NavMapPainter.stillDrawing(1_000, 1_050, 1_100));
    }

    @Test
    public void doesNotWaitForeverOnAMapThatNeverDraws() {
        assertFalse(NavMapPainter.stillDrawing(1_000, 0, 1_700));
    }
}
