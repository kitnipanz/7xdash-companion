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

    private static volatile CabinPassengerService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private AppConfig appConfig;
    private CabinPassengerView window;
    private SingleCamera boundCamera;
    private SurfaceTexture boundTexture;
    private int retryCount;
    private long lastRebuildMs;
    private Runnable retryRunnable;
    private Runnable watchdog;

    private final ScreenState.Listener screenListener = new ScreenState.Listener() {
        @Override
        public void onScreenOff() {
            if (CameraNeeds.current().isHeld(CameraNeeds.Holder.RECORDING)) {
                return;
            }
            unbindCamera();
        }

        @Override
        public void onScreenOn() {
            rebind();
        }
    };

    public static void start(Context context) {
        context.startService(new Intent(context, CabinPassengerService.class));
    }

    public static void stop(Context context) {
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
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (window == null) {
            window = new CabinPassengerView(this, appConfig);
            window.setSurfaceReady(this::onSurface);
            window.show();
            Toast.makeText(this, R.string.msg_cabin_on, Toast.LENGTH_SHORT).show();
        }
        startWatchdog();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
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

    private void onSurface(SurfaceTexture surface) {
        if (surface == null) {
            unbindCamera();
            return;
        }
        bindCamera(surface);
    }

    private void bindCamera(SurfaceTexture surfaceTexture) {
        if (ScreenState.dark() || window == null || surfaceTexture == null) {
            return;
        }
        MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
        if (manager == null) {
            manager = CameraManagerHolder.getInstance().getOrInit(this);
        }
        SingleCamera camera = manager != null ? manager.getCamera(CabinWindow.CAMERA_KEY) : null;
        if (camera == null) {
            scheduleRetry(surfaceTexture);
            return;
        }
        boundCamera = camera;
        boundTexture = surfaceTexture;
        Size preview = camera.getPreviewSize();
        if (preview != null) {
            window.setBufferSize(preview.getWidth(), preview.getHeight());
        }
        window.setSystemMirrored(systemFlips(camera));
        Size buffer = camera.getPreviewBufferSize();
        if (buffer != null && buffer.getWidth() > 0 && buffer.getHeight() > 0) {
            surfaceTexture.setDefaultBufferSize(buffer.getWidth(), buffer.getHeight());
            window.setBufferSize(buffer.getWidth(), buffer.getHeight());
        }
        boolean declared = camera.declareMirror(surfaceTexture);
        CameraNeeds.current().claim(CameraNeeds.Holder.CABIN);
        if (declared) {
            manager.channelScheduler().wake();
        }
        lastRebuildMs = android.os.SystemClock.uptimeMillis();
        retryCount = 0;
        AppLog.i(TAG, "cabin passenger bound, preview " + preview);
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
        cancelRetry();
        CameraNeeds.current().release(CameraNeeds.Holder.CABIN);
        if (boundCamera != null && boundTexture != null) {
            boundCamera.withdrawMirror(boundTexture);
        }
        boundCamera = null;
        boundTexture = null;
    }

    private void rebind() {
        if (window == null) {
            return;
        }
        SurfaceTexture texture = window.getTextureView().getSurfaceTexture();
        if (texture != null) {
            bindCamera(texture);
        }
    }

    private void scheduleRetry(SurfaceTexture surfaceTexture) {
        if (retryCount >= MAX_RETRY) {
            AppLog.w(TAG, "cabin camera still missing after " + MAX_RETRY + " tries");
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
                if (boundCamera == null || !boundCamera.isCameraOpened()) {
                    rebind();
                } else if (window != null && !window.hasFrame()) {
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - lastRebuildMs >= 8000L) {
                        lastRebuildMs = now;
                        MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
                        if (manager != null) {
                            manager.channelScheduler().wake();
                        }
                    }
                }
                handler.postDelayed(this, WATCHDOG_INTERVAL_MS);
            }
        };
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS);
    }

    private void cancelWatchdog() {
        if (watchdog != null) {
            handler.removeCallbacks(watchdog);
            watchdog = null;
        }
    }
}
