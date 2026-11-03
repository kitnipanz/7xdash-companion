package com.kooo.evcam.nav;

/** The turn at the start of a step, as the Routes API names it, plus the arrival at the end. */
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
