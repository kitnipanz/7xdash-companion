package com.kooo.evcam.zeekr;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;
import android.view.Display;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.CameraForegroundService;
import com.kooo.evcam.WakeUpHelper;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.camera.CameraNeeds;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.SingleCamera;
import com.kooo.evcam.screen.ScreenState;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;
import com.kooo.evcam.telemetry.VehicleState;

/**
 * 打转向灯弹侧视：听车辆信号，左灯弹左侧那一路、右灯弹右侧那一路，灯灭就收（可设再留几秒）；
 * 原厂画面优先。弹不弹的规则在 {@link SideViewDecision}。
 *
 * <h3>相机</h3>
 *
 * <p>和超级后视镜走同一条路（见 {@link RearViewMirrorService}）：窗口的画布用
 * {@code SingleCamera.declareMirror()} 声明成环视的附加输出，再登记
 * {@link CameraNeeds.Holder#SIDE_POPUP}。两者共用这一个槽位，
 * 所以<b>超级后视镜开着时不弹</b>。窗口拿掉就注销。</p>
 *
 * <h3>即时弹出</h3>
 *
 * <p>每次从无到有挂窗口都要重建一次会话，画面比窗口晚到零点几秒（相机没开时更久）。
 * 「即时弹出」开着时，D 档下窗口一直挂着但完全透明，相机照推（见 {@link SideViewPopupView} 的三种状态），
 * 打灯只是把它拨亮。离开 D 档 {@link #READY_GRACE_MS} 后才拿掉，等红灯挂 N 不至于来回重建。</p>
 *
 * <h3>车辆信号</h3>
 *
 * <p>亮屏时向 {@link Telemetry} 登记，熄屏就注销、窗口拿掉：熄屏后车机六秒就深睡，不该留着监听和相机过去。</p>
 *
 * <h3>抬头显示</h3>
 *
 * <p>中控和抬头显示各有一个开关。只开抬头显示、并且找得到那块屏时，窗口挂到那块屏上，只占一小块
 * （{@link HudFrame}）；找不到就仍在中控。两个都开时中控一个窗口、抬头显示再一个（{@link #hudCopy}），
 * 后者用相机的第二块后视镜画布（{@code SingleCamera.declareExtraMirror}）—— 和第一块挂在同一条共享流上，
 * 不多占硬件流。</p>
 */
public class SideViewPopupService extends Service {

    private static final String TAG = "SideViewPopupSvc";
    private static final String TELEMETRY_USER = "side-popup";

    private static final long RETRY_DELAY_MS = 500L;
    private static final int MAX_RETRY = 20;
    /** 离开 D 档多久才把备着的窗口拿掉。 */
    static final long READY_GRACE_MS = 30_000L;

    private static volatile SideViewPopupService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AppConfig appConfig;
    private SideViewPopupView popup;
    /** 中控、抬头显示两个都开时，抬头显示上那一块。主窗口 {@link #popup} 这时在中控。 */
    private SideViewPopupView hudCopy;
    private SingleCamera copyCamera;
    private SurfaceTexture copyTexture;
    private int copyRetries;
    private SingleCamera boundCamera;
    /** 交给相机的是哪一个 SurfaceTexture：放手时确认槽位还是自己的。 */
    private SurfaceTexture boundTexture;
    private boolean listening;
    private int retryCount;
    private Runnable retryRunnable;
    /** 上一次判定时该不该备着。 */
    private boolean lastReady;
    /** 最近显示过哪一边：备着时窗口停在那一边，下次多半还是它。 */
    private int lastLane = LaneCycle.LEFT;
    /** 这一次显示的时刻和第一帧来没来：黑匣子里看「弹了多久才有画面」。 */
    private long shownAtMs;
    private boolean firstFrameSeen;

    /** 灯灭后再留几秒：到点收起。 */
    private final Runnable delayedClose = () -> {
        AppLog.i(TAG, "侧视收起：灯灭后多留的时间到了");
        closeNow();
    };
    private boolean closePending;
    /** 离开 D 档宽限期满：拿掉备着的窗口。 */
    private final Runnable readyExpired = () -> {
        if (!lastReady && popup != null && !popup.isShowing()) {
            AppLog.i(TAG, "侧视弹窗：离开 D 档，不再备着");
            detachPopup();
        }
    };

    public static void start(Context context) {
        context.startService(new Intent(context, SideViewPopupService.class));
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, SideViewPopupService.class));
    }

    /** 设置页改了侧视的任何一项：窗口套用新的大小位置拉直，再按新规则判一次。 */
    public static void applyConfig() {
        SideViewPopupService svc = instance;
        if (svc != null) {
            Display hud = svc.hudDisplay();
            boolean moved = svc.popup != null && (svc.popup.onHud() != svc.primaryOnHud(hud)
                    || (svc.hudCopy != null) != svc.wantsCopy(hud));
            if (moved) {
                svc.detachPopup();
            } else {
                if (svc.popup != null) {
                    svc.popup.applyConfig();
                }
                if (svc.hudCopy != null) {
                    svc.hudCopy.applyConfig();
                }
            }
            svc.evaluate();
        }
        BothMirrorsService.applyConfig();
    }

    /** 左右侧视或超级后视镜抢走了相机槽位：按新情况再判一次。 */
    public static void refresh() {
        SideViewPopupService svc = instance;
        if (svc != null) {
            svc.handler.post(svc::evaluate);
        }
    }

    /**
     * 遮罩窗口后加，会盖住侧视。同类悬浮窗按添加先后叠，只能摘下重挂；
     * 重挂会重新接相机，所以只在遮罩刚打开时做一次。
     * 遮罩先开、转向灯后弹的窗口在显示时再摘下重挂。
     */
    public static void raiseAboveShade() {
        SideViewPopupService svc = instance;
        if (svc != null) {
            svc.handler.post(svc::reattachOnTop);
        }
    }

    private void reattachOnTop() {
        if (popup == null || !popup.isAttached()) {
            return;
        }
        AppLog.i(TAG, "侧视弹窗：遮罩打开，重挂到遮罩上面");
        detachPopup();
        evaluate();
    }

    private final Telemetry.Listener readingsListener = readings -> evaluate();

    private final ScreenState.Listener screenListener = new ScreenState.Listener() {
        @Override
        public void onScreenOff() {
            stopListening();
        }

        @Override
        public void onScreenOn() {
            startListening();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        appConfig = new AppConfig(this);
        instance = this;
        ScreenState.addListener(screenListener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (com.kooo.evcam.UserExit.blocks(this, "SideViewPopupService")) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!appConfig.isSidePopupEnabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!WakeUpHelper.hasOverlayPermission(this)) {
            AppLog.e(TAG, "没有悬浮窗权限，侧视弹窗无法显示");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ScreenState.dark()) {
            startListening();
        }
        return CameraForegroundService.stickiness(this);
    }

    // ================================================================= 信号

    private void startListening() {
        if (listening) {
            return;
        }
        listening = true;
        Telemetry.get().acquire(this, TELEMETRY_USER);
        Telemetry.get().addListener(readingsListener);
        AppLog.i(TAG, "侧视弹窗：开始听转向灯");
        evaluate();
    }

    private void stopListening() {
        if (!listening) {
            return;
        }
        listening = false;
        Telemetry.get().removeListener(readingsListener);
        Telemetry.get().release(TELEMETRY_USER);
        lastReady = false;
        detachPopup();
        AppLog.i(TAG, "侧视弹窗：停止听转向灯");
    }

    /** 主线程上：按最新的读数决定弹 / 收 / 换边 / 备着。 */
    private void evaluate() {
        if (!listening) {
            return;
        }
        Readings r = Telemetry.get().readings();
        VehicleState s = Telemetry.get().latest();
        SideViewDecision.Input in = new SideViewDecision.Input();
        in.turnSignal = s.turnSignal;
        in.hazard = s.hazard;
        in.gear = r.text(Signal.GEAR);
        in.stock360Shown = r.bool(Signal.STOCK_360);
        in.stockPopupShown = r.bool(Signal.STOCK_POPUP);
        in.parkAssistOn = r.bool(Signal.PARK_ASSIST);
        in.speedKmh = s.speedKmh;
        in.minSpeedKmh = appConfig.getSidePopupMinSpeed();
        in.overFactoryView = appConfig.isSidePopupOverFactory();
        in.doorCamera = appConfig.isSidePopupOnDoor();
        in.doorsOpen = s.doorsOpen;
        in.showing = popup != null && popup.isShowing() ? popup.lane() : SideViewDecision.NONE;

        boolean blocked = ScreenState.dark() || RearViewMirrorService.isRunning()
                || BothMirrorsService.isRunning()
                || (!appConfig.isSidePopupOnScreen() && !appConfig.isSidePopupOnHud());
        int want = blocked ? SideViewDecision.NONE : SideViewDecision.decide(in);
        showWarning(s.blindSpotWarning);
        lastReady = !blocked && appConfig.isSidePopupInstant() && SideViewDecision.shouldStayReady(in);

        if (want != SideViewDecision.NONE) {
            cancelDelayedClose();
            handler.removeCallbacks(readyExpired);
            if (want != in.showing) {
                boolean byDoor = want == SideViewDecision.doorLane(in)
                        && (in.turnSignal == null || in.turnSignal == 0 || Boolean.TRUE.equals(in.hazard));
                AppLog.i(TAG, (byDoor ? "开门：弹出「" : "转向灯：弹出「") + LaneCycle.labelOf(want) + "」路");
                showPopup(want);
                showWarning(s.blindSpotWarning);
            }
            return;
        }

        if (in.showing != SideViewDecision.NONE) {
            boolean factory = SideViewDecision.factoryViewActive(in);
            int delayMs = appConfig.getSidePopupCloseDelayMs();
            if (factory || blocked || delayMs <= 0) {
                AppLog.i(TAG, "侧视收起：" + (factory ? "原厂画面在" : blocked ? "超级后视镜开着或熄屏" : "转向灯灭"));
                closeNow();
            } else if (!closePending) {
                closePending = true;
                handler.postDelayed(delayedClose, delayMs);
            }
            return;
        }

        // 没在显示：管「备着」
        if (lastReady) {
            handler.removeCallbacks(readyExpired);
            if (popup == null || !popup.isAttached()) {
                AppLog.i(TAG, "侧视弹窗：D 档，窗口备着");
                attachPopup(lastLane, false);
            }
        } else if (popup != null && popup.isAttached() && !closePending) {
            handler.removeCallbacks(readyExpired);
            handler.postDelayed(readyExpired, blocked ? 0 : READY_GRACE_MS);
        }
    }

    /** 盲区报警的那一侧正好是弹着的那一路：红边。中控、抬头显示两块一起。 */
    private void showWarning(Integer sides) {
        for (SideViewPopupView view : new SideViewPopupView[]{popup, hudCopy}) {
            if (view != null) {
                int bit = view.lane() == LaneCycle.LEFT ? VehicleState.TURN_LEFT : VehicleState.TURN_RIGHT;
                view.setWarning(sides != null && (sides & bit) != 0);
            }
        }
    }

    // ================================================================= 窗口

    private void showPopup(int lane) {
        lastLane = lane;
        shownAtMs = SystemClock.uptimeMillis();
        firstFrameSeen = false;
        attachPopup(lane, true);
    }

    /** 收起：该备着就只拨暗（相机照推），不该就整个拿掉。 */
    private void closeNow() {
        cancelDelayedClose();
        if (popup == null || !popup.isShowing()) {
            return;
        }
        noteIfNoFrame();
        if (lastReady) {
            popup.conceal();
            if (hudCopy != null) {
                hudCopy.conceal();
            }
        } else {
            detachPopup();
        }
    }

    private void cancelDelayedClose() {
        if (closePending) {
            closePending = false;
            handler.removeCallbacks(delayedClose);
        }
    }

    /** 主窗口挂在抬头显示上：只开了抬头显示，而且找得到那块屏。 */
    boolean primaryOnHud(Display hud) {
        return hud != null && !appConfig.isSidePopupOnScreen();
    }

    /** 中控之外再在抬头显示上弹一块：两个都开，而且找得到那块屏。 */
    boolean wantsCopy(Display hud) {
        return hud != null && appConfig.isSidePopupOnScreen();
    }

    /** 抬头显示开着且找得到那块屏时返回它，否则 null。 */
    private Display hudDisplay() {
        if (!appConfig.isSidePopupOnHud() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null;
        }
        return HudDisplay.find(this);
    }

    private Context popupContext(Display hud) {
        if (hud == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return this;
        }
        try {
            return createWindowContext(hud, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        } catch (RuntimeException e) {
            AppLog.w(TAG, "抬头显示窗口建不了: " + e.getMessage());
            return this;
        }
    }

    private void attachPopup(int lane, boolean show) {
        Display hud = hudDisplay();
        Display target = primaryOnHud(hud) ? hud : null;
        if (popup != null && popup.isAttached() && popup.onHud() != (target != null)) {
            detachPopup();
        }
        if (popup != null && popup.isAttached()) {
            if (show) {
                popup.show(lane);
            } else {
                popup.attachHidden(lane);
            }
            syncCopy(lane, show, hud);
            return;
        }
        Context host = popupContext(target);
        boolean onHud = target != null && host != this;
        popup = new SideViewPopupView(host, appConfig);
        if (onHud) {
            Point size = HudDisplay.sizeOf(target);
            popup.setHudSize(size.x, size.y);
            AppLog.i(TAG, "侧视弹窗：投到抬头显示 " + size.x + "×" + size.y);
        }
        popup.getTextureView().setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                bindCamera(st);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                restoreBufferSize(st);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                unbindCamera();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
                onFrame();
            }
        });
        if (show) {
            popup.show(lane);
        } else {
            popup.attachHidden(lane);
        }
        syncCopy(lane, show, hud);
    }

    /** 两块屏都开时，抬头显示上那一块跟着主窗口：同一边、同样显示或备着。不该有就拿掉。 */
    private void syncCopy(int lane, boolean show, Display hud) {
        if (!wantsCopy(hud) || popup == null || !popup.isAttached()) {
            detachCopy();
            return;
        }
        if (hudCopy != null && hudCopy.isAttached()) {
            if (show) {
                hudCopy.show(lane);
            } else {
                hudCopy.attachHidden(lane);
            }
            return;
        }
        Context host = popupContext(hud);
        if (host == this) {
            return;
        }
        hudCopy = new SideViewPopupView(host, appConfig);
        Point size = HudDisplay.sizeOf(hud);
        hudCopy.setHudSize(size.x, size.y);
        AppLog.i(TAG, "侧视弹窗：中控之外也投到抬头显示 " + size.x + "×" + size.y);
        hudCopy.getTextureView().setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                copyRetries = 0;
                bindCopy(st);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                restoreBufferSize(copyCamera, st);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                unbindCopy();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
                SideViewPopupView view = hudCopy;
                if (view != null && !view.hasGeometry() && copyCamera != null) {
                    view.setSourceSize(copyCamera.getPreviewSize());
                }
            }
        });
        if (show) {
            hudCopy.show(lane);
        } else {
            hudCopy.attachHidden(lane);
        }
    }

    private void detachCopy() {
        unbindCopy();
        if (hudCopy != null) {
            hudCopy.detach();
            hudCopy = null;
        }
    }

    private void detachPopup() {
        cancelDelayedClose();
        handler.removeCallbacks(readyExpired);
        cancelRetry();
        if (popup != null && popup.isShowing()) {
            noteIfNoFrame();
        }
        detachCopy();
        unbindCamera();
        if (popup != null) {
            popup.detach();
            popup = null;
        }
    }

    /** 显示过、到收起时一帧都没来：记下来，诊断报告里看得到。 */
    private void noteIfNoFrame() {
        if (firstFrameSeen) {
            return;
        }
        SingleCamera camera = boundCamera;
        BlackBox.note("侧视弹窗收起：显示了 " + (SystemClock.uptimeMillis() - shownAtMs) + "ms 一帧都没来；"
                + (camera == null ? "没接上相机" : "相机" + (camera.isCameraOpened() ? "开着" : "没开")
                + "，最近错误 " + camera.lastErrorName()));
    }

    /**
     * 每来一帧响一次。挂窗口时相机要是还没开过，那一刻它的预览尺寸是 null（开相机时才选），
     * 窗口就不知道该裁哪一块；帧来了说明相机已经开好，这时补上。
     */
    private void onFrame() {
        SideViewPopupView view = popup;
        SingleCamera camera = boundCamera;
        if (view == null) {
            return;
        }
        if (!view.hasGeometry() && camera != null) {
            view.setSourceSize(camera.getPreviewSize());
            if (view.hasGeometry()) {
                AppLog.i(TAG, "侧视弹窗：相机开好后补上了预览尺寸 " + camera.getPreviewSize());
            }
        }
        if (view.isShowing() && !firstFrameSeen) {
            firstFrameSeen = true;
            BlackBox.note("侧视弹窗出画面：打灯后 " + (SystemClock.uptimeMillis() - shownAtMs) + "ms，几何"
                    + (view.hasGeometry() ? "已知" : "未知"));
        }
    }

    // ================================================================= 相机

    private void bindCamera(SurfaceTexture surfaceTexture) {
        if (popup == null || !popup.isAttached() || surfaceTexture == null || ScreenState.dark()) {
            return;
        }
        MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
        if (manager == null) {
            manager = CameraManagerHolder.getInstance().getOrInit(this);
        }
        SingleCamera camera = manager != null ? manager.getCamera("front") : null;
        if (camera == null) {
            scheduleRetry(surfaceTexture);
            return;
        }
        Size previewSize = camera.getPreviewSize();
        if (previewSize != null) {
            popup.setSourceSize(previewSize);
        }
        if (camera != boundCamera || surfaceTexture != boundTexture) {
            withdrawOutput();
            boundCamera = camera;
            boundTexture = surfaceTexture;
            restoreBufferSize(surfaceTexture);
        }
        boolean declared = camera.declareMirror(surfaceTexture);
        CameraNeeds.current().claim(CameraNeeds.Holder.SIDE_POPUP);
        if (declared) {
            manager.channelScheduler().wake();
        }
        retryCount = 0;
        boolean wasOpen = camera.isCameraOpened();
        BlackBox.note("侧视弹窗接相机：「" + LaneCycle.labelOf(popup.lane()) + "」路，"
                + (popup.isShowing() ? "显示中" : "备着") + "，相机" + (wasOpen ? "已开" : "未开，现在开")
                + "，预览尺寸 " + previewSize + "，几何" + (popup.hasGeometry() ? "已知" : "未知（等第一帧补）"));
    }

    /** 窗口尺寸一变 TextureView 会改缓冲区尺寸，拨回会话用的那个（同后视镜）。 */
    private void restoreBufferSize(SurfaceTexture surfaceTexture) {
        restoreBufferSize(boundCamera, surfaceTexture);
    }

    private static void restoreBufferSize(SingleCamera camera, SurfaceTexture surfaceTexture) {
        if (surfaceTexture == null || camera == null) {
            return;
        }
        Size buffer = camera.getPreviewBufferSize();
        if (buffer != null && buffer.getWidth() > 0 && buffer.getHeight() > 0) {
            surfaceTexture.setDefaultBufferSize(buffer.getWidth(), buffer.getHeight());
        }
    }

    /** 只撤回自己那块画布。后视镜后来声明过的话，这里对不上，不动它。 */
    private void withdrawOutput() {
        if (boundCamera != null && boundTexture != null) {
            boundCamera.withdrawMirror(boundTexture);
        }
    }

    private void unbindCamera() {
        cancelRetry();
        CameraNeeds.current().release(CameraNeeds.Holder.SIDE_POPUP);
        withdrawOutput();
        boundCamera = null;
        boundTexture = null;
    }

    /** 抬头显示上那一块接相机：第二块后视镜画布，挂在主窗口同一条共享流上。占用登记由主窗口管。 */
    private void bindCopy(SurfaceTexture surfaceTexture) {
        if (hudCopy == null || !hudCopy.isAttached() || surfaceTexture == null || ScreenState.dark()) {
            return;
        }
        MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
        SingleCamera camera = manager != null ? manager.getCamera("front") : null;
        if (camera == null) {
            if (copyRetries++ < MAX_RETRY) {
                handler.postDelayed(() -> bindCopy(surfaceTexture), RETRY_DELAY_MS);
            }
            return;
        }
        Size previewSize = camera.getPreviewSize();
        if (previewSize != null) {
            hudCopy.setSourceSize(previewSize);
        }
        if (camera != copyCamera || surfaceTexture != copyTexture) {
            unbindCopy();
            copyCamera = camera;
            copyTexture = surfaceTexture;
            restoreBufferSize(camera, surfaceTexture);
        }
        if (camera.declareExtraMirror(surfaceTexture)) {
            manager.channelScheduler().wake();
        }
        BlackBox.note("侧视弹窗：抬头显示那一块接相机，「" + LaneCycle.labelOf(hudCopy.lane()) + "」路");
    }

    private void unbindCopy() {
        if (copyCamera != null && copyTexture != null) {
            copyCamera.withdrawExtraMirror(copyTexture);
        }
        copyCamera = null;
        copyTexture = null;
    }

    private void scheduleRetry(SurfaceTexture surfaceTexture) {
        if (retryCount >= MAX_RETRY) {
            AppLog.w(TAG, "相机始终不可用，侧视放弃绑定");
            return;
        }
        retryCount++;
        cancelRetry();
        retryRunnable = () -> bindCamera(surfaceTexture);
        handler.postDelayed(retryRunnable, RETRY_DELAY_MS);
    }

    private void cancelRetry() {
        if (retryRunnable != null) {
            handler.removeCallbacks(retryRunnable);
            retryRunnable = null;
        }
    }

    @Override
    public void onDestroy() {
        instance = null;
        ScreenState.removeListener(screenListener);
        stopListening();
        detachPopup();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
