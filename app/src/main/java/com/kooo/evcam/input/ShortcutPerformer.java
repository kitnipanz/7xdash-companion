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
        // Every trigger (home card, side rail, steering-wheel key, sticker) comes through here
        com.kooo.evcam.analytics.Analytics.event("shortcut", "action", action.key);
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
                boolean mirrorOn = !config.isRearViewEnabled();
                if (!OverlayCoordinator.setRearViewEnabled(context, mirrorOn)) {
                    toast(context);
                } else {
                    hud(context, R.string.home_card_rear, mirrorOn);
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
                boolean dimOn = !dim.isDimOverlayEnabled();
                if (!OverlayCoordinator.setDimOverlayEnabled(context, dimOn)) {
                    toast(context);
                } else {
                    hud(context, R.string.home_rail_dim, dimOn);
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
            case DRIVE_UPLOAD:
                toggleDriveUpload(context);
                return;
            case HOLD_SPEAK:
                // Only a press with no key-up reaches here (a sticker): holding is impossible,
                // so it behaves like click-to-speak. Held keys are handled in ShortcutKeys.
            case TOGGLE_SPEAK:
                MegaphoneService.toggle(context);
                return;
            case PHOTO:
                photo(context, null);
                return;
            case PHOTO_SURROUND:
                photo(context, com.kooo.evcam.camera.MultiCameraManager.PHOTO_SURROUND);
                return;
            case PHOTO_CABIN:
                photo(context, com.kooo.evcam.camera.MultiCameraManager.PHOTO_CABIN);
                return;
            case AIRPLAY:
                toggleAirPlay(context);
                return;
            case EQ:
                toggleEq(context);
                return;
            case WHERE_PARKED:
                com.kooo.evcam.parking.WhereParked.get().captureNow(context);
                return;
            case OUTSIDE_SPEAKER:
                com.kooo.evcam.audio.OutsideSpeaker.toggle(context);
                return;
            case MOOD_LIGHT:
                com.kooo.evcam.light.GearLight.toggle(context);
                return;
            case FOG_LAMP:
                com.kooo.evcam.light.FogLamp.toggle(context);
                return;
            case VOLUME_UP:
                com.kooo.evcam.audio.CarVolume.step(context, 1);
                return;
            case VOLUME_DOWN:
                com.kooo.evcam.audio.CarVolume.step(context, -1);
                return;
            case ANNOUNCE_PLAY:
                com.kooo.evcam.announce.Announcement chosen = new com.kooo.evcam.announce.AnnouncementStore(context)
                        .find(ShortcutAction.choice(actionKey));
                if (chosen == null) {
                    com.kooo.evcam.ui.Notice.makeText(context, R.string.announce_missing,
                            android.widget.Toast.LENGTH_SHORT).show();
                } else {
                    com.kooo.evcam.announce.Announcer.play(context, chosen, "shortcut");
                }
                return;
            case ANNOUNCE:
                com.kooo.evcam.announce.AnnouncePicker.toggle(context);
                return;
            case QUIT:
                quit(context);
                return;
            case CABIN_PASSENGER:
                com.kooo.evcam.zeekr.CabinLog.line(context, "shortcut: cabin passenger");
                AppConfig cabin = new AppConfig(context);
                if (!OverlayCoordinator.setCabinPassengerEnabled(context, !cabin.isCabinPassengerEnabled())) {
                    toast(context);
                }
                return;
            default:
                return;
        }
    }

    /**
     * Flip upload-moment-to-Drive. Signed out (no client, or no account) shows an error
     * and leaves the switch where it is.
     */
    private static void toggleDriveUpload(Context context) {
        AppConfig config = new AppConfig(context);
        if (!config.hasDriveClient() || !config.hasDriveRefreshToken()) {
            com.kooo.evcam.ui.Notice.makeText(context, R.string.shortcut_drive_not_connected,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        boolean on = !config.isSaveMomentDrive();
        config.setSaveMomentDrive(on);
        com.kooo.evcam.blackbox.BlackBox.noteImportant(
                "开关变更: pref_save_moment_drive → " + (on ? "开" : "关"));
        com.kooo.evcam.ui.Notice.makeText(context,
                on ? R.string.shortcut_drive_upload_on : R.string.shortcut_drive_upload_off,
                Toast.LENGTH_SHORT).show();
    }

    /** Open the super mirror on the HUD; if it is already there, close it. */
    private static void toggleMirrorOnHud(Context context) {
        if (com.kooo.evcam.zeekr.HudDisplay.find(context) == null) {
            com.kooo.evcam.ui.Notice.makeText(context, R.string.msg_no_hud, Toast.LENGTH_SHORT).show();
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

    /** Same switch as the AirPlay page. The service reports a missing overlay when a picture arrives. */
    private static void toggleAirPlay(Context context) {
        boolean on = !com.kooo.evcam.airplay.AirPlayService.enabled(context);
        com.kooo.evcam.airplay.AirPlayService.setEnabled(context, on);
        com.kooo.evcam.ui.Notice.makeText(context,
                on ? R.string.shortcut_airplay_on : R.string.shortcut_airplay_off,
                Toast.LENGTH_SHORT).show();
    }

    /** Same switch as the EQ page. A refusal leaves the equalizer off. */
    private static void toggleEq(Context context) {
        com.kooo.evcam.audio.CabinEqStore store = new com.kooo.evcam.audio.CabinEqStore(context);
        boolean on = !store.enabled();
        if (!com.kooo.evcam.audio.CabinEqEngine.apply(on, store.currentGains())) {
            com.kooo.evcam.ui.Notice.makeText(context, R.string.eq_refused, Toast.LENGTH_SHORT).show();
            return;
        }
        store.setEnabled(on);
        com.kooo.evcam.ui.Notice.makeText(context,
                on ? R.string.shortcut_eq_on : R.string.shortcut_eq_off,
                Toast.LENGTH_SHORT).show();
    }

    /**
     * Quit through the main screen's own exit, so recording stops and files close the same way.
     * With no main screen alive, open it with {@link MainActivity#EXTRA_EXIT} and it quits itself.
     */
    private static void quit(Context context) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            MainActivity live = MainActivity.getInstance();
            if (live != null && !live.isFinishing()) {
                live.exitApp();
                return;
            }
            Intent exit = new Intent(context, MainActivity.class)
                    .putExtra(MainActivity.EXTRA_EXIT, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(exit);
        });
    }

    /** 拍一张，只拍 {@code only} 认的那几路。null 是全部。结果照常弹提示（开着抬头显示通知时也上抬头显示）。 */
    private static void photo(Context context, java.util.function.Predicate<String> only) {
        com.kooo.evcam.camera.CameraManagerHolder.getInstance().getOrInit(context)
                .takePhoto(new com.kooo.evcam.ui.PhotoFeedback(context), only);
    }

    /** 「调暗：开」这样的一句放到抬头显示上（开着「抬头显示通知」时）。 */
    private static void hud(Context context, int what, boolean on) {
        com.kooo.evcam.hud.HudNotice.post(context, context.getString(what),
                context.getString(on ? R.string.home_state_on : R.string.home_state_off));
    }

    private static void toast(Context context) {
        com.kooo.evcam.ui.Notice.makeText(context, R.string.msg_need_overlay, Toast.LENGTH_SHORT).show();
    }
}
