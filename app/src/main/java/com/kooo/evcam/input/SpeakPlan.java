package com.kooo.evcam.input;

/** Saved tap-to-speak choices. Missing values keep press-and-hold at the tuned volume. */
public final class SpeakPlan {

    public static final String MODE_HOLD = "hold";
    public static final String MODE_ONCE = "once";
    public static final int DEFAULT_VOLUME = 100;
    public static final int DEFAULT_SECONDS = 10;

    private static final int[] ONCE_SECONDS = {5, 10, 20, 30};

    private SpeakPlan() {
    }

    public static boolean isOnce(String mode) {
        return MODE_ONCE.equals(mode);
    }

    public static int volume(int percent) {
        if (percent < 0) {
            return 0;
        }
        if (percent > DEFAULT_VOLUME) {
            return DEFAULT_VOLUME;
        }
        return percent;
    }

    /** Gain applied to the cabin mic. 100 stays at the level that reached the outside speaker. */
    public static int gain(int volumePercent) {
        return Megaphone.GAIN * volume(volumePercent) / DEFAULT_VOLUME;
    }

    public static int seconds(int saved) {
        for (int allowed : ONCE_SECONDS) {
            if (saved == allowed) {
                return allowed;
            }
        }
        return DEFAULT_SECONDS;
    }

    public static int[] onceSeconds() {
        return ONCE_SECONDS.clone();
    }

    public static String speaker(String saved) {
        if (saved == null || saved.isEmpty()) {
            return Megaphone.BUS_ADDRESS;
        }
        return saved;
    }
}
