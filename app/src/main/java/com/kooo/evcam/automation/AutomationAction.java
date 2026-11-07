package com.kooo.evcam.automation;

import android.content.Context;

import com.kooo.evcam.R;
import com.kooo.evcam.announce.AnnouncePick;
import com.kooo.evcam.input.ShortcutAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What an automation can do. Unlike a shortcut these set a state instead of flipping it, because
 * an automation does not know what the car was left in. The key is what gets stored.
 */
public enum AutomationAction {

    DRIVE_MODE("drive_mode", R.string.automation_do_drive_mode, R.drawable.ic_automation_drive),
    MOOD_LIGHT("mood_light", R.string.automation_do_mood_light, R.drawable.ic_light),
    MOOD_COLOR("mood_color", R.string.automation_do_mood_color, R.drawable.ic_light),
    CAR_AMBIENT("car_ambient", R.string.automation_do_car_ambient, R.drawable.ic_light),
    CAR_AMBIENT_LEVEL("car_ambient_level", R.string.automation_do_car_ambient_level, R.drawable.ic_light),
    DIM("dim", R.string.automation_do_dim, R.drawable.ic_dim),
    /** Any shortcut action, by its stored key. */
    SHORTCUT("shortcut", R.string.automation_do_shortcut, R.drawable.ic_shortcut);

    /** One entry in an action's list of choices. */
    public static final class Option {
        public final String choice;
        public final String label;
        /** {@code 0xRRGGBB}, or -1 when the option is not a colour. */
        public final int color;

        Option(String choice, String label, int color) {
            this.choice = choice;
            this.label = label;
            this.color = color;
        }
    }

    static final String ON = "on";
    static final String OFF = "off";
    private static final int[] COLORS = {0xFFFFFF, 0xFF0000, 0xFF6000, 0xFFD000, 0x00FF00, 0x00E0FF, 0x0060FF, 0x8000FF, 0xFF2090};
    private static final int[] COLOR_NAMES = {R.string.automation_color_white, R.string.automation_color_red,
            R.string.automation_color_orange, R.string.automation_color_yellow, R.string.automation_color_green,
            R.string.automation_color_cyan, R.string.automation_color_blue, R.string.automation_color_purple,
            R.string.automation_color_pink};
    private static final int[] DIM_LEVELS = {30, 50, 70, 90};

    public final String key;
    public final int labelRes;
    public final int iconRes;

    AutomationAction(String key, int labelRes, int iconRes) {
        this.key = key;
        this.labelRes = labelRes;
        this.iconRes = iconRes;
    }

    public static AutomationAction fromKey(String key) {
        for (AutomationAction action : values()) {
            if (action.key.equals(key)) {
                return action;
            }
        }
        return null;
    }

    /** The choices for this action, in the order shown. */
    public List<Option> options(Context context) {
        List<Option> out = new ArrayList<>();
        switch (this) {
            case DRIVE_MODE:
                out.add(new Option("comfort", context.getString(R.string.automation_mode_comfort), -1));
                out.add(new Option("normal", context.getString(R.string.automation_mode_normal), -1));
                out.add(new Option("sport", context.getString(R.string.automation_mode_sport), -1));
                break;
            case MOOD_LIGHT:
                addOnOff(context, out);
                break;
            case MOOD_COLOR:
                addColors(context, out);
                break;
            case CAR_AMBIENT:
                addOnOff(context, out);
                addColors(context, out);
                break;
            case CAR_AMBIENT_LEVEL:
                for (int level = 1; level <= com.kooo.evcam.telemetry.CarAmbientLab.INTENSITY_MAX; level++) {
                    out.add(new Option(Integer.toString(level), String.format(Locale.getDefault(), "%d", level), -1));
                }
                break;
            case DIM:
                out.add(new Option(OFF, context.getString(R.string.home_state_off), -1));
                for (int level : DIM_LEVELS) {
                    out.add(new Option(Integer.toString(level), context.getString(R.string.automation_percent, level), -1));
                }
                break;
            default:
                break;
        }
        return out;
    }

    /** Hold-to-speak and the floating picker need a person there; Quit would stop the automations too. */
    public static boolean offered(ShortcutAction action) {
        return action != ShortcutAction.HOLD_SPEAK && action != ShortcutAction.TOGGLE_SPEAK
                && action != ShortcutAction.ANNOUNCE && action != ShortcutAction.QUIT;
    }

    /** Every step, one after the other. */
    public static String describeAll(Context context, List<AutomationRule.Step> steps) {
        StringBuilder out = new StringBuilder();
        for (AutomationRule.Step step : steps) {
            out.append(out.length() == 0 ? "" : " · ").append(describe(context, step));
        }
        return out.toString();
    }

    /** Reads like "Drive mode: Sport". */
    public static String describe(Context context, AutomationRule.Step step) {
        AutomationAction action = fromKey(step.action);
        if (action == null) {
            return "";
        }
        String name = context.getString(action.labelRes);
        if (action == SHORTCUT) {
            ShortcutAction shortcut = ShortcutAction.fromKey(step.choice);
            if (shortcut == ShortcutAction.ANNOUNCE_PLAY) {
                return AnnouncePick.label(context, step.choice);
            }
            return shortcut == null ? name : context.getString(shortcut.labelRes);
        }
        for (Option option : action.options(context)) {
            if (option.choice.equals(step.choice)) {
                return context.getString(R.string.automation_named_choice, name, option.label);
            }
        }
        return name;
    }

    private static void addOnOff(Context context, List<Option> out) {
        out.add(new Option(ON, context.getString(R.string.home_state_on), -1));
        out.add(new Option(OFF, context.getString(R.string.home_state_off), -1));
    }

    private static void addColors(Context context, List<Option> out) {
        for (int i = 0; i < COLORS.length; i++) {
            out.add(new Option(Integer.toHexString(COLORS[i]), context.getString(COLOR_NAMES[i]), COLORS[i]));
        }
    }
}
