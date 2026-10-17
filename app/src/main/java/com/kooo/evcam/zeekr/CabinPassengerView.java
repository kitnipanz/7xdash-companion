package com.kooo.evcam.zeekr;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.profile.CameraProfile;
import com.kooo.evcam.profile.LaneLayout;
import com.kooo.evcam.profile.ProfileSizes;
import com.kooo.evcam.profile.ProfileStore;

/**
 * Floating window of the front cabin. Drag moves it. A pinch, or the size sliders, changes
 * how much of the cabin is visible. The picture is not stretched.
 */
public class CabinPassengerView extends ViewGroup {

    interface SurfaceReady {
        void onSurface(SurfaceTexture surface);
    }

    private static final String TAG = "CabinPassenger";
    private static final int DRAG_SLOP_PX = 12;

    private final WindowManager windowManager;
    private final AppConfig appConfig;
    private final TextureView textureView;
    private boolean sawFrame;

    private WindowManager.LayoutParams params;
    private boolean attached;
    private SurfaceReady surfaceReady;
    private int bufferWidth;
    private int bufferHeight;
    private boolean systemMirrored;

    private float downX;
    private float downY;
    private int startX;
    private int startY;
    private boolean dragging;
    private boolean pinching;
    private float pinchSpan;
    private int pinchWidth;
    private int pinchHeight;

    public CabinPassengerView(Context context, AppConfig appConfig) {
        super(context);
        this.appConfig = appConfig;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        setBackgroundColor(0xFF000000);
        textureView = new TextureView(context);
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                deliver(surface);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                deliver(surface);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                deliver(null);
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                sawFrame = true;
                invalidate();
            }
        });
        addView(textureView);
    }

    public void setSurfaceReady(SurfaceReady ready) {
        this.surfaceReady = ready;
    }

    public void setBufferSize(int width, int height) {
        bufferWidth = width;
        bufferHeight = height;
        invalidate();
    }

    /** Front-facing cameras are flipped once by Android. Undo that before the saved mirror. */
    public void setSystemMirrored(boolean mirrored) {
        systemMirrored = mirrored;
        invalidate();
    }

    public boolean hasFrame() {
        return sawFrame;
    }

    public TextureView getTextureView() {
        return textureView;
    }

    public void show() {
        if (attached) {
            return;
        }
        int width = appConfig.getCabinPassengerWidth(screenWidth());
        int height = appConfig.getCabinPassengerHeight(screenHeight());
        params = new WindowManager.LayoutParams(
                width, height,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        int savedX = appConfig.getCabinPassengerX();
        int savedY = appConfig.getCabinPassengerY();
        if (savedX < 0 || savedY < 0) {
            savedX = screenWidth() - width - 40;
            savedY = 80;
        }
        params.x = CabinWindow.clampOrigin(savedX, width, screenWidth());
        params.y = CabinWindow.clampOrigin(savedY, height, screenHeight());
        try {
            windowManager.addView(this, params);
            attached = true;
        } catch (Exception e) {
            AppLog.e(TAG, "cabin window failed to attach", e);
        }
    }

    public void applySizeFromConfig() {
        if (params == null) {
            return;
        }
        params.width = appConfig.getCabinPassengerWidth(screenWidth());
        params.height = appConfig.getCabinPassengerHeight(screenHeight());
        params.x = CabinWindow.clampOrigin(params.x, params.width, screenWidth());
        params.y = CabinWindow.clampOrigin(params.y, params.height, screenHeight());
        applyLayout();
        invalidate();
    }

    public void hide() {
        if (!attached) {
            return;
        }
        try {
            windowManager.removeView(this);
        } catch (Exception e) {
            AppLog.w(TAG, "cabin window remove failed: " + e);
        }
        attached = false;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        textureView.layout(0, 0, r - l, b - t);
    }

    /**
     * The camera writes into the child. This head unit does not show that child on its own.
     * Super mirror paints it here, once per frame. A transform on the child was leaving this
     * window on its black background.
     */
    @Override
    protected void dispatchDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        int save = canvas.save();
        if (mirror()) {
            canvas.scale(-1f, 1f, width / 2f, height / 2f);
        }
        if (bufferWidth > 0 && bufferHeight > 0) {
            float scale = Math.max(width / (float) bufferWidth, height / (float) bufferHeight);
            float drawnW = bufferWidth * scale;
            float drawnH = bufferHeight * scale;
            canvas.translate((width - drawnW) / 2f, (height - drawnH) / 2f);
            canvas.scale(drawnW / width, drawnH / height);
        }
        drawChild(canvas, textureView, getDrawingTime());
        canvas.restoreToCount(save);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (params == null) {
            return false;
        }
        if (event.getPointerCount() >= 2) {
            pinch(event);
            return true;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pinching = false;
                dragging = false;
                downX = event.getRawX();
                downY = event.getRawY();
                startX = params.x;
                startY = params.y;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pinching || params == null) {
                    return true;
                }
                float dx = event.getRawX() - downX;
                float dy = event.getRawY() - downY;
                if (!dragging && (Math.abs(dx) > DRAG_SLOP_PX || Math.abs(dy) > DRAG_SLOP_PX)) {
                    dragging = true;
                }
                if (dragging) {
                    params.x = CabinWindow.clampOrigin(startX + Math.round(dx), params.width, screenWidth());
                    params.y = CabinWindow.clampOrigin(startY + Math.round(dy), params.height, screenHeight());
                    applyLayout();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (params != null && (dragging || pinching)) {
                    appConfig.setCabinPassengerOrigin(params.x, params.y);
                    if (pinching) {
                        appConfig.setCabinPassengerSize(params.width, params.height, screenWidth(), screenHeight());
                    }
                }
                dragging = false;
                pinching = false;
                return true;
            default:
                return true;
        }
    }

    private void pinch(MotionEvent event) {
        if (params == null || event.getPointerCount() < 2) {
            return;
        }
        float span = span(event);
        if (!pinching) {
            pinching = true;
            dragging = false;
            pinchSpan = span;
            pinchWidth = params.width;
            pinchHeight = params.height;
            return;
        }
        if (pinchSpan < 1f) {
            return;
        }
        float scale = span / pinchSpan;
        params.width = CabinWindow.clamp(Math.round(pinchWidth * scale), screenWidth());
        params.height = CabinWindow.clamp(Math.round(pinchHeight * scale), screenHeight());
        params.x = CabinWindow.clampOrigin(params.x, params.width, screenWidth());
        params.y = CabinWindow.clampOrigin(params.y, params.height, screenHeight());
        applyLayout();
        invalidate();
    }

    private static float span(MotionEvent event) {
        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);
        return (float) Math.hypot(dx, dy);
    }

    /** Saved mirror, after undoing the flip Android already applied to a front-facing camera. */
    private boolean mirror() {
        LaneLayout lane = lane();
        boolean saved = lane != null && lane.mirrored;
        return saved != systemMirrored;
    }

    private LaneLayout lane() {
        try {
            String role = ProfileSizes.roleForCameraKey(CabinWindow.CAMERA_KEY);
            if (role == null) {
                return null;
            }
            CameraProfile camera = new ProfileStore(getContext()).current().camera(role);
            if (camera == null || camera.lanes.isEmpty()) {
                return null;
            }
            return camera.lanes.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private void deliver(SurfaceTexture surface) {
        if (surfaceReady != null) {
            surfaceReady.onSurface(surface);
        }
    }

    private void applyLayout() {
        if (!attached || params == null) {
            return;
        }
        try {
            windowManager.updateViewLayout(this, params);
        } catch (Exception e) {
            AppLog.w(TAG, "cabin window layout failed: " + e);
        }
    }

    private int screenWidth() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private int screenHeight() {
        return getResources().getDisplayMetrics().heightPixels;
    }
}
