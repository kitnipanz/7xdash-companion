package com.kooo.evcam.zeekr;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import com.kooo.evcam.AppLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统里本应用打得开的每一块屏。点一行，那块屏顶上写大字，用来认哪块是抬头显示。
 *
 * <p>先要系统公开的屏，再要能做 Presentation 的屏，再按编号 0–16 问一次。
 * 隐藏接口若多报了编号、却不给 {@link Display}，那一行标成打不开。</p>
 */
public final class ScreenProbe {

    private static final String TAG = "ScreenProbe";
    private static final int SCAN_LAST_ID = 16;

    private static ViewSlot slot;
    private static int showing = -1;
    private static String method = "";
    private static int failedId = -1;
    private static String failedMessage = "";

    public static final class Row {
        public final int id;
        public final String name;
        public final int width;
        public final int height;
        public final String state;
        public final String flags;
        public final String uniqueId;
        public final boolean main;
        /** 系统报了这个编号，但不给打开。 */
        public final boolean closed;

        public Row(int id, String name, int width, int height, String state, String flags,
                   String uniqueId, boolean main, boolean closed) {
            this.id = id;
            this.name = name;
            this.width = width;
            this.height = height;
            this.state = state;
            this.flags = flags;
            this.uniqueId = uniqueId;
            this.main = main;
            this.closed = closed;
        }
    }

    private static final class ViewSlot {
        WindowManager windowManager;
        TextView overlay;
        android.app.Presentation presentation;
    }

    private ScreenProbe() {
    }

    public static int showing() {
        return showing;
    }

    public static String method() {
        return method;
    }

    /** 上一回写失败的那块屏；别的屏返回空串。 */
    public static String failure(int id) {
        return failedId == id ? failedMessage : "";
    }

    public static List<Row> list(Context context) {
        DisplayManager manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Map<Integer, Display> open = new LinkedHashMap<>();
        List<Integer> reported = new ArrayList<>();
        if (manager != null) {
            addAll(open, manager.getDisplays());
            addAll(open, manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION));
            for (int id = 0; id <= SCAN_LAST_ID; id++) {
                Display display = manager.getDisplay(id);
                if (display != null) {
                    open.put(display.getDisplayId(), display);
                }
            }
            reported.addAll(hiddenIds());
        }
        List<Integer> ids = new ArrayList<>(open.keySet());
        for (int id : reported) {
            if (!open.containsKey(id) && !ids.contains(id)) {
                ids.add(id);
            }
        }
        ids.sort(Integer::compareTo);
        List<Row> rows = new ArrayList<>();
        for (int id : ids) {
            Display display = open.get(id);
            if (display == null) {
                rows.add(new Row(id, "", 0, 0, "", "", "", false, true));
            } else {
                rows.add(describe(display));
            }
        }
        return rows;
    }

    /** 再点同一块就收起。写不上返回 false，原因在 {@link #failure(int)}。 */
    public static boolean toggle(Context context, int id) {
        if (showing == id) {
            clear();
            return true;
        }
        clear();
        DisplayManager manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        Display display = manager == null ? null : manager.getDisplay(id);
        if (display == null) {
            noteFailure(id, "system will not open this display");
            return false;
        }
        String text = markText(display);
        Context app = context.getApplicationContext();
        try {
            if (showOverlay(app, display, text)) {
                showing = id;
                method = "overlay";
                failedId = -1;
                failedMessage = "";
                AppLog.i(TAG, "字写到屏 " + id + "（overlay）");
                return true;
            }
        } catch (Throwable t) {
            noteFailure(id, reason(t));
            AppLog.w(TAG, "overlay 写屏 " + id + " 失败: " + reason(t));
        }
        if (display.getDisplayId() == Display.DEFAULT_DISPLAY) {
            if (failedMessage.isEmpty()) {
                noteFailure(id, "overlay was not added");
            }
            return false;
        }
        try {
            showPresentation(app, display, text);
            showing = id;
            method = "presentation";
            failedId = -1;
            failedMessage = "";
            AppLog.i(TAG, "字写到屏 " + id + "（presentation）");
            return true;
        } catch (Throwable t) {
            noteFailure(id, reason(t));
            AppLog.w(TAG, "presentation 写屏 " + id + " 失败: " + reason(t));
            return false;
        }
    }

    public static void clear() {
        ViewSlot held = slot;
        slot = null;
        showing = -1;
        method = "";
        if (held == null) {
            return;
        }
        if (held.overlay != null && held.windowManager != null) {
            try {
                held.windowManager.removeView(held.overlay);
            } catch (Throwable t) {
                AppLog.w(TAG, "收起屏幕字失败: " + reason(t));
            }
        }
        if (held.presentation != null) {
            try {
                held.presentation.dismiss();
            } catch (Throwable t) {
                AppLog.w(TAG, "收起 presentation 失败: " + reason(t));
            }
        }
    }

    public static String markText(Display display) {
        Point size = sizeOf(display);
        String name = display.getName();
        if (name == null || name.isEmpty()) {
            name = "—";
        }
        return "SCREEN " + display.getDisplayId() + "\n" + name + "\n" + size.x + "×" + size.y;
    }

    private static Row describe(Display display) {
        Point size = sizeOf(display);
        return new Row(display.getDisplayId(), display.getName() == null ? "" : display.getName(),
                size.x, size.y, stateName(display.getState()), flagNames(display.getFlags()),
                "", display.getDisplayId() == Display.DEFAULT_DISPLAY, false);
    }

    static String stateName(int state) {
        switch (state) {
            case Display.STATE_OFF:
                return "off";
            case Display.STATE_ON:
                return "on";
            case Display.STATE_DOZE:
                return "doze";
            case Display.STATE_DOZE_SUSPEND:
                return "doze-suspend";
            case Display.STATE_VR:
                return "vr";
            case Display.STATE_ON_SUSPEND:
                return "on-suspend";
            default:
                return "state " + state;
        }
    }

    static String flagNames(int bits) {
        StringBuilder sb = new StringBuilder();
        addFlag(sb, bits, Display.FLAG_SECURE, "secure");
        addFlag(sb, bits, Display.FLAG_PRIVATE, "private");
        addFlag(sb, bits, Display.FLAG_PRESENTATION, "presentation");
        addFlag(sb, bits, Display.FLAG_ROUND, "round");
        if (sb.length() == 0) {
            return "no flags";
        }
        return sb.toString();
    }

    private static void addFlag(StringBuilder sb, int bits, int flag, String name) {
        if ((bits & flag) == 0) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(name);
    }

    private static void addAll(Map<Integer, Display> into, Display[] displays) {
        if (displays == null) {
            return;
        }
        for (Display display : displays) {
            if (display != null) {
                into.put(display.getDisplayId(), display);
            }
        }
    }

    /** 系统内部报出的屏编号。拿不到就空表。 */
    private static List<Integer> hiddenIds() {
        List<Integer> ids = new ArrayList<>();
        try {
            Class<?> globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object global = globalClass.getMethod("getInstance").invoke(null);
            Object raw;
            try {
                raw = globalClass.getMethod("getDisplayIds").invoke(global);
            } catch (NoSuchMethodException ignored) {
                raw = globalClass.getMethod("getDisplayIds", boolean.class).invoke(global, true);
            }
            if (raw instanceof int[]) {
                for (int id : (int[]) raw) {
                    ids.add(id);
                }
            }
        } catch (Throwable ignored) {
            // 隐藏接口被挡：公开列表照旧
        }
        return ids;
    }

    private static boolean showOverlay(Context app, Display display, String text) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            if (display.getDisplayId() != Display.DEFAULT_DISPLAY) {
                return false;
            }
            WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            TextView label = label(app, display, text);
            wm.addView(label, band(app, display));
            ViewSlot held = new ViewSlot();
            held.windowManager = wm;
            held.overlay = label;
            slot = held;
            return true;
        }
        Context windowContext = app.createWindowContext(
                display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        WindowManager wm = (WindowManager) windowContext.getSystemService(Context.WINDOW_SERVICE);
        TextView label = label(windowContext, display, text);
        wm.addView(label, band(windowContext, display));
        ViewSlot held = new ViewSlot();
        held.windowManager = wm;
        held.overlay = label;
        slot = held;
        return true;
    }

    private static void showPresentation(Context app, Display display, String text) {
        android.app.Presentation presentation = new android.app.Presentation(app, display) {
            @Override
            protected void onCreate(Bundle savedInstanceState) {
                super.onCreate(savedInstanceState);
                TextView label = label(getContext(), display, text);
                label.setGravity(Gravity.CENTER);
                setContentView(label);
            }
        };
        presentation.show();
        ViewSlot held = new ViewSlot();
        held.presentation = presentation;
        slot = held;
    }

    private static TextView label(Context context, Display display, String text) {
        Point size = sizeOf(display);
        int band = bandHeight(size.y);
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(0xFFFFFFFF);
        view.setBackgroundColor(0xFF000000);
        view.setGravity(Gravity.CENTER);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(28f, band * 0.22f));
        return view;
    }

    private static WindowManager.LayoutParams band(Context context, Display display) {
        Point size = sizeOf(display);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                bandHeight(size.y),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        params.gravity = Gravity.TOP | Gravity.START;
        return params;
    }

    static int bandHeight(int displayHeight) {
        if (displayHeight < 800) {
            return Math.max(120, displayHeight / 2);
        }
        return 220;
    }

    private static Point sizeOf(Display display) {
        Point size = new Point();
        display.getRealSize(size);
        return size;
    }

    private static void noteFailure(int id, String message) {
        failedId = id;
        failedMessage = message == null ? "" : message;
    }

    private static String reason(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.isEmpty()) {
            return t.getClass().getSimpleName();
        }
        return t.getClass().getSimpleName() + ": " + message;
    }
}
