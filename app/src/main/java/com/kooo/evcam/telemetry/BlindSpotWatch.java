package com.kooo.evcam.telemetry;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.blackbox.BlackBox;

/**
 * Which side the car is warning about. Built in: the warning lamps in the mirrors,
 * {@code BCM_DOOR_OPEN_WARN_LEFT_INDCN} / {@code RIGHT_INDCN} (all-signals recording, 2026-11-02),
 * and the rear cross traffic alert flags {@code PAS_FUNC_RCTA_LEFT_WARNING} / {@code RIGHT_WARNING};
 * either one warning counts. A side the driver taught in the finder
 * ({@link AppConfig#getBlindSpotLearned}) wins, unless it was taught 255 ("no reading").
 *
 * <p>Read while a turn signal is on (the side view and the mood light use it then), and in D, R
 * or N while Show the side when the car warns is on (the side view opens for it). In P and with both off it
 * stays quiet. Readings go into {@link VehicleState#blindSpotWarning} through
 * {@link Telemetry#setBlindSpotWarning}.</p>
 */
public final class BlindSpotWatch implements Telemetry.Listener {

    static final long POLL_MS = 200L;
    /** Half a blink of every blind-spot alert: the side view's red edge and the lights, so they blink together. */
    public static final long BLINK_HALF_MS = 350L;

    /**
     * Red half of the shared blink clock at this uptime. The red edge and the lights both read this
     * clock instead of each counting from when it started, so they turn red and back together.
     */
    public static boolean redHalfAt(long uptimeMs) {
        return (Math.max(0L, uptimeMs) / BLINK_HALF_MS) % 2 == 0;
    }

    /** How long until the shared clock flips. */
    public static long untilFlip(long uptimeMs) {
        return BLINK_HALF_MS - Math.max(0L, uptimeMs) % BLINK_HALF_MS;
    }

    private static volatile int edgeSides;
    private static final java.util.List<Runnable> edgeListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Sides whose red edge is on screen now (the side view shows that side and the car warns about
     * it). While it shows, the lights blink too, whatever the speed, so the two never disagree.
     */
    public static int edgeShown() {
        return edgeSides;
    }

    /** Main thread: the side view says which red edges are on screen now. */
    public static void setEdgeShown(int sides) {
        if (sides != edgeSides) {
            edgeSides = sides;
            for (Runnable listener : edgeListeners) {
                listener.run();
            }
        }
    }

    /** Called on the main thread when the red edges on screen change. */
    public static void addEdgeListener(Runnable listener) {
        edgeListeners.add(listener);
    }

    /**
     * Warned sides that alert now (bits {@link VehicleState#TURN_LEFT} / {@link VehicleState#TURN_RIGHT}).
     * Hazards on: none. A side alerts when the turn signal points at it, at any speed, or when the car
     * goes at least {@code minSpeedKmh} (0 = any speed; an unknown speed does not block), so city
     * traffic alone does not keep warning. Pure, see the test.
     */
    public static int alertSides(Integer warned, Integer turnSignal, Boolean hazard, Float speedKmh, int minSpeedKmh) {
        if (warned == null || warned == 0 || Boolean.TRUE.equals(hazard)) {
            return 0;
        }
        int sides = warned & (VehicleState.TURN_LEFT | VehicleState.TURN_RIGHT);
        boolean fastEnough = minSpeedKmh <= 0 || speedKmh == null || speedKmh >= minSpeedKmh;
        if (fastEnough) {
            return sides;
        }
        boolean signalling = turnSignal != null
                && (turnSignal == VehicleState.TURN_LEFT || turnSignal == VehicleState.TURN_RIGHT);
        return signalling ? sides & turnSignal : 0;
    }
    /** BCM_DOOR_OPEN_WARN_LEFT_INDCN / RIGHT_INDCN (IDoor): the warning lamps in the mirrors. Read 0 at rest (2026-11-02 recording). */
    public static final int MIRROR_LEFT = 0xA0005002;
    public static final int MIRROR_RIGHT = 0xA0005003;
    static final int NO_READING = 255;
    /** PAS_FUNC_RCTA_LEFT_WARNING / PAS_FUNC_RCTA_RIGHT_WARNING (IPAS). */
    public static final int RCTA_LEFT = 0x23050200;
    public static final int RCTA_RIGHT = 0x23050300;

    private static BlindSpotWatch installed;

    private final Context app;
    private final AppConfig config;
    private final HandlerThread thread = new HandlerThread("blind-spot-watch");
    private final Handler work;
    private boolean polling;
    private Integer lastNoted;
    /** Built-in signal values last written to the black box, so the warning values can be checked from a drive. */
    private final java.util.Map<Integer, Integer> lastRaw = new java.util.HashMap<>();

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            Integer sides = read();
            Telemetry.get().setBlindSpotWarning(sides);
            if (sides != null && !sides.equals(lastNoted)) {
                if (sides != 0) {
                    BlackBox.note("盲区报警：" + (sides == 3 ? "两侧" : sides == VehicleState.TURN_LEFT ? "左侧" : "右侧"));
                }
                lastNoted = sides;
            }
            if (polling) {
                work.postDelayed(this, POLL_MS);
            }
        }
    };

    private BlindSpotWatch(Context context) {
        app = context.getApplicationContext();
        config = new AppConfig(app);
        thread.start();
        work = new Handler(thread.getLooper());
    }

    /** Application start. Repeat calls do nothing. */
    public static void install(Context context) {
        if (installed != null) {
            return;
        }
        installed = new BlindSpotWatch(context);
        Telemetry.get().addListener(installed);
    }

    @Override
    public void onReadingsChanged(Readings readings) {
        VehicleState s = Telemetry.get().latest();
        boolean signalling = s.turnSignal != null && s.turnSignal != 0 && !Boolean.TRUE.equals(s.hazard);
        boolean driving = "D".equals(s.gear) || "R".equals(s.gear) || "N".equals(s.gear);
        boolean want = signalling || (driving && config.isSidePopupOnWarning());
        if (want == polling) {
            return;
        }
        polling = want;
        work.removeCallbacks(poll);
        if (want) {
            work.post(poll);
        } else {
            lastNoted = null;
            lastRaw.clear();
            work.post(() -> Telemetry.get().setBlindSpotWarning(null));
        }
    }

    /** How long Show a test warning pretends the car warns. */
    static final long TEST_MS = 4_000L;

    /**
     * Settings → Show a test warning: pretends the car warns of a car on the left for
     * {@link #TEST_MS}, so the side view opens with its red edge. Only while not polling (parked):
     * a real reading would replace it at once.
     */
    public static void test() {
        BlindSpotWatch w = installed;
        if (w == null || w.polling) {
            return;
        }
        w.work.post(() -> Telemetry.get().setBlindSpotWarning(VehicleState.TURN_LEFT));
        w.work.postDelayed(() -> {
            if (!w.polling) {
                Telemetry.get().setBlindSpotWarning(null);
            }
        }, TEST_MS);
    }

    /** Bits {@link VehicleState#TURN_LEFT} / {@link VehicleState#TURN_RIGHT}; null when nothing is readable. */
    private Integer read() {
        Learned l = config.getBlindSpotLearned();
        boolean taughtLeft = taught(l.leftId, l.leftValue);
        boolean taughtRight = taught(l.rightId, l.rightValue);
        Integer learnedLeft = taughtLeft ? CarAmbientLab.value(app, l.leftId) : null;
        Integer learnedRight = taughtRight ? CarAmbientLab.value(app, l.rightId) : null;
        Integer mirrorLeft = taughtLeft ? null : raw("mirror left", MIRROR_LEFT);
        Integer mirrorRight = taughtRight ? null : raw("mirror right", MIRROR_RIGHT);
        Integer rctaLeft = taughtLeft ? null : raw("RCTA left", RCTA_LEFT);
        Integer rctaRight = taughtRight ? null : raw("RCTA right", RCTA_RIGHT);
        // LCMA blind spot lamp from the settings service: bits, null when not readable
        Integer lcma = SettingZeekrWatch.lcmaSides();
        if (learnedLeft == null && learnedRight == null && mirrorLeft == null && mirrorRight == null
                && rctaLeft == null && rctaRight == null && lcma == null) {
            return null;
        }
        int out = 0;
        if (side(taughtLeft, l.leftValue, learnedLeft, mirrorLeft, rctaLeft)
                || lcmaWarns(lcma, VehicleState.TURN_LEFT)) {
            out |= VehicleState.TURN_LEFT;
        }
        if (side(taughtRight, l.rightValue, learnedRight, mirrorRight, rctaRight)
                || lcmaWarns(lcma, VehicleState.TURN_RIGHT)) {
            out |= VehicleState.TURN_RIGHT;
        }
        return out;
    }

    /** The car's own blind spot lamp (LCMA) is lit on that side. It always counts, taught or not: it is the car's own warning. */
    public static boolean lcmaWarns(Integer lcmaSides, int bit) {
        return lcmaSides != null && (lcmaSides & bit) != 0;
    }

    /** Reads a built-in signal; every change goes to the black box, since what the car really sends is still being checked. */
    private Integer raw(String label, int id) {
        Integer now = CarAmbientLab.value(app, id);
        if (!java.util.Objects.equals(now, lastRaw.get(id)) || !lastRaw.containsKey(id)) {
            BlackBox.note("Blind-spot signal " + label + ": " + lastRaw.get(id) + " → " + now);
            lastRaw.put(id, now);
        }
        return now;
    }

    /** A taught side counts only when it was taught a real value: 255 is what the car reads with nothing to report. */
    public static boolean taught(int id, int value) {
        return id != 0 && value != NO_READING;
    }

    /** A built-in warning signal that is on: 0 is quiet and 255 is "no reading", anything between is a warning. */
    public static boolean builtInWarning(Integer value) {
        return value != null && value > 0 && value < NO_READING;
    }

    /**
     * Does one side warn? A taught side warns when it reads its taught value; otherwise the mirror
     * warning lamp or the RCTA flag, whichever warns. Pure, see the test.
     */
    public static boolean side(boolean taught, int taughtValue, Integer learnedNow, Integer mirrorNow, Integer rctaNow) {
        if (taught) {
            return learnedNow != null && learnedNow == taughtValue;
        }
        return builtInWarning(mirrorNow) || builtInWarning(rctaNow);
    }

    /** What the finder learned: for each side, a car setting and the value it reads while warning. 0 = not learned. */
    public static final class Learned {
        public final int leftId;
        public final int leftValue;
        public final int rightId;
        public final int rightValue;

        public Learned(int leftId, int leftValue, int rightId, int rightValue) {
            this.leftId = leftId;
            this.leftValue = leftValue;
            this.rightId = rightId;
            this.rightValue = rightValue;
        }

        public boolean any() {
            return leftId != 0 || rightId != 0;
        }
    }
}
