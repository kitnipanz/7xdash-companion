package com.kooo.evcam.overlay;

/**
 * Where the Macro pad sits while it is docked to the Dash panel: right under it, left edges lined
 * up. If there is no room under the panel it goes above it instead; failing that, as low as fits.
 * Plain arithmetic, so it can be tested off the device.
 */
final class MacroPadDock {

    private MacroPadDock() {
    }

    /**
     * @param panel   the panel's window {x, y, width, height}
     * @param width   the pad's width
     * @param height  the pad's height
     * @param screenH screen height
     * @param gap     space between the panel and the pad
     * @return the pad's {x, y}
     */
    static int[] place(int[] panel, int width, int height, int screenH, int gap) {
        int x = panel[0];
        int below = panel[1] + panel[3] + gap;
        int y;
        if (below + height <= screenH) {
            y = below;
        } else if (panel[1] - gap - height >= 0) {
            y = panel[1] - gap - height;
        } else {
            y = Math.max(0, screenH - height);
        }
        return new int[]{x, y};
    }
}
