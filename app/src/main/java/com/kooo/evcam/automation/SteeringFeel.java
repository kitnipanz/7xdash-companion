package com.kooo.evcam.automation;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.kooo.evcam.R;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.telemetry.CarAmbientLab;

/**
 * How heavy the steering wheel feels, through the function the car's own drive mode settings use
 * ({@code IDriveMode.DM_FUNC_DM_CUSTOM_STEERING_WHEEL_FEEL}). Like the drive mode, its value is its
 * own number plus 1 (light), 2 (balanced) or 3 (heavy). Read and sent on a 7X from the Car buttons
 * lab, in P; the owner saw the car's setting and the wheel follow. The car itself decides when it
 * takes a change.
 */
public final class SteeringFeel {

    static final String NAME = "DM_FUNC_DM_CUSTOM_STEERING_WHEEL_FEEL";
    /** The number seen on a 7X, for when the library cannot be searched by name. */
    static final int KNOWN_ID = 0x22030900;
    static final int LIGHT = 0x22030901;
    static final int BALANCED = 0x22030902;
    static final int HEAVY = 0x22030903;

    static final String CHOICE_LIGHT = "light";
    static final String CHOICE_BALANCED = "balanced";
    static final String CHOICE_HEAVY = "heavy";

    private SteeringFeel() {
    }

    /** Stored choice to the car's value; null when it is not one. */
    static Integer value(String choice) {
        if (CHOICE_LIGHT.equals(choice)) {
            return LIGHT;
        }
        if (CHOICE_BALANCED.equals(choice)) {
            return BALANCED;
        }
        return CHOICE_HEAVY.equals(choice) ? HEAVY : null;
    }

    /** One button: light, balanced, heavy, light again. Not known starts at light. */
    static int next(Integer current) {
        if (current == null) {
            return LIGHT;
        }
        switch (current) {
            case LIGHT:
                return BALANCED;
            case BALANCED:
                return HEAVY;
            default:
                return LIGHT;
        }
    }

    /** The name shown for one of the car's values. */
    static int labelRes(int value) {
        return value == LIGHT ? R.string.steering_light
                : value == HEAVY ? R.string.steering_heavy : R.string.steering_balanced;
    }

    /** Sets the feel from a stored choice. Blocking. Null when sent, else why not. */
    static String set(Context context, String choice) {
        Integer want = value(choice);
        if (want == null) {
            BlackBox.note("Steering feel: not sent, choice " + choice);
            return context.getString(R.string.automation_steering_bad);
        }
        return send(context, want);
    }

    private static int id() {
        Integer found = CarAmbientLab.functionId(NAME);
        return found == null ? KNOWN_ID : found;
    }

    /** What the car reads now; null when it is not one of the three. */
    private static Integer read(Context context, int id) {
        Integer v = CarAmbientLab.value(context, id);
        return v != null && (v == LIGHT || v == BALANCED || v == HEAVY) ? v : null;
    }

    private static String send(Context context, int want) {
        int id = id();
        Integer before = read(context, id);
        if (Integer.valueOf(want).equals(before)) {
            return null;
        }
        String failed = CarAmbientLab.write(context, id, want);
        BlackBox.note(String.format(java.util.Locale.US, "Steering feel 0x%08X: %s -> 0x%08X %s",
                id, before == null ? "?" : String.format(java.util.Locale.US, "0x%08X", before), want,
                failed == null ? "sent" : failed));
        return failed;
    }

    /** Shortcut: light. */
    public static void light(Context context) {
        move(context, current -> LIGHT);
    }

    /** Shortcut: balanced. */
    public static void balanced(Context context) {
        move(context, current -> BALANCED);
    }

    /** Shortcut: heavy. */
    public static void heavy(Context context) {
        move(context, current -> HEAVY);
    }

    /** Shortcut: the next one after what the car reads now. */
    public static void cycle(Context context) {
        move(context, SteeringFeel::next);
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
                        ? app.getString(R.string.steering_set, app.getString(labelRes(want)))
                        : app.getString(R.string.steering_failed, failed);
                com.kooo.evcam.ui.Notice.makeText(app, text, Toast.LENGTH_SHORT).show();
            });
        }, "steering-feel").start();
    }
}
