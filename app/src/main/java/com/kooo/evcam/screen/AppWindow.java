package com.kooo.evcam.screen;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.util.DisplayMetrics;

import com.kooo.evcam.AppLog;

/**
 * Opens another app next to what is on screen, the best way the head unit allows: a floating
 * (freeform) window where it has them, else split screen beside the opener, else full screen as
 * usual. Which one the system allows is {@link MultiWindowProbe}'s answer; whether it then honours
 * the request is up to the system, which may still open the app full screen.
 */
public final class AppWindow {

    private static final String TAG = "AppWindow";
    /** WindowConfiguration.WINDOWING_MODE_FREEFORM (hidden). */
    private static final int WINDOWING_MODE_FREEFORM = 5;

    public enum Mode { FLOATING, SPLIT, FULL }

    private AppWindow() {
    }

    /** The way the app will be asked to open on this head unit. Pure, see the test. */
    public static Mode modeFor(MultiWindowProbe.Result r) {
        if (Boolean.TRUE.equals(r.freeform) || Boolean.TRUE.equals(r.freeformSetting)) {
            return Mode.FLOATING;
        }
        if (Boolean.TRUE.equals(r.splitScreen) && !Boolean.FALSE.equals(r.multiWindow)) {
            return Mode.SPLIT;
        }
        return Mode.FULL;
    }

    /**
     * Where the floating window goes: the right part of the screen, clear of the top and bottom
     * bars. Pure, see the test.
     */
    public static Rect floatingBounds(int width, int height) {
        int w = Math.round(width * 0.55f);
        int h = Math.round(height * 0.78f);
        int right = width - Math.round(width * 0.03f);
        int top = Math.round(height * 0.1f);
        return new Rect(right - w, top, right, top + h);
    }

    /**
     * How to open {@code pkg}: its launcher activity, or its Android TV one (the TV builds of
     * Netflix and YouTube have only that). Null when it isn't installed or can't be opened.
     */
    public static Intent launchIntent(Context context, String pkg) {
        android.content.pm.PackageManager pm = context.getPackageManager();
        Intent launch = pm.getLaunchIntentForPackage(pkg);
        if (launch == null) {
            launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        }
        return launch == null || launch.getComponent() == null ? null : launch;
    }

    /**
     * For Diagnostics: what the system says about each Netflix and YouTube app, and any other app
     * whose name says Netflix or YouTube, so a missing card can be explained.
     */
    public static void appendVideoApps(StringBuilder sb, Context context, String[] netflix, String[] youtube) {
        sb.append("========== Video apps (Netflix / YouTube cards) ==========\n");
        android.content.pm.PackageManager pm = context.getPackageManager();
        java.util.Set<String> listed = new java.util.LinkedHashSet<>();
        java.util.Collections.addAll(listed, netflix);
        java.util.Collections.addAll(listed, youtube);
        for (String p : listed) {
            sb.append(p).append(": ").append(describe(pm, context, p)).append('\n');
        }
        try {
            for (android.content.pm.PackageInfo info : pm.getInstalledPackages(0)) {
                String p = info.packageName;
                String lower = p.toLowerCase(java.util.Locale.ROOT);
                if (!listed.contains(p) && (lower.contains("netflix") || lower.contains("youtube")
                        || lower.contains("smarttube") || lower.contains("box"))) {
                    sb.append("also installed: ").append(p).append(": ").append(describe(pm, context, p)).append('\n');
                }
            }
        } catch (RuntimeException e) {
            sb.append("installed list refused: ").append(e.getMessage()).append('\n');
        }
        sb.append('\n');
    }

    private static String describe(android.content.pm.PackageManager pm, Context context, String p) {
        try {
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(p, 0);
            String how = pm.getLaunchIntentForPackage(p) != null ? "launcher"
                    : pm.getLeanbackLaunchIntentForPackage(p) != null ? "TV launcher" : "no launcher";
            return "installed" + (ai.enabled ? "" : " (disabled)") + ", " + how;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return "not installed";
        } catch (RuntimeException e) {
            return "unknown: " + e.getMessage();
        }
    }

    /** Opens {@code app}; returns how it was asked to open. Split screen sits beside {@code from} when it is an activity. */
    public static Mode open(Context from, ComponentName app) {
        Mode mode = modeFor(MultiWindowProbe.probe(from));
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (mode == Mode.FLOATING) {
                DisplayMetrics dm = from.getResources().getDisplayMetrics();
                ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchBounds(floatingBounds(dm.widthPixels, dm.heightPixels));
                try {
                    ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class)
                            .invoke(options, WINDOWING_MODE_FREEFORM);
                } catch (Throwable t) {
                    // hidden API refused: the bounds alone ask for a window where freeform is on
                    AppLog.i(TAG, "windowing mode not set: " + t);
                }
                from.startActivity(intent, options.toBundle());
            } else if (mode == Mode.SPLIT) {
                from.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
                        | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
            } else {
                from.startActivity(intent);
            }
        } catch (RuntimeException e) {
            AppLog.w(TAG, mode + " refused for " + app.flattenToShortString() + ": " + e.getMessage());
            from.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            mode = Mode.FULL;
        }
        AppLog.i(TAG, "opened " + app.flattenToShortString() + " " + mode);
        return mode;
    }
}
