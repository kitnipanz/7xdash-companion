package com.kooo.evcam.input;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * Turns raw key down/up into tap, long-press, or double-press.
 * Long fires while still held. Double cancels the pending single tap.
 */
public final class PressClassifier {

    public static final long LONG_MS = 550L;
    public static final long DOUBLE_GAP_MS = 380L;

    public interface Listener {
        void onPress(int keyCode, int scanCode, String deviceName, PressKind kind);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final Runnable fireTap;
    private final Runnable fireLong;

    private int downKey;
    private int downScan;
    private String downDevice = "";
    private long downAt;
    private boolean longFired;
    private boolean holding;

    private int pendingTapKey;
    private int pendingTapScan;
    private String pendingTapDevice = "";

    public PressClassifier(Listener listener) {
        this.listener = listener;
        this.fireTap = () -> {
            this.listener.onPress(pendingTapKey, pendingTapScan, pendingTapDevice, PressKind.TAP);
            pendingTapKey = 0;
        };
        this.fireLong = () -> {
            if (!holding || longFired) {
                return;
            }
            longFired = true;
            cancelPendingTap();
            this.listener.onPress(downKey, downScan, downDevice, PressKind.LONG);
        };
    }

    /** True when this event was handled (consumed). */
    public boolean onKeyEvent(KeyEvent event) {
        if (event == null || event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            return onDown(event);
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            return onUp(event);
        }
        return false;
    }

    public void reset() {
        handler.removeCallbacks(fireTap);
        handler.removeCallbacks(fireLong);
        holding = false;
        longFired = false;
        pendingTapKey = 0;
    }

    private boolean onDown(KeyEvent event) {
        if (event.getRepeatCount() > 0) {
            return holding;
        }
        String device = deviceName(event);
        // Second down inside the double window: fire double, skip the pending tap
        if (pendingTapKey != 0
                && sameButton(pendingTapKey, pendingTapScan, pendingTapDevice,
                event.getKeyCode(), event.getScanCode(), device)) {
            cancelPendingTap();
            holding = true;
            longFired = true; // ignore this hold for long
            downKey = event.getKeyCode();
            downScan = event.getScanCode();
            downDevice = device;
            downAt = SystemClock.uptimeMillis();
            listener.onPress(downKey, downScan, downDevice, PressKind.DOUBLE);
            return true;
        }
        cancelPendingTap();
        holding = true;
        longFired = false;
        downKey = event.getKeyCode();
        downScan = event.getScanCode();
        downDevice = device;
        downAt = SystemClock.uptimeMillis();
        handler.removeCallbacks(fireLong);
        handler.postDelayed(fireLong, LONG_MS);
        return true;
    }

    private boolean onUp(KeyEvent event) {
        String device = deviceName(event);
        if (!holding || !sameButton(downKey, downScan, downDevice,
                event.getKeyCode(), event.getScanCode(), device)) {
            return false;
        }
        holding = false;
        handler.removeCallbacks(fireLong);
        if (longFired) {
            return true;
        }
        long held = SystemClock.uptimeMillis() - downAt;
        if (held >= LONG_MS) {
            listener.onPress(downKey, downScan, downDevice, PressKind.LONG);
            return true;
        }
        pendingTapKey = downKey;
        pendingTapScan = downScan;
        pendingTapDevice = downDevice;
        handler.removeCallbacks(fireTap);
        handler.postDelayed(fireTap, DOUBLE_GAP_MS);
        return true;
    }

    private void cancelPendingTap() {
        handler.removeCallbacks(fireTap);
        pendingTapKey = 0;
    }

    private static String deviceName(KeyEvent event) {
        return event.getDevice() == null || event.getDevice().getName() == null
                ? "" : event.getDevice().getName();
    }

    private static boolean sameButton(int keyA, int scanA, String deviceA,
                                      int keyB, int scanB, String deviceB) {
        if (!deviceA.isEmpty() && !deviceB.isEmpty() && !deviceA.equals(deviceB)) {
            return false;
        }
        if (keyA != 0 || keyB != 0) {
            return keyA == keyB;
        }
        return scanA != 0 && scanA == scanB;
    }
}
