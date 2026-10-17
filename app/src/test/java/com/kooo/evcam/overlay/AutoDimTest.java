package com.kooo.evcam.overlay;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AutoDimTest {

    @Test
    public void nightWindowWrapsMidnight() {
        assertTrue(AutoDim.inWindow(20, 19, 7));
        assertTrue(AutoDim.inWindow(2, 19, 7));
        assertFalse(AutoDim.inWindow(12, 19, 7));
        assertFalse(AutoDim.inWindow(19, 19, 19));
    }

    @Test
    public void dayWindowIsHalfOpen() {
        assertTrue(AutoDim.inWindow(9, 8, 18));
        assertFalse(AutoDim.inWindow(18, 8, 18));
        assertFalse(AutoDim.inWindow(7, 8, 18));
    }
}
