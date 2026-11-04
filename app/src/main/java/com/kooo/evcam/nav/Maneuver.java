package com.kooo.evcam.nav;

/** The turn at the start of a step, plus the arrival at the end. */
public enum Maneuver {
    DEPART,
    STRAIGHT,
    TURN_SLIGHT_LEFT,
    TURN_LEFT,
    TURN_SHARP_LEFT,
    TURN_SLIGHT_RIGHT,
    TURN_RIGHT,
    TURN_SHARP_RIGHT,
    UTURN_LEFT,
    UTURN_RIGHT,
    RAMP_LEFT,
    RAMP_RIGHT,
    FORK_LEFT,
    FORK_RIGHT,
    MERGE,
    ROUNDABOUT_LEFT,
    ROUNDABOUT_RIGHT,
    FERRY,
    NAME_CHANGE,
    ARRIVE;

    /** The Routes API's name for it; unknown or missing names count as straight on. */
    public static Maneuver of(String name) {
        if (name == null || name.isEmpty()) {
            return STRAIGHT;
        }
        if ("FERRY_TRAIN".equals(name)) {
            return FERRY;
        }
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return STRAIGHT;
        }
    }

    /** TomTom's name for it; unknown or missing names count as straight on. */
    public static Maneuver ofTomTom(String name) {
        if (name == null) {
            return STRAIGHT;
        }
        switch (name) {
            case "DEPART":
                return DEPART;
            case "KEEP_LEFT":
            case "BEAR_LEFT":
                return TURN_SLIGHT_LEFT;
            case "TURN_LEFT":
                return TURN_LEFT;
            case "SHARP_LEFT":
                return TURN_SHARP_LEFT;
            case "KEEP_RIGHT":
            case "BEAR_RIGHT":
                return TURN_SLIGHT_RIGHT;
            case "TURN_RIGHT":
                return TURN_RIGHT;
            case "SHARP_RIGHT":
                return TURN_SHARP_RIGHT;
            case "MAKE_UTURN":
            case "TRY_MAKE_UTURN":
                return UTURN_RIGHT;
            case "ENTER_MOTORWAY":
            case "ENTER_FREEWAY":
            case "ENTER_HIGHWAY":
            case "ENTRANCE_RAMP":
                return MERGE;
            case "MOTORWAY_EXIT_LEFT":
                return RAMP_LEFT;
            case "TAKE_EXIT":
            case "MOTORWAY_EXIT_RIGHT":
                return RAMP_RIGHT;
            case "TAKE_FERRY":
                return FERRY;
            case "ROUNDABOUT_RIGHT":
                return ROUNDABOUT_RIGHT;
            case "ROUNDABOUT_LEFT":
            case "ROUNDABOUT_CROSS":
            case "ROUNDABOUT_BACK":
                return ROUNDABOUT_LEFT;
            case "SWITCH_PARALLEL_ROAD":
            case "SWITCH_MAIN_ROAD":
                return NAME_CHANGE;
            case "ARRIVE":
            case "ARRIVE_LEFT":
            case "ARRIVE_RIGHT":
            case "WAYPOINT_REACHED":
                return ARRIVE;
            default:
                return STRAIGHT;
        }
    }

    /** Arrows that point left (drawn mirrored). */
    public boolean left() {
        switch (this) {
            case TURN_SLIGHT_LEFT:
            case TURN_LEFT:
            case TURN_SHARP_LEFT:
            case UTURN_LEFT:
            case RAMP_LEFT:
            case FORK_LEFT:
            case ROUNDABOUT_LEFT:
                return true;
            default:
                return false;
        }
    }
}
