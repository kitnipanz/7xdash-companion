package com.kooo.evcam.automation;

import android.content.Context;

import com.kooo.evcam.R;
import com.kooo.evcam.announce.AnnouncePick;
import com.kooo.evcam.input.ShortcutAction;
import com.kooo.evcam.nav.SavedPlaces;

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
    AC("ac", R.string.automation_do_ac, R.drawable.ic_automation_climate),
    TEMP_ZONES("temp_zones", R.string.automation_do_temp_zones, R.drawable.ic_automation_temp_zones),
    /** Only in P ({@link CarControls#openGlovebox}). */
    GLOVEBOX("glovebox", R.string.automation_do_glovebox, R.drawable.ic_automation_glovebox),
    /** How far the power tailgate opens, 50 to 100 percent; the choice is the percent. */
    TAILGATE_HEIGHT("tailgate_height", R.string.automation_do_tailgate_height, R.drawable.ic_automation_tailgate),
    /** How far the sunroof shade is open, 0 (closed) to 100; the choice is the percent ({@link SunShade}). */
    SUNROOF_SHADE("sunroof_shade", R.string.automation_do_sunroof_shade, R.drawable.ic_sunshade),
    /** How heavy the steering wheel feels: light, balanced or heavy ({@link SteeringFeel}). */
    STEERING_FEEL("steering_feel", R.string.automation_do_steering_feel, R.drawable.ic_steering),
    /** Rear fog lamp on or off. */
    REAR_FOG("rear_fog", R.string.automation_do_rear_fog, R.drawable.ic_fog),
    /** What the car does outside when it locks or unlocks; the choice is the car's value. */
    LOCK_FEEDBACK("lock_feedback", R.string.automation_do_lock_feedback, R.drawable.ic_automation_lock_feedback),
    /** Start guiding to a saved place; the choice is its id. Leaves a trip already on alone. */
    NAVIGATE("navigate", R.string.automation_do_navigate, R.drawable.ic_navigation),
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
    static final String SYNCED = "synced";
    static final String DUAL = "dual";
    static final String OPEN = "open";
    private static final int[] COLORS = {0xFFFFFF, 0xFF0000, 0xFF6000, 0xFFD000, 0x00FF00, 0x00E0FF, 0x0060FF, 0x8000FF, 0xFF2090};
    private static final int[] COLOR_NAMES = {R.string.automation_color_white, R.string.automation_color_red,
            R.string.automation_color_orange, R.string.automation_color_yellow, R.string.automation_color_green,
            R.string.automation_color_cyan, R.string.automation_color_blue, R.string.automation_color_purple,
            R.string.automation_color_pink};
    private static final int[] DIM_LEVELS = {30, 50, 70, 90};
    /** The car's values for lock feedback, in the order its own settings screen shows them. */
    static final int[] LOCK_FEEDBACK_VALUES = {0, 4, 2, 3};
    private static final int[] LOCK_FEEDBACK_NAMES = {R.string.automation_lock_hazard,
            R.string.automation_lock_hazard_sound, R.string.automation_lock_hazard_headlight,
            R.string.automation_lock_hazard_signal};
    static final int[] TAILGATE_LEVELS = {50, 60, 70, 80, 90, 100};
    /** Sunroof shade, percent open: 0 reads Closed, 100 reads Open. */
    static final int[] SUNSHADE_LEVELS = {0, 25, 50, 75, 100};

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
            case AC:
            case REAR_FOG:
                addOnOff(context, out);
                break;
            case TEMP_ZONES:
                out.add(new Option(SYNCED, context.getString(R.string.automation_temp_synced), -1));
                out.add(new Option(DUAL, context.getString(R.string.automation_temp_dual), -1));
                break;
            case GLOVEBOX:
                out.add(new Option(OPEN, context.getString(R.string.automation_open), -1));
                break;
            case TAILGATE_HEIGHT:
                for (int level : TAILGATE_LEVELS) {
                    out.add(new Option(Integer.toString(level), context.getString(R.string.automation_percent, level), -1));
                }
                break;
            case SUNROOF_SHADE:
                for (int level : SUNSHADE_LEVELS) {
                    String label = level == 0 ? context.getString(R.string.sunshade_closed)
                            : level == 100 ? context.getString(R.string.sunshade_open)
                            : context.getString(R.string.automation_percent, level);
                    out.add(new Option(Integer.toString(level), label, -1));
                }
                break;
            case STEERING_FEEL:
                out.add(new Option(SteeringFeel.CHOICE_LIGHT, context.getString(R.string.steering_light), -1));
                out.add(new Option(SteeringFeel.CHOICE_BALANCED, context.getString(R.string.steering_balanced), -1));
                out.add(new Option(SteeringFeel.CHOICE_HEAVY, context.getString(R.string.steering_heavy), -1));
                break;
            case LOCK_FEEDBACK:
                for (int i = 0; i < LOCK_FEEDBACK_VALUES.length; i++) {
                    out.add(new Option(Integer.toString(LOCK_FEEDBACK_VALUES[i]), context.getString(LOCK_FEEDBACK_NAMES[i]), -1));
                }
                break;
            case MOOD_COLOR:
                addColors(context, out);
                break;
            case NAVIGATE:
                for (SavedPlaces.Place place : SavedPlaces.load(context)) {
                    out.add(new Option(Integer.toString(place.id), place.name, -1));
                }
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
