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

    private OverlayAccess() {
    }

    /** The overlay permission itself is granted. */
    public static boolean permitted(Context context) {
        return Settings.canDrawOverlays(context);
    }

    /**
     * Windows can go over other apps: either the overlay permission is granted, or the
     * accessibility service is running. With neither, it tries to switch the service on (this
     * needs WRITE_SECURE_SETTINGS); the service starts a moment later, so this call still says no.
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
     * Adds an overlay window. {@code wm} is the window manager the caller would use; its display
     * decides where the window goes. Without the overlay permission the window goes through the
     * accessibility service on that same display, as an accessibility overlay.
     */
    public static void add(WindowManager wm, View view, WindowManager.LayoutParams lp) {
        KeepAliveAccessibilityService service = viaAccessibility(view.getContext());
        if (service == null) {
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

    /**
     * A context for overlay windows on {@code display}, in place of
     * {@code createWindowContext(display, TYPE_APPLICATION_OVERLAY, null)}. Without the overlay
     * permission that call fails, so the accessibility service's own display context is used.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    public static Context windowContext(Context base, Display display) {
        KeepAliveAccessibilityService service = viaAccessibility(base);
        if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return display.getDisplayId() == Display.DEFAULT_DISPLAY
                    ? service : service.createDisplayContext(display);
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
