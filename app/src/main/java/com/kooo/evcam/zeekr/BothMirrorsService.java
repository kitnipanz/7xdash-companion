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
import android.util.Size;
import android.view.Display;
import android.view.TextureView;
import android.view.WindowManager;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.CameraForegroundService;
import com.kooo.evcam.WakeUpHelper;
import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.camera.CameraNeeds;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.SingleCamera;
import com.kooo.evcam.screen.ScreenState;

/**
 * 快捷键打开的左右侧视。相机绑定和 {@link SideViewPopupService} 同一条路，
 * 所以超级后视镜、打灯侧视不能同时占这个槽位。
 */
public class BothMirrorsService extends Service {

    private static final String TAG = "BothMirrorsSvc";
    private static final long RETRY_DELAY_MS = 500L;
    private static final int MAX_RETRY = 20;

    private static volatile BothMirrorsService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AppConfig appConfig;
    private BothMirrorsView view;
    private SingleCamera boundCamera;
    private SurfaceTexture boundTexture;
    private int retryCount;
    private Runnable retryRunnable;
    private boolean hudFailed;
    private boolean sawHudSetting;

    public static void start(Context context) {
        context.startService(new Intent(context, BothMirrorsService.class));
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, BothMirrorsService.class));
    }

    public static boolean isRunning() {
        return instance != null;
    }

    public static void applyConfig() {
        BothMirrorsService svc = instance;
        if (svc == null) {
            return;
        }
        svc.handler.post(() -> {
            if (svc.view != null && svc.view.onHud() != svc.wantsHud()) {
                svc.reattach();
                return;
            }
            if (svc.view != null) {
                svc.view.applyConfig();
            }
        });
    }

    /** 遮罩后加会盖住窗口。摘下重挂，让左右侧视回到遮罩上面。 */
    public static void raiseAboveShade() {
        BothMirrorsService svc = instance;
        if (svc != null) {
            svc.handler.post(svc::reattach);
        }
    }

    private final ScreenState.Listener screenListener = new ScreenState.Listener() {
        @Override
        public void onScreenOff() {
            detach();
        }

        @Override
        public void onScreenOn() {
            show();
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
        if (com.kooo.evcam.UserExit.blocks(this, "BothMirrorsService")) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!appConfig.isBothMirrorsEnabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!WakeUpHelper.hasOverlayPermission(this)) {
            AppLog.e(TAG, "没有悬浮窗权限，左右侧视无法显示");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (appConfig.isRearViewEnabled() || RearViewMirrorService.isRunning()) {
            com.kooo.evcam.overlay.OverlayCoordinator.setRearViewEnabled(this, false);
            appConfig = new AppConfig(this);
        }
        if (!ScreenState.dark()) {
            show();
        }
        return CameraForegroundService.stickiness(this);
    }

    /** 设置开着、并且找得到抬头显示时，窗口挂到那块屏。否则留在中控。 */
    private boolean wantsHud() {
        boolean on = appConfig.isBothMirrorsOnHud();
        if (on != sawHudSetting) {
            sawHudSetting = on;
            hudFailed = false;
        }
        return on && !hudFailed
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && HudDisplay.find(this) != null;
    }

    private Context hudHost() {
        if (!wantsHud()) {
            return this;
        }
        Display hud = HudDisplay.find(this);
        if (hud == null) {
            return this;
        }
        try {
            return createWindowContext(hud, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        } catch (RuntimeException e) {
            hudFailed = true;
            AppLog.w(TAG, "抬头显示窗口建不了: " + e.getMessage());
            return this;
        }
    }

    private void show() {
        if (view != null && view.isAttached()) {
            return;
        }
        Context host = hudHost();
        view = new BothMirrorsView(host, appConfig);
        if (host != this) {
            Point size = HudDisplay.sizeOf(HudDisplay.find(this));
            view.setHudSize(size.x, size.y);
            AppLog.i(TAG, "左右侧视：投到抬头显示 " + size.x + "×" + size.y);
        }
        view.getTextureView().setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
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
                BothMirrorsView shown = view;
                SingleCamera camera = boundCamera;
                if (shown != null && !shown.hasGeometry() && camera != null) {
                    shown.setSourceSize(camera.getPreviewSize());
                }
            }
        });
        view.show();
        AppLog.i(TAG, "左右侧视已打开");
    }

    private void reattach() {
        if (view == null || !view.isAttached()) {
            return;
        }
        detach();
        show();
    }

    private void detach() {
        cancelRetry();
        unbindCamera();
        if (view != null) {
            view.detach();
            view = null;
        }
    }

    private void bindCamera(SurfaceTexture surfaceTexture) {
        if (view == null || !view.isAttached() || surfaceTexture == null || ScreenState.dark()) {
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
            view.setSourceSize(previewSize);
        }
        if (camera != boundCamera || surfaceTexture != boundTexture) {
            withdrawOutput();
            boundCamera = camera;
            boundTexture = surfaceTexture;
            restoreBufferSize(surfaceTexture);
        }
        boolean declared = camera.declareMirror(surfaceTexture);
        CameraNeeds.current().claim(CameraNeeds.Holder.BOTH_MIRRORS);
        if (declared) {
            manager.channelScheduler().wake();
        }
        retryCount = 0;
    }

    private void restoreBufferSize(SurfaceTexture surfaceTexture) {
        if (surfaceTexture == null || boundCamera == null) {
            return;
        }
        Size buffer = boundCamera.getPreviewBufferSize();
        if (buffer != null && buffer.getWidth() > 0 && buffer.getHeight() > 0) {
            surfaceTexture.setDefaultBufferSize(buffer.getWidth(), buffer.getHeight());
        }
    }

    private void withdrawOutput() {
        if (boundCamera != null && boundTexture != null) {
            boundCamera.withdrawMirror(boundTexture);
        }
    }

    private void unbindCamera() {
        cancelRetry();
        CameraNeeds.current().release(CameraNeeds.Holder.BOTH_MIRRORS);
        withdrawOutput();
        boundCamera = null;
        boundTexture = null;
    }

    private void scheduleRetry(SurfaceTexture surfaceTexture) {
        if (retryCount >= MAX_RETRY) {
            AppLog.w(TAG, "相机始终不可用，左右侧视放弃绑定");
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
        detach();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
