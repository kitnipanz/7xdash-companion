package com.kooo.evcam.screen;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Build;
import android.provider.Settings;

/**
 * Can this head unit show 7xDash next to another app? Android split screen and freeform windows are
 * system features a car maker can switch off, so the Diagnostics report asks the system rather
 * than guessing. Read-only: nothing is launched or changed.
 */
public final class MultiWindowProbe {

    private MultiWindowProbe() {
    }

    /** What the system says, for the verdict and the report. Null = could not tell. */
    public static final class Result {
        public Boolean multiWindow;
        public Boolean splitScreen;
        public Boolean freeform;
        public Boolean freeformSetting;
        public Boolean forceResizable;
        public Boolean inMultiWindowNow;
    }

    public static Result probe(Context context) {
        Result r = new Result();
        r.multiWindow = firstKnown(atm("supportsMultiWindow", context), frameworkBool("config_supportsMultiWindow"));
        r.splitScreen = firstKnown(atm("supportsSplitScreenMultiWindow", context),
                frameworkBool("config_supportsSplitScreenMultiWindow"));
        boolean feature = context.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT);
        Boolean config = frameworkBool("config_freeformWindowManagement");
        r.freeform = feature || Boolean.TRUE.equals(config);
        r.freeformSetting = globalFlag(context, "enable_freeform_support");
        r.forceResizable = globalFlag(context, "force_resizable_activities");
        com.kooo.evcam.MainActivity main = com.kooo.evcam.MainActivity.getInstance();
        r.inMultiWindowNow = main == null ? null : main.isInMultiWindowMode();
        return r;
    }

    /** One line a driver can act on. Pure, see the test. */
    public static String verdict(Result r) {
        boolean split = Boolean.TRUE.equals(r.splitScreen) && !Boolean.FALSE.equals(r.multiWindow);
        boolean free = Boolean.TRUE.equals(r.freeform) || Boolean.TRUE.equals(r.freeformSetting);
        if (split && free) {
            return "split screen and floating windows: both supported";
        }
        if (split) {
            return "split screen: supported; floating windows: no";
        }
        if (free) {
            return "split screen: no; floating windows: supported";
        }
        if (r.splitScreen == null && r.multiWindow == null) {
            return "could not tell: the system did not answer";
        }
        return "neither: only 7xDash's own floating windows (Dash panel, Super mirror) can sit over other apps";
    }

    public static void appendTo(StringBuilder sb, Context context) {
        sb.append("========== Split screen / floating windows ==========\n");
        Result r = probe(context);
        sb.append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("multi-window: ").append(text(r.multiWindow)).append('\n');
        sb.append("split screen: ").append(text(r.splitScreen)).append('\n');
        sb.append("freeform (feature or config): ").append(text(r.freeform)).append('\n');
        sb.append("freeform developer setting: ").append(text(r.freeformSetting)).append('\n');
        sb.append("force resizable developer setting: ").append(text(r.forceResizable)).append('\n');
        sb.append("7xDash in multi-window now: ").append(text(r.inMultiWindowNow)).append('\n');
        sb.append(">> ").append(verdict(r)).append("\n\n");
    }

    private static String text(Boolean b) {
        return b == null ? "unknown" : b ? "yes" : "no";
    }

    private static Boolean firstKnown(Boolean a, Boolean b) {
        return a != null ? a : b;
    }

    /** {@code ActivityTaskManager.supportsMultiWindow(Context)} and friends: hidden, so by reflection. */
    private static Boolean atm(String method, Context context) {
        try {
            Class<?> c = Class.forName("android.app.ActivityTaskManager");
            Object v = c.getMethod(method, Context.class).invoke(null, context);
            return v instanceof Boolean ? (Boolean) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Boolean frameworkBool(String name) {
        try {
            Resources res = Resources.getSystem();
            int id = res.getIdentifier(name, "bool", "android");
            return id == 0 ? null : res.getBoolean(id);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Boolean globalFlag(Context context, String name) {
        try {
            String v = Settings.Global.getString(context.getContentResolver(), name);
            return v == null ? null : !"0".equals(v.trim());
        } catch (Throwable t) {
            return null;
        }
    }
}
