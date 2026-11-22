package com.kooo.evcam.overlay;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.KeepAliveAccessibilityService;
import com.kooo.evcam.input.AccessibilityGate;

/**
 * Who may draw over other apps, and through which window manager.
 *
 * <p>The car head unit has no "Display over other apps" settings page, so the overlay permission
 * cannot be switched on there. When it is missing, an accessibility service that is running can
 * still add windows of type {@code TYPE_ACCESSIBILITY_OVERLAY} through its own window manager,
 * and no overlay permission is needed for that. {@link #add} picks the route: the overlay
 * permission when it is granted, the accessibility service otherwise.</p>
 *
 * <p>Removing and updating a window work through any window manager in the process, so only
 * adding goes through here.</p>
 */
public final class OverlayAccess {
    private static final String TAG = "OverlayAccess";
    /** WindowManager.LayoutParams.TYPE_PRESENTATION (hidden in the SDK): the type android.app.Presentation uses. */
    private static final int TYPE_PRESENTATION = WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW + 37;

    private OverlayAccess() {
    }

    /** The overlay permission itself is granted. */
    public static boolean permitted(Context context) {
        return Settings.canDrawOverlays(context);
    }

    /**
     * Windows can go over other apps: either the overlay permission is granted, or the
     * accessibility service is running. With neither, it tries to switch the service on (this
     * needs WRITE_SECURE_SETTINGS, granted over adb). That takes a moment, so this call still says no.
     */
    public static boolean canDraw(Context context) {
        if (permitted(context) || KeepAliveAccessibilityService.isRunning()) {
            return true;
        }
        AccessibilityGate.selfEnable(context.getApplicationContext());
        return false;
    }

    /** The service to add windows through, or null when the overlay permission is granted or the service is off. */
    private static KeepAliveAccessibilityService viaAccessibility(Context context) {
        if (permitted(context)) {
            return null;
        }
        return KeepAliveAccessibilityService.running();
    }

    /**
     * A public presentation screen other than the main one (on a 7X the HUD and the 2560×536
     * screen). Any app may put a {@code TYPE_PRESENTATION} window there, as {@link
     * android.app.Presentation} does, with no overlay permission.
     */
    public static boolean isPresentationDisplay(Display display) {
        if (display == null || display.getDisplayId() == Display.DEFAULT_DISPLAY) {
            return false;
        }
        int flags = display.getFlags();
        return (flags & Display.FLAG_PRESENTATION) != 0 && (flags & Display.FLAG_PRIVATE) == 0;
    }

    /**
     * Windows on {@code display} go in as presentation windows: no overlay permission, no
     * accessibility service. A public presentation screen qualifies, and so does the HUD we
     * picked by size, even when a store install does not mark that screen as presentation.
     */
    private static boolean viaPresentation(Context context, Display display) {
        return !permitted(context) && KeepAliveAccessibilityService.running() == null
                && (isPresentationDisplay(display) || isHud(context, display));
    }

    /** Something can be drawn on {@code display}: as an overlay, or as a presentation window there. */
    public static boolean canDrawOn(Context context, Display display) {
        return canDraw(context) || isPresentationDisplay(display) || isHud(context, display);
    }

    /**
     * Something can be drawn on the HUD without the overlay permission. The window is a
     * presentation window on that screen. Overlay and the accessibility service also count,
     * but neither is required.
     */
    public static boolean canDrawOnHud(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false;
        }
        Display hud = com.kooo.evcam.zeekr.HudDisplay.find(context);
        return hud != null && hud.getDisplayId() != Display.DEFAULT_DISPLAY;
    }

    /** {@code display} is the driver HUD this head unit just reported. */
    private static boolean isHud(Context context, Display display) {
        if (display == null || display.getDisplayId() == Display.DEFAULT_DISPLAY) {
            return false;
        }
        Display hud = com.kooo.evcam.zeekr.HudDisplay.find(context);
        return hud != null && hud.getDisplayId() == display.getDisplayId();
    }

    /**
     * Adds an overlay window. {@code wm} is the window manager the caller would use; its display
     * decides where the window goes. Without the overlay permission the window goes through the
     * accessibility service on that same display, as an accessibility overlay.
     */
    public static void add(WindowManager wm, View view, WindowManager.LayoutParams lp) {
        addWindow(wm, view, lp);
        if (!(view instanceof OnTop)) {
            addedBelow(wm.getDefaultDisplay());
        }
    }

    /**
     * A window that must stay above every other window on its screen: the blind spot on the HUD.
     * Windows of one type stack in the order they were added, so the only way up is to be added
     * again; the window does that itself when told something went on after it.
     */
    public interface OnTop {
        /** Another window was just added on display {@code displayId}. Main thread. */
        void onWindowAddedAfter(int displayId);

        /** On screen now (not just standing by). */
        boolean isShowing();

        /** The display it is on, -1 when not attached. */
        int onDisplay();
    }

    /** A window that must stay on top is showing on {@code displayId}: something passing can wait. */
    public static synchronized boolean onTopShowingOn(int displayId) {
        for (OnTop w : ON_TOP) {
            if (w.isShowing() && w.onDisplay() == displayId) {
                return true;
            }
        }
        return false;
    }

    private static final java.util.Set<OnTop> ON_TOP =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    /** Per display: how many other windows have been added. An {@link OnTop} compares it with what it saw. */
    private static final java.util.Map<Integer, Long> ADDED = new java.util.HashMap<>();
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    /** {@code window} wants to hear when other windows are added. */
    public static synchronized void keepOnTop(OnTop window) {
        ON_TOP.add(window);
    }

    public static synchronized void release(OnTop window) {
        ON_TOP.remove(window);
    }

    /** How many other windows have gone onto {@code displayId} so far. */
    public static synchronized long addedOn(int displayId) {
        Long n = ADDED.get(displayId);
        return n == null ? 0L : n;
    }

    private static void addedBelow(Display display) {
        if (display == null) {
            return;
        }
        int id = display.getDisplayId();
        java.util.List<OnTop> tell;
        synchronized (OverlayAccess.class) {
            ADDED.put(id, addedOn(id) + 1);
            tell = new java.util.ArrayList<>(ON_TOP);
        }
        for (OnTop w : tell) {
            MAIN.post(() -> w.onWindowAddedAfter(id));
        }
    }

    private static void addWindow(WindowManager wm, View view, WindowManager.LayoutParams lp) {
        KeepAliveAccessibilityService service = viaAccessibility(view.getContext());
        if (service == null) {
            if (lp.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    && viaPresentation(view.getContext(), wm.getDefaultDisplay())) {
                // Must match the window context from windowContext(), made as a presentation one
                lp.type = TYPE_PRESENTATION;
            }
            wm.addView(view, lp);
            return;
        }
        WindowManager a11y = accessibilityWindowManager(service, wm.getDefaultDisplay());
        if (a11y == null) {
            wm.addView(view, lp);
            return;
        }
        if (lp.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) {
            lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        }
        a11y.addView(view, lp);
    }

    /** Removes a window added with {@link #add}. Any window manager in the process can remove it. */
    public static void remove(WindowManager wm, View view) {
        wm.removeView(view);
    }

    /** {@link #remove}, at once ({@code removeViewImmediate}). */
    public static void removeImmediate(WindowManager wm, View view) {
        wm.removeViewImmediate(view);
    }

    /** Moves or resizes a window added with {@link #add}. */
    public static void update(WindowManager wm, View view, WindowManager.LayoutParams lp) {
        wm.updateViewLayout(view, lp);
    }

    /**
     * A context for overlay windows on {@code display}, in place of
     * {@code createWindowContext(display, TYPE_APPLICATION_OVERLAY, null)}. Without the overlay
     * permission that call fails, so the accessibility service's own display context is used, or,
     * on a presentation screen such as the HUD, a presentation window context ({@link #add} then
     * adds the window as {@code TYPE_PRESENTATION} to match).
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    public static Context windowContext(Context base, Display display) {
        KeepAliveAccessibilityService service = viaAccessibility(base);
        if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return display.getDisplayId() == Display.DEFAULT_DISPLAY
                    ? service : service.createDisplayContext(display);
        }
        if (viaPresentation(base, display)) {
            return base.createWindowContext(display, TYPE_PRESENTATION, null);
        }
        return base.createWindowContext(display,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
    }

    /**
     * The accessibility service's window manager for {@code display}. On the main display the
     * service's own; on another display (Android 11 and later) the service's display context,
     * which carries the service's overlay token for that display.
     */
    private static WindowManager accessibilityWindowManager(KeepAliveAccessibilityService service,
                                                            Display display) {
        try {
            if (display == null || display.getDisplayId() == Display.DEFAULT_DISPLAY) {
                return service.getSystemService(WindowManager.class);
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return null;
            }
            return service.createDisplayContext(display).getSystemService(WindowManager.class);
        } catch (RuntimeException e) {
            AppLog.w(TAG, "No accessibility window manager: " + e.getMessage());
            return null;
        }
    }
}
