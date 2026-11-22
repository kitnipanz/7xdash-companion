package com.kooo.evcam.overlay;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.telemetry.CarAmbientLab;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Ways to dim the main screen other than the overlay shade ({@link DimOverlayService}), for head
 * units where the overlay permission can't be granted. Each is a test: switch on, look, switch
 * off; switching off puts back exactly what was there. The strength is the Dim opacity (10–100).
 */
public final class DimRoutes {

    private static final String TAG = "DimRoutes";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public enum Route {
        /** The screen's own brightness (Settings.System), like the car's brightness slider. Needs "Modify system settings". */
        BRIGHTNESS,
        /** Android 12+ "Extra dim" (reduce bright colours). Needs WRITE_SECURE_SETTINGS (adb). */
        EXTRA_DIM,
        /** The car's own display brightness function, if ECARX has one and takes writes. */
        CAR,
        /** A full-screen dark floating activity: no permission, but Android may block taps under it. */
        FLOATING
    }

    private static final String EXTRA_DIM_ON = "reduce_bright_colors_activated";
    private static final String EXTRA_DIM_LEVEL = "reduce_bright_colors_level";

    private static final Pattern CAR_INCLUDE = Pattern.compile("BRIGHT|BACKLIGHT|LUMINAN");
    private static final Pattern CAR_EXCLUDE = Pattern.compile("AMBIEN|HUD|LAMP|FOOT|WELCOME|ATMOS|KEYBOARD|BUTTON|SWITCH_LIGHT|HEADLIGHT");

    private static final Map<Route, Boolean> ON = new EnumMap<>(Route.class);
    private static final Map<Route, String> RESULT = new EnumMap<>(Route.class);

    /** What was there before, to put back. */
    private static Integer savedBrightness;
    private static Integer savedBrightnessMode;
    private static Integer carId;
    private static Integer carSaved;

    private DimRoutes() {
    }

    /** The dark window closed (by itself, after {@link DimFloatActivity#SHOW_MS}): the row says off. */
    static void floatingClosed() {
        if (isOn(Route.FLOATING)) {
            ON.put(Route.FLOATING, false);
            RESULT.put(Route.FLOATING, "Dark window closed by itself after 15 seconds");
        }
        Runnable r = onChange;
        if (r != null) {
            MAIN.post(r);
        }
    }

    private static Runnable onChange;

    /** The settings page redraws its rows when a route changes by itself. Main thread; null to stop. */
    public static void setOnChange(Runnable r) {
        onChange = r;
    }

    /** Stops {@code mine} only: another page may have set its own since. */
    public static void clearOnChange(Runnable mine) {
        if (onChange == mine) {
            onChange = null;
        }
    }

    public static boolean isOn(Route route) {
        return Boolean.TRUE.equals(ON.get(route));
    }

    /** What the last try said; empty before the first. */
    public static String result(Route route) {
        String r = RESULT.get(route);
        return r == null ? "" : r;
    }

    /** Switches {@code route} on (at {@code strength}, 10–100) or back off. {@code done} runs on the main thread. */
    public static void toggle(Context context, Route route, int strength, Runnable done) {
        Context app = context.getApplicationContext();
        boolean on = !isOn(route);
        new Thread(() -> {
            String r;
            try {
                r = on ? apply(app, route, strength) : restore(app, route);
            } catch (Throwable t) {
                r = "Failed: " + t.getClass().getSimpleName() + ": " + t.getMessage();
                AppLog.w(TAG, route + " " + r);
            }
            String result = r;
            MAIN.post(() -> {
                RESULT.put(route, result);
                AppLog.i(TAG, route + (on ? " on: " : " off: ") + result);
                com.kooo.evcam.blackbox.BlackBox.note("Dim test " + route + (on ? " on: " : " off: ") + result);
                if (done != null) {
                    done.run();
                }
            });
        }, "dim-route").start();
    }

    /** Puts back every route that is on. App exit, or before the overlay shade comes on. */
    public static void restoreAll(Context context) {
        for (Route route : Route.values()) {
            if (isOn(route)) {
                toggle(context, route, 0, null);
            }
        }
    }

    // ------------------------------------------------------------------ on

    private static String apply(Context c, Route route, int strength) {
        int s = Math.max(10, Math.min(100, strength));
        switch (route) {
            case BRIGHTNESS:
                return applyBrightness(c, s);
            case EXTRA_DIM:
                return applyExtraDim(c, s);
            case CAR:
                return applyCar(c, s);
            case FLOATING:
                MAIN.post(() -> DimFloatActivity.open(c, s));
                ON.put(Route.FLOATING, true);
                return "Opened a dark window at " + s + "%. Try tapping the app underneath; it closes by itself after 15 seconds";
            default:
                return "?";
        }
    }

    private static String applyBrightness(Context c, int s) {
        if (!Settings.System.canWrite(c)) {
            MAIN.post(() -> {
                try {
                    c.startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            Uri.parse("package:" + c.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException ignored) {
                    // the car has no page for it
                }
            });
            return "No \"Modify system settings\" permission. Grant it on the page that opened, or over adb: "
                    + "appops set " + c.getPackageName() + " WRITE_SETTINGS allow";
        }
        int before = Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1);
        int mode = Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, -1);
        savedBrightness = before >= 0 ? before : null;
        savedBrightnessMode = mode >= 0 ? mode : null;
        Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        int target = Math.max(1, Math.round(255f * (100 - s) / 100f));
        boolean ok = Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, target);
        int now = Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, -1);
        ON.put(Route.BRIGHTNESS, true);
        return String.format(Locale.US, "Brightness %d → %d (reads %d%s). If the screen didn't change, the car ignores this setting",
                before, target, now, ok ? "" : ", write refused");
    }

    private static String applyExtraDim(Context c, int s) {
        if (c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) {
            return "No WRITE_SECURE_SETTINGS. Grant it over adb: pm grant " + c.getPackageName()
                    + " android.permission.WRITE_SECURE_SETTINGS";
        }
        // Android 12+ lets an app write these hidden keys with this permission but not read them:
        // what was there before can't be known, so switching off puts Extra dim off
        Settings.Secure.putInt(c.getContentResolver(), EXTRA_DIM_LEVEL, s);
        Settings.Secure.putInt(c.getContentResolver(), EXTRA_DIM_ON, 1);
        ON.put(Route.EXTRA_DIM, true);
        return "Extra dim on at " + s + "%. If the screen didn't change, this Android has no Extra dim";
    }

    private static String applyCar(Context c, int s) {
        Map<String, Integer> found = CarAmbientLab.constantsMatching(CAR_INCLUDE, CAR_EXCLUDE);
        if (found.isEmpty()) {
            return "No brightness function in the car's ECARX interface";
        }
        StringBuilder seen = new StringBuilder();
        String picked = null;
        Integer pickedValue = null;
        for (Map.Entry<String, Integer> e : found.entrySet()) {
            Integer value = CarAmbientLab.value(c, e.getValue());
            String support = CarAmbientLab.support(c, e.getValue());
            if (seen.length() < 600) {
                seen.append("\n").append(e.getKey()).append(" = ").append(value).append(" (").append(support).append(")");
            }
            if (picked == null && value != null && value > 0) {
                picked = e.getKey();
                pickedValue = value;
            }
        }
        if (picked == null) {
            return "Found " + found.size() + " brightness names, none reads a value:" + seen;
        }
        int max = pickedValue <= 10 ? 10 : pickedValue <= 100 ? 100 : 255;
        int target = Math.max(1, Math.round(max * (100 - s) / 100f));
        String why = CarAmbientLab.write(c, found.get(picked), target);
        Integer now = CarAmbientLab.value(c, found.get(picked));
        carId = found.get(picked);
        carSaved = pickedValue;
        ON.put(Route.CAR, true);
        return String.format(Locale.US, "%s %d → %d: %s, reads %s.%s", picked, pickedValue, target,
                why == null ? "sent" : why, now, seen);
    }

    // ------------------------------------------------------------------ off

    private static String restore(Context c, Route route) {
        ON.put(route, false);
        switch (route) {
            case BRIGHTNESS:
                if (Settings.System.canWrite(c)) {
                    if (savedBrightnessMode != null) {
                        Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, savedBrightnessMode);
                    }
                    if (savedBrightness != null) {
                        Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, savedBrightness);
                    }
                }
                return "Brightness put back to " + savedBrightness;
            case EXTRA_DIM:
                Settings.Secure.putInt(c.getContentResolver(), EXTRA_DIM_ON, 0);
                return "Extra dim off";
            case CAR:
                if (carId != null && carSaved != null) {
                    String why = CarAmbientLab.write(c, carId, carSaved);
                    return "Car brightness put back to " + carSaved + (why == null ? "" : ": " + why);
                }
                return "Nothing to put back";
            case FLOATING:
                MAIN.post(DimFloatActivity::close);
                return "Dark window closed";
            default:
                return "?";
        }
    }
}
