package com.kooo.evcam.telemetry;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.blackbox.BlackBox;

/**
 * Which side the car's blind-spot assist is warning about, from the car settings the driver
 * taught 7xDash in the Blind-spot warning finder ({@link AppConfig#getBlindSpotLearned}). The
 * number is not known for this car, so it is learned rather than built in.
 *
 * <p>Only read while a turn signal is on: that is the only time the side view and the mood light
 * use it, and it keeps the car interface quiet the rest of the time. Readings go into
 * {@link VehicleState#blindSpotWarning} through {@link Telemetry#setBlindSpotWarning}.</p>
 */
public final class BlindSpotWatch implements Telemetry.Listener {

    static final long POLL_MS = 200L;

    private static BlindSpotWatch installed;

    private final Context app;
    private final AppConfig config;
    private final HandlerThread thread = new HandlerThread("blind-spot-watch");
    private final Handler work;
    private boolean polling;
    private Integer lastNoted;

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
        boolean want = signalling && config.getBlindSpotLearned().any();
        if (want == polling) {
            return;
        }
        polling = want;
        work.removeCallbacks(poll);
        if (want) {
            work.post(poll);
        } else {
            lastNoted = null;
            work.post(() -> Telemetry.get().setBlindSpotWarning(null));
        }
    }

    /** Bits {@link VehicleState#TURN_LEFT} / {@link VehicleState#TURN_RIGHT}; null when nothing is learned or readable. */
    private Integer read() {
        Learned l = config.getBlindSpotLearned();
        Integer left = l.leftId != 0 ? CarAmbientLab.value(app, l.leftId) : null;
        Integer right = l.rightId != 0 ? CarAmbientLab.value(app, l.rightId) : null;
        if (left == null && right == null) {
            return null;
        }
        return sides(l, left, right);
    }

    /** Which sides warn, given what the car reads for the learned settings. Pure, see the test. */
    public static int sides(Learned l, Integer leftValue, Integer rightValue) {
        int out = 0;
        if (l.leftId != 0 && leftValue != null && leftValue == l.leftValue) {
            out |= VehicleState.TURN_LEFT;
        }
        if (l.rightId != 0 && rightValue != null && rightValue == l.rightValue) {
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
