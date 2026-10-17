package com.kooo.evcam.zeekr;

import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.view.Display;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 驾驶员挡风玻璃上的抬头显示是哪一块屏。
 *
 * <p>先认名字里带 hud 的非主屏。这台极氪 7X 上，屏幕（试验性）点出来的是编号 3、1440×480。
 * 编号对上就用它；编号变了、尺寸还是 1440×480，也认。主屏再叫这个编号也不认。</p>
 */
public final class HudDisplay {

    /** 这台 7X 抬头显示的编号。屏幕（试验性）实车点出来的。 */
    public static final int ZEEKR_7X_ID = 3;
    public static final int ZEEKR_7X_WIDTH = 1440;
    public static final int ZEEKR_7X_HEIGHT = 480;

    public static final class Screen {
        public final int id;
        public final String name;
        public final int width;
        public final int height;
        public final boolean main;

        public Screen(int id, String name, int width, int height, boolean main) {
            this.id = id;
            this.name = name;
            this.width = width;
            this.height = height;
            this.main = main;
        }
    }

    private HudDisplay() {
    }

    /** 抬头显示的编号；没有就 -1。 */
    public static int pick(List<Screen> screens) {
        if (screens == null) {
            return -1;
        }
        int byId = -1;
        int bySize = -1;
        for (Screen screen : screens) {
            if (screen == null || screen.main) {
                continue;
            }
            String name = screen.name == null ? "" : screen.name.toLowerCase(Locale.US);
            if (name.contains("hud")) {
                return screen.id;
            }
            if (screen.id == ZEEKR_7X_ID) {
                byId = screen.id;
            }
            if (screen.width == ZEEKR_7X_WIDTH && screen.height == ZEEKR_7X_HEIGHT) {
                bySize = screen.id;
            }
        }
        return byId >= 0 ? byId : bySize;
    }

    public static Display find(Context context) {
        DisplayManager manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (manager == null) {
            return null;
        }
        java.util.Map<Integer, Display> open = new java.util.LinkedHashMap<>();
        addAll(open, manager.getDisplays());
        addAll(open, manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION));
        for (int id = 0; id <= 16; id++) {
            Display display = manager.getDisplay(id);
            if (display != null) {
                open.put(display.getDisplayId(), display);
            }
        }
        List<Screen> screens = new ArrayList<>();
        for (Display display : open.values()) {
            Point size = sizeOf(display);
            screens.add(new Screen(display.getDisplayId(), display.getName(), size.x, size.y,
                    display.getDisplayId() == Display.DEFAULT_DISPLAY));
        }
        int id = pick(screens);
        return id < 0 ? null : open.get(id);
    }

    private static void addAll(java.util.Map<Integer, Display> into, Display[] displays) {
        if (displays == null) {
            return;
        }
        for (Display display : displays) {
            if (display != null) {
                into.put(display.getDisplayId(), display);
            }
        }
    }

    public static Point sizeOf(Display display) {
        Point size = new Point();
        display.getRealSize(size);
        return size;
    }
}
