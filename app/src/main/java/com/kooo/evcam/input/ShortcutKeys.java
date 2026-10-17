package com.kooo.evcam.input;

import android.content.Context;
import android.view.KeyEvent;

import com.kooo.evcam.AppConfig;

import java.util.List;

/**
 * Shared entry for shortcut key events: classifies tap / long / double, then runs the action.
 * KeyCatcher, the accessibility service, and MainActivity all call {@link #dispatch}.
 */
public final class ShortcutKeys {

    private static Context appContext;
    private static PressClassifier classifier;
    private static int liveKey = Integer.MIN_VALUE;
    private static int liveScan = Integer.MIN_VALUE;

    private ShortcutKeys() {
    }

    public static synchronized boolean dispatch(Context context, KeyEvent event) {
        if (context == null || event == null || ShortcutCapture.isActive()
                || event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            return false;
        }
        appContext = context.getApplicationContext();
        if (SpeakCapture.take(appContext, event)) {
            return true;
        }
        if (classifier == null) {
            classifier = new PressClassifier((keyCode, scanCode, deviceName, kind) -> {
                Context ctx = appContext;
                if (ctx == null) {
                    return;
                }
                Shortcut hit = ShortcutBook.match(
                        ShortcutBook.parse(new AppConfig(ctx).getButtonShortcuts()),
                        keyCode, scanCode, deviceName, kind);
                if (hit != null) {
                    ShortcutPerformer.perform(ctx, hit.action);
                }
            });
        }
        int action = event.getAction();
        boolean held = liveEvent(event);
        boolean speak = holdToSpeak(appContext, event);
        if (action == KeyEvent.ACTION_DOWN && (speak || (held && event.getRepeatCount() > 0))) {
            AppConfig config = new AppConfig(appContext);
            boolean once = SpeakPlan.isOnce(config.getSpeakMode());
            if (event.getRepeatCount() == 0) {
                liveKey = event.getKeyCode();
                liveScan = event.getScanCode();
                if (once && MegaphoneService.isOpen()) {
                    MegaphoneService.stop(appContext);
                } else if (once) {
                    MegaphoneService.start(appContext, config.getSpeakSeconds() * 1000L);
                } else {
                    MegaphoneService.start(appContext);
                }
            } else if (!once) {
                MegaphoneService.beat();
            }
            return true;
        }
        if (held && action == KeyEvent.ACTION_UP) {
            liveKey = Integer.MIN_VALUE;
            liveScan = Integer.MIN_VALUE;
            if (!SpeakPlan.isOnce(new AppConfig(appContext).getSpeakMode())) {
                MegaphoneService.stop(appContext);
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            if (!bound(appContext, event)) {
                return false;
            }
        }
        return classifier.onKeyEvent(event);
    }

    private static boolean liveEvent(KeyEvent event) {
        if (liveKey == Integer.MIN_VALUE) {
            return false;
        }
        if (liveKey != 0) {
            return event.getKeyCode() == liveKey;
        }
        return event.getScanCode() == liveScan;
    }

    private static boolean holdToSpeak(Context context, KeyEvent event) {
        String device = event.getDevice() == null || event.getDevice().getName() == null
                ? "" : event.getDevice().getName();
        List<Shortcut> items = ShortcutBook.parse(new AppConfig(context).getButtonShortcuts());
        return ShortcutBook.holdToSpeak(items, event.getKeyCode(), event.getScanCode(), device);
    }

    /** True when any press kind is saved for this button. */
    private static boolean bound(Context context, KeyEvent event) {
        String device = event.getDevice() == null || event.getDevice().getName() == null
                ? "" : event.getDevice().getName();
        List<Shortcut> items = ShortcutBook.parse(new AppConfig(context).getButtonShortcuts());
        for (PressKind kind : PressKind.values()) {
            if (ShortcutBook.match(items, event.getKeyCode(), event.getScanCode(), device, kind) != null) {
                return true;
            }
        }
        return false;
    }
}
