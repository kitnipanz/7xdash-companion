package com.kooo.evcam.input;

import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;

import com.kooo.evcam.AppConfig;

/** One key press on the Tap to speak page becomes the button that opens the mic. */
public final class SpeakCapture {

    public interface Listener {
        void onSaved();
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile boolean armed;
    private static Listener listener;
    private static int eatenKey = Integer.MIN_VALUE;
    private static int eatenScan = Integer.MIN_VALUE;

    private SpeakCapture() {
    }

    public static void arm(Listener next) {
        listener = next;
        eatenKey = Integer.MIN_VALUE;
        eatenScan = Integer.MIN_VALUE;
        armed = true;
    }

    public static void disarm() {
        armed = false;
        listener = null;
        eatenKey = Integer.MIN_VALUE;
        eatenScan = Integer.MIN_VALUE;
    }

    public static boolean isArmed() {
        return armed || eatenKey != Integer.MIN_VALUE;
    }

    /** @return true when this event belongs to the capture and must not open the mic */
    public static boolean take(android.content.Context context, KeyEvent event) {
        if (!isArmed() || context == null || event == null
                || event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            return false;
        }
        if (eatenKey != Integer.MIN_VALUE) {
            boolean same = eatenKey != 0
                    ? event.getKeyCode() == eatenKey
                    : event.getScanCode() == eatenScan;
            if (same && event.getAction() == KeyEvent.ACTION_UP) {
                eatenKey = Integer.MIN_VALUE;
                eatenScan = Integer.MIN_VALUE;
            }
            return same;
        }
        if (!armed) {
            return false;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return true;
        }
        if (event.getRepeatCount() > 0) {
            return true;
        }
        InputDevice device = event.getDevice();
        if (device != null && device.isVirtual()) {
            return false;
        }
        String name = device == null || device.getName() == null ? "" : device.getName();
        Shortcut next = new Shortcut(event.getKeyCode(), event.getScanCode(), name,
                ShortcutAction.HOLD_SPEAK.key, PressKind.TAP.key);
        AppConfig config = new AppConfig(context.getApplicationContext());
        config.setButtonShortcuts(ShortcutBook.write(ShortcutBook.replaceAction(
                ShortcutBook.parse(config.getButtonShortcuts()), next)));
        armed = false;
        eatenKey = event.getKeyCode();
        eatenScan = event.getScanCode();
        Listener saved = listener;
        listener = null;
        if (saved != null) {
            MAIN.post(saved::onSaved);
        }
        return true;
    }
}
