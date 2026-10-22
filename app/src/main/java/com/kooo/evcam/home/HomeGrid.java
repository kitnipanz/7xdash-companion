package com.kooo.evcam.home;

/** Sizing for the home cards: columns from the width, row height from the height. Pure, so it is unit tested. */
final class HomeGrid {

    private HomeGrid() {
    }

    /** As many columns as fit at {@code minCard} wide, within {@code min}..{@code max}. */
    static int columns(int width, int minCard, int min, int max) {
        if (width <= 0 || minCard <= 0) {
            return max;
        }
        return Math.max(min, Math.min(max, width / minCard));
    }

    static int rows(int cards, int columns) {
        if (cards <= 0 || columns <= 0) {
            return 0;
        }
        return (cards + columns - 1) / columns;
    }

    /**
     * Row height: the height split as if there were {@code share} rows (so a short grid keeps
     * normal-sized cards and shows wallpaper above), never below {@code minRow}. A grid with
     * more rows than that gets smaller rows first, then scrolls at {@code minRow}.
     */
    static int rowHeight(int height, int rows, int share, int minRow) {
        if (rows <= 0) {
            return minRow;
        }
        int slot = height / Math.max(share, rows + 1);
        if (rows >= share) {
            slot = height / rows;
        }
        return Math.max(minRow, slot);
    }
}
