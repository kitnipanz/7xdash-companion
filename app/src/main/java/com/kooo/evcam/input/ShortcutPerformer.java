package com.kooo.evcam.input;

import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.R;
import com.kooo.evcam.overlay.OverlayCoordinator;
import com.kooo.evcam.service.RecordingFloatingService;

/** Runs a saved shortcut. Recording goes through the floating service, which already knows foreground and background. */
public final class ShortcutPerformer {

    private ShortcutPerformer() {
    }

    public static void perform(Context context, String actionKey) {
        ShortcutAction action = ShortcutAction.fromKey(actionKey);
        if (context == null || action == null) {
            return;
        }
        switch (action) {
            case RECORD:
                Intent toggle = new Intent(context, RecordingFloatingService.class);
                toggle.setAction(RecordingFloatingService.ACTION_TOGGLE_RECORDING);
                context.startService(toggle);
                return;
            case MIRROR:
                AppConfig config = new AppConfig(context);
                // This button means the centre screen; the HUD one has its own action.
                if (config.isRearViewOnHud()) {
                    config.setRearViewOnHud(false);
                    com.kooo.evcam.zeekr.RearViewMirrorService.applyHud(context);
                    if (config.isRearViewEnabled()) {
                        return;
                    }
                }
                if (!OverlayCoordinator.setRearViewEnabled(context, !config.isRearViewEnabled())) {
                    toast(context);
                }
                return;
            case MIRROR_HUD:
                toggleMirrorOnHud(context);
                return;
            case BOTH_MIRRORS:
                AppConfig both = new AppConfig(context);
                if (!OverlayCoordinator.setBothMirrorsEnabled(context, !both.isBothMirrorsEnabled())) {
                    toast(context);
                }
                return;
            case APP:
                Intent open = new Intent(context, MainActivity.class);
                open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                context.startActivity(open);
                return;
            case DIM:
                AppConfig dim = new AppConfig(context);
                if (!OverlayCoordinator.setDimOverlayEnabled(context, !dim.isDimOverlayEnabled())) {
                    toast(context);
                }
                return;
            case HUD:
                toggleHud(context);
                return;
            case SAVE:
                com.kooo.evcam.recording.SaveMoment.perform(context);
                return;
            case LOCK_SAVE:
                com.kooo.evcam.storage.AutoLock.get().lockFromShortcut(context);
                return;
            case INSTANT_CAPTURE:
                com.kooo.evcam.storage.AutoLock.get().instantFromShortcut(context);
                return;
            case HOLD_SPEAK:
                // Only a press with no key-up reaches here (a sticker): holding is impossible,
                // so it behaves like click-to-speak. Held keys are handled in ShortcutKeys.
            case TOGGLE_SPEAK:
                MegaphoneService.toggle(context);
                return;
            case CABIN_PASSENGER:
                AppConfig cabin = new AppConfig(context);
                if (!OverlayCoordinator.setCabinPassengerEnabled(context, !cabin.isCabinPassengerEnabled())) {
                    toast(context);
                }
                return;
            default:
                return;
        }
    }

    /** Open the super mirror on the HUD; if it is already there, close it. */
    private static void toggleMirrorOnHud(Context context) {
        if (com.kooo.evcam.zeekr.HudDisplay.find(context) == null) {
            Toast.makeText(context, R.string.msg_no_hud, Toast.LENGTH_SHORT).show();
            return;
        }
        AppConfig config = new AppConfig(context);
        if (config.isRearViewEnabled() && config.isRearViewOnHud()) {
            config.setRearViewOnHud(false);
            OverlayCoordinator.setRearViewEnabled(context, false);
            return;
        }
        config.setRearViewOnHud(true);
        if (config.isRearViewEnabled()) {
            // Already open on the centre screen: move it.
            com.kooo.evcam.zeekr.RearViewMirrorService.applyHud(context);
        } else if (!OverlayCoordinator.setRearViewEnabled(context, true)) {
            config.setRearViewOnHud(false);
            toast(context);
        }
    }

    private static void toggleHud(Context context) {
        AppConfig config = new AppConfig(context);
        boolean on = !config.isSidePopupOnHud();
        if (on && !config.isSidePopupEnabled()
                && !OverlayCoordinator.setSidePopupEnabled(context, true)) {
            toast(context);
            return;
        }
        config.setSidePopupOnHud(on);
        com.kooo.evcam.zeekr.SideViewPopupService.applyConfig();
        if (on) {
            com.kooo.evcam.zeekr.SideViewPopupService.start(context);
        }
        HudCue.show(context, on);
    }

    private static void toast(Context context) {
        Toast.makeText(context, R.string.msg_need_overlay, Toast.LENGTH_SHORT).show();
    }
}
