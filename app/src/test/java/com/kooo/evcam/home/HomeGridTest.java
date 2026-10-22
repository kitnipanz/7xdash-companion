package com.kooo.evcam.home;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HomeGridTest {

    @Test
    public void columnsFollowTheWidth() {
        assertEquals(4, HomeGrid.columns(1400, 180, 2, 4));
        assertEquals(3, HomeGrid.columns(600, 180, 2, 4));
        assertEquals(2, HomeGrid.columns(300, 180, 2, 4));
    }

    @Test
    public void rowsRoundUp() {
        assertEquals(4, HomeGrid.rows(13, 4));
        assertEquals(3, HomeGrid.rows(12, 4));
        assertEquals(7, HomeGrid.rows(13, 2));
        assertEquals(0, HomeGrid.rows(0, 4));
    }

    @Test
    public void fewRowsKeepCardSizeAndLeaveWallpaper() {
        // 3 or 4 rows on a 1000px grid: every row is a fifth, the rest is wallpaper
        assertEquals(200, HomeGrid.rowHeight(1000, 3, 5, 100));
        assertEquals(200, HomeGrid.rowHeight(1000, 4, 5, 100));
    }

    @Test
    public void manyRowsShrinkThenScroll() {
        assertEquals(166, HomeGrid.rowHeight(1000, 6, 5, 100));
        // 13 rows would be 76px: stays at the minimum and the grid scrolls
        assertEquals(100, HomeGrid.rowHeight(1000, 13, 5, 100));
    }
}
