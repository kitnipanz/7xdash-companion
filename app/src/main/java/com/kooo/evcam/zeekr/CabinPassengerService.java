package com.kooo.evcam.zeekr;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Size;
import android.view.Surface;
import android.widget.Toast;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.CameraForegroundService;
import com.kooo.evcam.R;
import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.camera.CameraNeeds;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.SingleCamera;
import com.kooo.evcam.screen.ScreenState;

/**
 * Window lifecycle for the cabin-passenger view. The front-cabin camera owns its own extra
 * output, so this does not take the slot Super mirror uses.
 */
public class CabinPassengerService extends Service {

    private static final String TAG = "CabinPassengerSvc";
    private static final long RETRY_DELAY_MS = 500L;
    private static final int MAX_RETRY = 20;
    private static final long WATCHDOG_INTERVAL_MS = 2000L;
    /** No new frame for this long while bound: declare again and wake the scheduler. */
    private static final long STALL_MS = 4000L;

    private static volatile CabinPassengerService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AppConfig appConfig;
    private CabinPassengerView window;
    private SingleCamera boundCamera;
    private SurfaceTexture boundTexture;
    private int retryCount;
    /** When this binding started, or the stall check last woke the scheduler. */
    private long lastRebuildMs;
    private Runnable retryRunnable;
    private Runnable watchdog;

    private final ScreenState.Listener screenListener = new ScreenState.Listener() {
        @Override
        public void onScreenOff() {
            log("screen off, recording=" + CameraNeeds.current().isHeld(CameraNeeds.Holder.RECORDING));
            if (CameraNeeds.current().isHeld(CameraNeeds.Holder.RECORDING)) {
                return;
            }
            unbindCamera();
        }

        @Override
        public void onScreenOn() {
            log("screen on");
            rebind();
        }
    };

    public static void start(Context context) {
        CabinLog.line(context, "start() running=" + isRunning() + " " + CabinLog.world());
        try {
            context.startService(new Intent(context, CabinPassengerService.class));
        } catch (RuntimeException e) {
            CabinLog.line(context, "start() refused: " + e);
            throw e;
        }
    }

    public static void stop(Context context) {
        CabinLog.line(context, "stop() running=" + isRunning());
        context.stopService(new Intent(context, CabinPassengerService.class));
    }

    public static boolean isRunning() {
        return instance != null;
    }

    public static void applySize(Context context) {
        CabinPassengerService svc = instance;
        if (svc != null && svc.window != null) {
            svc.window.applySizeFromConfig();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        appConfig = new AppConfig(this);
        instance = this;
        ScreenState.addListener(screenListener);
        log("service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        log("onStartCommand intent=" + (intent != null) + " window=" + (window != null) + " " + CabinLog.world());
        if (window == null) {
            window = new CabinPassengerView(this, appConfig);
            window.setSurfaceReady(new CabinPassengerView.SurfaceReady() {
                @Override
                public void onSurface(SurfaceTexture surface) {
                    bindCamera(surface);
                }

                @Override
                public void onResized(SurfaceTexture surface) {
                    restoreBufferSize(surface);
                }

                @Override
                public boolean onGone(SurfaceTexture surface) {
                    return handCanvasToPipeline(surface);
                }
            });
            window.show();
            log("window shown attached=" + window.isAttached()
                    + " hwAccel=" + window.getTextureView().isHardwareAccelerated());
            takeBackKeptCanvas();
            com.kooo.evcam.ui.Notice.makeText(this, R.string.msg_cabin_on, Toast.LENGTH_SHORT).show();
        }
        startWatchdog();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        log("service destroyed, frames=" + (window != null ? window.frameCount() : -1));
        cancelWatchdog();
        cancelRetry();
        unbindCamera();
        if (window != null) {
            window.hide();
            window = null;
        }
        ScreenState.removeListener(screenListener);
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void bindCamera(SurfaceTexture surfaceTexture) {
        if (ScreenState.dark() || window == null || surfaceTexture == null) {
            log("bind skipped: dark=" + ScreenState.dark() + " window=" + (window != null)
                    + " canvas=" + CabinLog.id(surfaceTexture));
            return;
        }
        MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
        if (manager == null) {
            manager = CameraManagerHolder.getInstance().getOrInit(this);
        }
        SingleCamera camera = manager != null ? manager.getCamera(CabinWindow.CAMERA_KEY) : null;
        if (camera == null) {
            log("bind: no cabin camera yet (manager=" + (manager != null) + "), retry " + (retryCount + 1));
            scheduleRetry(surfaceTexture);
            return;
        }
        if (camera != boundCamera || surfaceTexture != boundTexture) {
            // A rebuilt pipeline has a new camera object: the old one must not keep this canvas
            withdrawOutput();
            boundCamera = camera;
            boundTexture = surfaceTexture;
            lastRebuildMs = android.os.SystemClock.uptimeMillis();
        }
        Size preview = camera.getPreviewSize();
        if (preview != null) {
            window.setBufferSize(preview.getWidth(), preview.getHeight());
        }
        window.setSystemMirrored(systemFlips(camera));
        restoreBufferSize(surfaceTexture);
        boolean declared = camera.declareMirror(surfaceTexture);
        CameraNeeds.current().claim(CameraNeeds.Holder.CABIN);
        if (declared) {
            manager.channelScheduler().wake();
        }
        retryCount = 0;
        AppLog.i(TAG, "cabin passenger bound, preview " + preview + (declared ? ", declared" : ""));
        log("bound preview=" + preview + " buffer=" + camera.getPreviewBufferSize()
                + " declared=" + declared + " " + CabinLog.world() + " | " + CabinLog.camera(camera, surfaceTexture));
        logLater(surfaceTexture, 1500L);
    }

    /** The view sets its canvas to its layout size on resize. Put back the size the session uses. */
    private void restoreBufferSize(SurfaceTexture surfaceTexture) {
        SingleCamera camera = boundCamera;
        if (surfaceTexture == null || camera == null || window == null) {
            return;
        }
        Size buffer = camera.getPreviewBufferSize();
        if (buffer != null && buffer.getWidth() > 0 && buffer.getHeight() > 0) {
            surfaceTexture.setDefaultBufferSize(buffer.getWidth(), buffer.getHeight());
            window.setBufferSize(buffer.getWidth(), buffer.getHeight());
        }
    }

    /**
     * Opening again soon after closing: take back the canvas the last window left with the
     * pipeline. Same canvas, same output, so the cabin session does not change at all.
     */
    private void takeBackKeptCanvas() {
        if (window == null || !window.isAttached()) {
            return;
        }
        MultiCameraManager pipeline = CameraManagerHolder.getInstance().getCameraManager();
        if (pipeline == null || pipeline.isReleased()) {
            return;
        }
        SurfaceTexture kept = pipeline.takeKeptMirror(CabinWindow.CAMERA_KEY);
        log("take back kept canvas: " + CabinLog.id(kept));
        if (kept == null) {
            return;
        }
        if (!window.adopt(kept)) {
            log("adopt failed, kept canvas handed back");
            if (!MultiCameraManager.keepMirrorCanvas(pipeline, CabinWindow.CAMERA_KEY, kept)) {
                kept.release();
            }
            return;
        }
        AppLog.i(TAG, "cabin passenger took back its kept canvas");
        bindCamera(kept);
    }

    /**
     * The window's canvas is going away (window removed). Hand it to the pipeline instead of
     * letting the view release it: a released canvas still in the session is a dead output, and
     * the cabin lane sends no frames anywhere until the scheduler rebuilds the session.
     *
     * @return true: the pipeline keeps it
     */
    private boolean handCanvasToPipeline(SurfaceTexture surface) {
        cancelRetry();
        if (surface == boundTexture) {
            unbindCamera();
        }
        MultiCameraManager pipeline = CameraManagerHolder.getInstance().getCameraManager();
        boolean kept = MultiCameraManager.keepMirrorCanvas(pipeline, CabinWindow.CAMERA_KEY, surface);
        log("canvas gone " + CabinLog.id(surface) + ", pipeline keeps it=" + kept);
        if (!kept) {
            com.kooo.evcam.camera.ChannelScheduler.wakeAll();
        }
        return kept;
    }

    private boolean systemFlips(SingleCamera camera) {
        try {
            CameraManager cameras = (CameraManager) getSystemService(CAMERA_SERVICE);
            if (cameras == null || camera.getCameraId() == null) {
                return false;
            }
            Integer facing = cameras.getCameraCharacteristics(camera.getCameraId())
                    .get(CameraCharacteristics.LENS_FACING);
            return facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
        } catch (Exception e) {
            return false;
        }
    }

    private void unbindCamera() {
        if (boundCamera != null) {
            log("unbind canvas " + CabinLog.id(boundTexture));
        }
        cancelRetry();
        CameraNeeds.current().release(CameraNeeds.Holder.CABIN);
        withdrawOutput();
        boundCamera = null;
        boundTexture = null;
    }

    /** Withdraw only our canvas. The canvas stays in the session until the scheduler next changes it. */
    private void withdrawOutput() {
        if (boundCamera != null && boundTexture != null && boundCamera.withdrawMirror(boundTexture)) {
            MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
            if (manager != null) {
                manager.channelScheduler().wake();
            }
        }
    }

    private void rebind() {
        if (window == null) {
            return;
        }
        SurfaceTexture texture = window.getTextureView().getSurfaceTexture();
        if (texture == null) {
            log("rebind: window has no canvas yet, available=" + window.getTextureView().isAvailable());
        }
        if (texture != null) {
            bindCamera(texture);
        }
    }

    private void scheduleRetry(SurfaceTexture surfaceTexture) {
        if (retryCount >= MAX_RETRY) {
            AppLog.w(TAG, "cabin camera still missing after " + MAX_RETRY + " tries");
            log("gave up: cabin camera still missing after " + MAX_RETRY + " tries");
            return;
        }
        cancelRetry();
        retryCount++;
        retryRunnable = () -> bindCamera(surfaceTexture);
        handler.postDelayed(retryRunnable, RETRY_DELAY_MS);
    }

    private void cancelRetry() {
        if (retryRunnable != null) {
            handler.removeCallbacks(retryRunnable);
            retryRunnable = null;
        }
    }

    private void startWatchdog() {
        cancelWatchdog();
        watchdog = new Runnable() {
            @Override
            public void run() {
                if (ScreenState.dark()) {
                    handler.postDelayed(this, WATCHDOG_INTERVAL_MS);
                    return;
                }
                MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
                SingleCamera current = manager != null ? manager.getCamera(CabinWindow.CAMERA_KEY) : null;
                if (boundCamera == null || boundCamera != current || !boundCamera.isCameraOpened()) {
                    log("watchdog rebind: bound=" + (boundCamera != null)
                            + " sameCamera=" + (boundCamera == current)
                            + " opened=" + (boundCamera != null && boundCamera.isCameraOpened())
                            + " " + CabinLog.world() + " | " + CabinLog.camera(current, boundTexture));
                    rebind();
                } else if (window != null) {
                    // Stalled: no frame since this binding started, or none for a while after
                    long now = android.os.SystemClock.uptimeMillis();
                    long since = Math.max(window.lastFrameAt(), lastRebuildMs);
                    if (now - since >= STALL_MS) {
                        lastRebuildMs = now;
                        AppLog.w(TAG, "cabin passenger has no frames, declaring again");
                        log("stalled: frames=" + window.frameCount() + " lastFrame="
                                + (window.lastFrameAt() > 0 ? (now - window.lastFrameAt()) + "ms ago" : "never")
                                + " " + CabinLog.world() + " | " + CabinLog.camera(boundCamera, boundTexture));
                        rebind();
                        manager.channelScheduler().wake();
                    }
                }
                handler.postDelayed(this, WATCHDOG_INTERVAL_MS);
            }
        };
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS);
    }

    /** A second look shortly after binding: did the canvas reach the session, did frames arrive. */
    private void logLater(SurfaceTexture texture, long delayMs) {
        handler.postDelayed(() -> {
            if (window == null || texture != boundTexture) {
                return;
            }
            log("+" + delayMs + "ms frames=" + window.frameCount() + " drawn=" + window.drawCount()
                    + " view=" + window.getWidth() + "x" + window.getHeight()
                    + " shown=" + window.isShown() + " | " + CabinLog.camera(boundCamera, texture));
        }, delayMs);
    }

    private void log(String text) {
        CabinLog.line(this, text);
    }

    private void cancelWatchdog() {
        if (watchdog != null) {
            handler.removeCallbacks(watchdog);
            watchdog = null;
        }
    }
}
