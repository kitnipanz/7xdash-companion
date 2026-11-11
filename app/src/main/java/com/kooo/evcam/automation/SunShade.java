package com.kooo.evcam.automation;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.kooo.evcam.R;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.telemetry.CarAmbientLab;

/**
 * The sunroof shade, through the function the car's own Settings app moves it with
 * ({@code SETTING_FUNC_WINDOW_CLOSE_SUNCURTAIN}, its slider position plus one). On a 7X it reads
 * and takes {@link #CLOSED} (shut) to {@link #OPEN} (all the way open), seen on the car. Here it is
 * a percent: 0 closed, 100 open. The car itself decides when the shade may move.
 */
public final class SunShade {

    static final String NAME = "SETTING_FUNC_WINDOW_CLOSE_SUNCURTAIN";
    /** The number seen on a 7X, for when the library cannot be searched by name. */
    static final int KNOWN_ID = 0x20080100;
    /**
     * The zone it lives in: read without one it says 255 (seen in a signal recording on a 7X), read
     * in this zone it gives the position. Sent in the same zone.
     */
    static final int ZONE = 0x8;
    static final int CLOSED = 1;
    static final int OPEN = 26;

    private SunShade() {
    }

    /** The car's value for a percent open, rounded; out of range is clamped. */
    static int valueFor(int percent) {
        int p = Math.max(0, Math.min(100, percent));
        return CLOSED + Math.round(p * (OPEN - CLOSED) / 100f);
    }

    /** The percent in a stored choice, or null when it is not a number from 0 to 100. */
    static Integer percent(String choice) {
        try {
            int p = Integer.parseInt(choice);
            return p >= 0 && p <= 100 ? p : null;
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }

    /** One button: open when it is closed (or not known), else close. */
    static int toggleTarget(Integer current) {
        return current == null || current <= CLOSED ? OPEN : CLOSED;
    }

    /** Moves the shade to {@code percent} open. Blocking. Null when sent, else why not. */
    static String set(Context context, String choice) {
        Integer p = percent(choice);
        if (p == null) {
            BlackBox.note("Sunroof shade: not sent, choice " + choice);
            return context.getString(R.string.automation_sunshade_bad);
        }
        return send(context, valueFor(p));
    }

    private static int id() {
        Integer found = CarAmbientLab.functionId(NAME);
        return found == null ? KNOWN_ID : found;
    }

    /** Where the shade is, {@link #CLOSED} to {@link #OPEN}; null when the car does not say. */
    private static Integer read(Context context, int id) {
        Integer v = CarAmbientLab.valueInZone(context, id, ZONE);
        return v != null && v >= CLOSED && v <= OPEN ? v : null;
    }

    private static String send(Context context, int want) {
        int id = id();
        Integer before = read(context, id);
        if (Integer.valueOf(want).equals(before)) {
            return null;
        }
        String failed = CarAmbientLab.writeInZone(context, id, ZONE, want);
        BlackBox.note(String.format(java.util.Locale.US, "Sunroof shade 0x%08X zone 0x%X: %s -> %d %s",
                id, ZONE, before, want, failed == null ? "sent" : failed));
        return failed;
    }

    /** Shortcut: open all the way. */
    public static void open(Context context) {
        move(context, current -> OPEN);
    }

    /** Shortcut: close all the way. */
    public static void close(Context context) {
        move(context, current -> CLOSED);
    }

    /** Shortcut: open when closed, else close. */
    public static void toggle(Context context) {
        move(context, SunShade::toggleTarget);
    }

    private interface Target {
        int from(Integer current);
    }

    /** Off the main thread, then says what it did. */
    private static void move(Context context, Target target) {
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            int want = target.from(read(app, id()));
            String failed = send(app, want);
            main.post(() -> {
                String text = failed == null
                        ? app.getString(want == CLOSED ? R.string.sunshade_closing : R.string.sunshade_opening)
                        : app.getString(R.string.sunshade_failed, failed);
                com.kooo.evcam.ui.Notice.makeText(app, text, Toast.LENGTH_SHORT).show();
            });
        }, "sun-shade").start();
    }
}
