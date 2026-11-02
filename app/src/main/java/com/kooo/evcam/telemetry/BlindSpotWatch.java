package com.kooo.evcam.telemetry;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.blackbox.BlackBox;

/**
 * Which side the car is warning about. Built in: the car's rear cross traffic alert flags,
 * {@code PAS_FUNC_RCTA_LEFT_WARNING} and {@code PAS_FUNC_RCTA_RIGHT_WARNING} (found with the
 * Blind-spot warning finder on the 7X, 2026-11-02). A side the driver taught in the finder
 * ({@link AppConfig#getBlindSpotLearned}) wins over the built-in one.
 *
 * <p>Read while a turn signal is on (the side view and the mood light use it then), and in D, R
 * or N while the red screen edge is on ({@code overlay.BlindSpotEdge}). In P and with both off it
 * stays quiet. Readings go into {@link VehicleState#blindSpotWarning} through
 * {@link Telemetry#setBlindSpotWarning}.</p>
 */
public final class BlindSpotWatch implements Telemetry.Listener {

    static final long POLL_MS = 200L;
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
    /** Raw RCTA values last written to the black box, so the warning values can be checked from a drive. */
    private Integer lastRawLeft;
    private Integer lastRawRight;

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
        boolean want = signalling || (driving && (config.isBlindSpotEdgeEnabled() || config.isSidePopupOnWarning()));
        if (want == polling) {
            return;
        }
        polling = want;
        work.removeCallbacks(poll);
        if (want) {
            work.post(poll);
        } else {
            lastNoted = null;
            lastRawLeft = null;
            lastRawRight = null;
            work.post(() -> Telemetry.get().setBlindSpotWarning(null));
        }
    }

    /** Bits {@link VehicleState#TURN_LEFT} / {@link VehicleState#TURN_RIGHT}; null when nothing is readable. */
    private Integer read() {
        Learned l = config.getBlindSpotLearned();
        Integer left = CarAmbientLab.value(app, l.leftId != 0 ? l.leftId : RCTA_LEFT);
        Integer right = CarAmbientLab.value(app, l.rightId != 0 ? l.rightId : RCTA_RIGHT);
        noteRaw(l, left, right);
        if (left == null && right == null) {
            return null;
        }
        return sides(l, left, right);
    }

    /** Every change of the built-in RCTA values goes to the black box: what the car really sends is still being checked. */
    private void noteRaw(Learned l, Integer left, Integer right) {
        if (l.leftId == 0 && !java.util.Objects.equals(left, lastRawLeft)) {
            BlackBox.note("RCTA 左: " + lastRawLeft + " → " + left);
            lastRawLeft = left;
        }
        if (l.rightId == 0 && !java.util.Objects.equals(right, lastRawRight)) {
            BlackBox.note("RCTA 右: " + lastRawRight + " → " + right);
            lastRawRight = right;
        }
    }

    /** An RCTA flag that is warning: 0 is quiet and 255 is "no reading", anything between is a warning. Pure, see the test. */
    public static boolean rctaWarning(Integer value) {
        return value != null && value > 0 && value < 255;
    }

    /**
     * Which sides warn. A learned side warns when it reads the learned value; a side not learned
     * reads the built-in RCTA flag ({@link #rctaWarning}). Pure, see the test.
     */
    public static int sides(Learned l, Integer leftValue, Integer rightValue) {
        int out = 0;
        if (l.leftId != 0 ? leftValue != null && leftValue == l.leftValue : rctaWarning(leftValue)) {
            out |= VehicleState.TURN_LEFT;
        }
        if (l.rightId != 0 ? rightValue != null && rightValue == l.rightValue : rctaWarning(rightValue)) {
            out |= VehicleState.TURN_RIGHT;
        }
        return out;
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
