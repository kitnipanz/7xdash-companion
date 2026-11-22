package com.kooo.evcam.overlay;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.WakeUpHelper;
import com.kooo.evcam.zeekr.BothMirrorsService;
import com.kooo.evcam.zeekr.RearViewMirrorService;
import com.kooo.evcam.zeekr.SideViewPopupService;

/**
 * 悬浮窗（超级后视镜、Dash panel 等）「该不该开、能不能开、什么时候开」。
 *
 * <h3>为什么要有这么个地方</h3>
 *
 * <p>同一个问题原先在三处各答一遍：主界面启动时、设置页开关时、前台服务在开机自启时。
 * 三份答案已经不一致了 —— 最明显的一处是<b>超级后视镜的开关没查悬浮窗权限</b>：
 * 没授权时开关拨上去、提示语还告诉你手势怎么用，屏幕上什么都没有，
 * 服务在 {@code onStartCommand} 里 {@code stopSelf()} 走了，只往 logcat 写了一行。
 * 画面悬浮窗和录制悬浮按钮的开关都查了，唯独它漏了。</p>
 *
 * <p>这类「设置里显示开着、实际没开」的毛病，根子是同一个判断被抄了好几份。
 * 抄的时候都对，改的时候只改一处。所以这里只留一份。</p>
 */
public final class OverlayCoordinator {

    private static final String TAG = "OverlayCoordinator";

    /** 后视镜要绑相机，相机这会儿还在开，等一下再拉。 */
    private static final long REAR_VIEW_DELAY_MS = 2000;

    private OverlayCoordinator() {
    }

    // ------------------------------------------------------------------ 判断

    /** Overlay permission or the accessibility service. */
    public static boolean canShowOverlay(Context context) {
        return WakeUpHelper.hasOverlayPermission(context);
    }

    /**
     * A window aimed at the HUD. The HUD is a presentation screen, so the overlay permission
     * is not required. A centre-screen window still needs overlay or accessibility.
     */
    private static boolean canPlace(Context context, boolean onHud) {
        if (onHud && OverlayAccess.canDrawOnHud(context)) {
            return true;
        }
        return canShowFloating(context);
    }

    /** Blind spot: HUD, centre screen, or both. Starts when at least one of those can draw. */
    private static boolean canPlaceSide(Context context) {
        AppConfig config = new AppConfig(context);
        boolean hud = config.isSidePopupOnHud();
        boolean screen = config.isSidePopupOnScreen();
        if (hud && !screen) {
            return OverlayAccess.canDrawOnHud(context);
        }
        if (screen && !hud) {
            return canShowFloating(context);
        }
        return (hud && OverlayAccess.canDrawOnHud(context)) || canShowFloating(context);
    }

    /**
     * Mirrors, blind spot, cabin and gauges on the centre screen: the overlay permission or the
     * accessibility service.
     */
    public static boolean canShowFloating(Context context) {
        return OverlayAccess.canDraw(context);
    }


    // ------------------------------------------------------------------ 启动时恢复

    /**
     * 按设置把该开的悬浮窗都开起来。
     *
     * <p>Dash panel 的录制状态不从这里推：它每半秒自己问 {@code RecordingCoordinator}。</p>
     */
    public static void restoreOnLaunch(Context context) {
        AppConfig config = new AppConfig(context);
        boolean floating = canShowFloating(context);
        com.kooo.evcam.input.AccessibilityGate.ensureForShortcuts(context);
        // The HUD status card: no overlay needed, the HUD is a presentation screen
        com.kooo.evcam.hud.HudStatusWindow.sync(context);

        if (config.isRearViewEnabled() && canPlace(context, config.isRearViewOnHud())) {
            // 这一段以前没有：开关存着「开」，但没人在启动时把服务拉起来，
            // 于是每次重开应用都要去设置里关一次再开一次它才出现。
            main().postDelayed(() -> {
                RearViewMirrorService.start(context);
                AppLog.d(TAG, "超级后视镜已按设置自动开启");
            }, REAR_VIEW_DELAY_MS);
        }

        if (config.isDimOverlayEnabled() && floating) {
            DimOverlayService.show(context);
            AppLog.d(TAG, "屏幕遮罩已按设置打开");
        }

        if (config.isBothMirrorsEnabled() && canPlace(context, config.isBothMirrorsOnHud())) {
            main().postDelayed(() -> {
                BothMirrorsService.start(context);
                AppLog.d(TAG, "左右侧视已按设置自动开启");
            }, REAR_VIEW_DELAY_MS);
        }

        if (config.isSidePopupEnabled() && canPlaceSide(context)) {
            // 和后视镜一样等相机开起来；它平时只听信号，弹的时候才接相机
            main().postDelayed(() -> {
                SideViewPopupService.start(context);
                AppLog.d(TAG, "打转向灯弹侧视已按设置开启");
            }, REAR_VIEW_DELAY_MS);
        }

        if (config.isCabinPassengerEnabled() && floating) {
            main().postDelayed(() -> {
                com.kooo.evcam.zeekr.CabinPassengerService.start(context);
                AppLog.d(TAG, "座舱乘客窗口已按设置自动开启");
            }, REAR_VIEW_DELAY_MS);
        }

        if (config.isRecordingFloatingEnabled()) {
            // 1.9: the floating button is gone, its buttons are on the Dash panel. Whoever had the
            // floating button on gets the Dash panel instead, once.
            config.setRecordingFloatingEnabled(false);
            config.setDashPanelEnabled(true);
            AppLog.i(TAG, "Floating button setting moved to the Dash panel");
        }

        if (config.isDashPanelEnabled() && floating) {
            DashPanelService.show(context);
            AppLog.d(TAG, "Dash panel shown from settings");
        }

        if (config.isDashGaugesEnabled() && !config.isDashPanelEnabled() && floating) {
            GaugesService.show(context);
            AppLog.d(TAG, "Gauges shown on their own from settings");
        }

        if (config.isMacroPadEnabled() && floating) {
            MacroPadService.show(context);
            AppLog.d(TAG, "Macro pad shown from settings");
        }
    }

    // ------------------------------------------------------------------ 开关

    /**
     * Dash panel on / off. It also carries what the floating button had (record, dim, quit).
     *
     * @return 是否真的按要求生效；没有悬浮窗权限时返回 {@code false}，
     *         调用方应当把开关保持在原位而不是拨上去
     */
    public static boolean setDashPanelEnabled(Context context, boolean enabled) {
        if (enabled && !canShowFloating(context)) {
            return false;
        }
        new AppConfig(context).setDashPanelEnabled(enabled);
        if (enabled) {
            DashPanelService.show(context);
        } else {
            DashPanelService.hide(context);
        }
        syncGauges(context);
        return true;
    }

    /** Gauges on with the Dash panel off: they float on their own; otherwise that window goes. */
    private static void syncGauges(Context context) {
        AppConfig config = new AppConfig(context);
        if (config.isDashGaugesEnabled() && !config.isDashPanelEnabled()) {
            GaugesService.show(context);
        } else {
            GaugesService.hide(context);
        }
    }

    /**
     * What the Macro pad docks under: the Dash panel (with its gauges), else the gauges on their
     * own, else nothing. Main thread.
     */
    static int[] dockTarget() {
        int[] panel = DashPanelService.windowBounds();
        return panel != null ? panel : GaugesService.windowBounds();
    }

    /**
     * Gauges on / off: under the Dash panel when it is on, on their own when it is off. Same
     * return value as {@link #setDashPanelEnabled}.
     */
    public static boolean setDashGaugesEnabled(Context context, boolean enabled) {
        if (enabled && !canShowFloating(context)) {
            return false;
        }
        new AppConfig(context).setDashGaugesEnabled(enabled);
        DashPanelService.gaugesChanged();
        syncGauges(context);
        return true;
    }

    /** Macro pad on / off. Same return value as {@link #setDashPanelEnabled}. */
    public static boolean setMacroPadEnabled(Context context, boolean enabled) {
        if (enabled && !canShowFloating(context)) {
            return false;
        }
        new AppConfig(context).setMacroPadEnabled(enabled);
        if (enabled) {
            MacroPadService.show(context);
        } else {
            MacroPadService.hide(context);
        }
        return true;
    }

    /**
     * The Dash panel moved, changed size, was minimized or expanded, opened or closed. The Macro pad
     * hangs under it while both are up, and floats on its own again once the panel is gone.
     * Main thread.
     */
    static void dashPanelMoved() {
        MacroPadService.followDashPanel();
    }

        /** 开 / 关全屏遮罩。返回值含义同 {@link #setDashPanelEnabled}。Dash panel 的月亮自己每半秒读一次。 */
    public static boolean setDimOverlayEnabled(Context context, boolean enabled) {
        if (enabled && !canShowFloating(context)) {
            return false;
        }
        AppConfig config = new AppConfig(context);
        config.setDimOverlayEnabled(enabled);
        if (enabled) {
            DimOverlayService.show(context);
        } else {
            DimOverlayService.hide(context);
        }
        return true;
    }

    /**
     * 开 / 关超级后视镜。返回值含义同 {@link #setDashPanelEnabled}。
     *
     * <p>这里的权限检查是补上的 —— 原来没有，没授权时开关会拨上去而窗口不出现。</p>
     */
    public static boolean setRearViewEnabled(Context context, boolean enabled) {
        if (enabled && !canPlace(context, new AppConfig(context).isRearViewOnHud())) {
            return false;
        }
        new AppConfig(context).setRearViewEnabled(enabled);
        if (enabled) {
            if (new AppConfig(context).isBothMirrorsEnabled()) {
                setBothMirrorsEnabled(context, false);
            }
            RearViewMirrorService.start(context);
        } else {
            RearViewMirrorService.stop(context);
        }
        return true;
    }

    /**
     * 开 / 关左右侧视。返回值含义同 {@link #setDashPanelEnabled}。
     *
     * <p>和超级后视镜共用一路相机输出，开这边就关掉后视镜。</p>
     */
    public static boolean setBothMirrorsEnabled(Context context, boolean enabled) {
        if (enabled && !canPlace(context, new AppConfig(context).isBothMirrorsOnHud())) {
            return false;
        }
        new AppConfig(context).setBothMirrorsEnabled(enabled);
        if (enabled) {
            if (new AppConfig(context).isRearViewEnabled()) {
                setRearViewEnabled(context, false);
            }
            BothMirrorsService.start(context);
        } else {
            BothMirrorsService.stop(context);
        }
        SideViewPopupService.refresh();
        return true;
    }

    /**
     * 开 / 关座舱乘客悬浮窗。返回值含义同 {@link #setDashPanelEnabled}。
     *
     * <p>画面是前座舱，窗口和超级后视镜同一类：可拖、可改大小。用的是前座舱自己的附加输出。</p>
     */
    public static boolean setCabinPassengerEnabled(Context context, boolean enabled) {
        com.kooo.evcam.zeekr.CabinLog.line(context, "---- cabin " + (enabled ? "ON" : "OFF")
                + " requested, saved=" + new AppConfig(context).isCabinPassengerEnabled()
                + " running=" + com.kooo.evcam.zeekr.CabinPassengerService.isRunning()
                + " overlay=" + canShowOverlay(context));
        if (enabled && !canShowFloating(context)) {
            return false;
        }
        new AppConfig(context).setCabinPassengerEnabled(enabled);
        if (enabled) {
            com.kooo.evcam.zeekr.CabinPassengerService.start(context);
        } else {
            com.kooo.evcam.zeekr.CabinPassengerService.stop(context);
        }
        return true;
    }

    /** 开 / 关打转向灯弹侧视。返回值含义同 {@link #setDashPanelEnabled}。 */
    public static boolean setSidePopupEnabled(Context context, boolean enabled) {
        if (enabled && !canPlaceSide(context)) {
            return false;
        }
        new AppConfig(context).setSidePopupEnabled(enabled);
        if (enabled) {
            SideViewPopupService.start(context);
        } else {
            SideViewPopupService.stop(context);
        }
        return true;
    }

    /**
     * 主界面销毁时的清理。
     *
     * <p>什么都不用停：后视镜和 Dash panel 是<b>脱离主界面用的</b>，
     * 主界面没了它们还该在 —— 那本来就是它们存在的理由。</p>
     */
    public static void onActivityDestroyed(Context context) {
        // 有意留空
    }

    // ------------------------------------------------------------------

    private static Handler main() {
        return new Handler(Looper.getMainLooper());
    }
}
