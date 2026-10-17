package com.kooo.evcam.remote;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Surface;
import android.view.TextureView;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.zeekr.CompositeStreamGeometry;
import com.kooo.evcam.zeekr.FisheyeGlPipe;
import com.kooo.evcam.zeekr.PreviewDewarp;
import com.kooo.evcam.zeekr.RemoteFrame;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Live H.264 for remote watch.
 *
 * <p>Surround (drive, ch1–ch4) is read straight off the camera texture inside the preview
 * {@link FisheyeGlPipe}: per-pixel defish, no TextureView readback, no CPU copy. Cabin
 * (driver, backseat) has no GL pipe, so its TextureView is grabbed on the UI thread and
 * drawn into the encoder with a canvas. If the surround pipe is not up (camera wired
 * direct), surround falls back to the same canvas path through {@link RemoteFrame}.</p>
 *
 * <p>Main thread only, except the packet sink.</p>
 */
public final class RemoteStream {

    /** What the activity lends us. Called on the main thread. */
    public interface Host {
        TextureView surroundView();

        TextureView driverView();

        TextureView backseatView();

        /** Stream lanes u0,v0,u1,v1 ×4 from the four-lane container; false if unknown. */
        boolean copyLaneWindows(float[] out);

        int[] laneOrder();

        float fisheyeFov();

        String fisheyeProjection();
    }

    private static final String TAG = "RemoteStream";
    public static final int WIDTH = 1280;
    public static final int HEIGHT = 720;
    private static final int GL_FPS = 20;
    private static final int CANVAS_FPS = 12;
    private static final int BITRATE = 2_000_000;
    private static final long CHECK_MS = 1000L;
    /** Header: 'V', flags, source, w(2), h(2), pts us(8). */
    public static final int HEADER = 15;
    public static final byte MAGIC = 'V';

    /** Same order as the web client. */
    private static final String[] SOURCES = {
            RemoteLive.DRIVE, RemoteLive.CH1, RemoteLive.CH2, RemoteLive.CH3, RemoteLive.CH4,
            RemoteLive.DRIVER, RemoteLive.BACKSEAT,
    };

    private enum Mode { NONE, GL, CANVAS }

    private final Host host;
    private final CarLink link;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService drawExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "remote-canvas");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final AtomicBoolean drawing = new AtomicBoolean(false);
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();
    private final RemoteFrame composer = new RemoteFrame();
    private final float[] windows = new float[CompositeStreamGeometry.LANE_COUNT * 4];

    private LiveEncoder encoder;
    private Mode mode = Mode.NONE;
    private FisheyeGlPipe tapped;
    private String source = RemoteLive.DRIVE;
    private volatile byte sourceCode;
    private volatile long lastPacketMs;
    private volatile long lastKeyAskMs;
    private boolean running;

    private final Runnable canvasTick = new Runnable() {
        @Override
        public void run() {
            if (!running || mode != Mode.CANVAS) {
                return;
            }
            feedCanvas();
            main.postDelayed(this, 1000L / CANVAS_FPS);
        }
    };

    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            // Pipe rebuilt (camera re-session) or came up late: rebind or switch path.
            Mode want = wantedMode(source);
            if (want != mode || (mode == Mode.GL && PreviewDewarp.pipe() != tapped)) {
                AppLog.i(TAG, "rebind " + source + " " + mode + " -> " + want);
                bind(source, true);
            }
            main.postDelayed(this, CHECK_MS);
        }
    };

    public RemoteStream(Host host, CarLink link) {
        this.host = host;
        this.link = link;
    }

    public boolean isRunning() {
        return running;
    }

    /** Start, or switch camera while running. False when no encoder could be made. */
    public boolean start(String requested) {
        String src = RemoteLive.slotFor(requested);
        boolean was = running;
        running = true;
        if (!bind(src, !was)) {
            stop();
            return false;
        }
        if (!was) {
            main.postDelayed(check, CHECK_MS);
        }
        return true;
    }

    public void stop() {
        running = false;
        main.removeCallbacks(check);
        main.removeCallbacks(canvasTick);
        unbind();
    }

    public void release() {
        stop();
        drawExec.shutdownNow();
    }

    /** Last packet age; the activity falls back to JPEG if video never arrives. */
    public long msSinceLastPacket() {
        long t = lastPacketMs;
        return t == 0 ? Long.MAX_VALUE : SystemClock.elapsedRealtime() - t;
    }

    private Mode wantedMode(String src) {
        if (isSurround(src) && PreviewDewarp.pipe() != null && PreviewDewarp.laneRects() != null) {
            return Mode.GL;
        }
        return Mode.CANVAS;
    }

    private static boolean isSurround(String src) {
        return RemoteLive.DRIVE.equals(src) || RemoteFrame.laneForSource(src) >= 0;
    }

    private boolean bind(String src, boolean fresh) {
        Mode want = wantedMode(src);
        // Same producer kind: keep the encoder, change the picture, force a keyframe.
        if (!fresh && encoder != null && want == mode
                && (mode != Mode.GL || PreviewDewarp.pipe() == tapped)) {
            source = src;
            sourceCode = codeOf(src);
            if (mode == Mode.GL && !applyTap()) {
                return bind(src, true);
            }
            encoder.requestKeyFrame();
            return true;
        }
        unbind();
        source = src;
        sourceCode = codeOf(src);
        lastPacketMs = 0;
        encoder = LiveEncoder.start(WIDTH, HEIGHT, want == Mode.GL ? GL_FPS : CANVAS_FPS, BITRATE,
                this::onPacket);
        if (encoder == null) {
            return false;
        }
        mode = want;
        if (mode == Mode.GL) {
            tapped = PreviewDewarp.pipe();
            if (tapped != null && applyTap()) {
                return true;
            }
            // Pipe refused the encoder surface: canvas path, fresh encoder (EGL may hold it).
            AppLog.w(TAG, "GL tap failed, canvas fallback");
            unbind();
            encoder = LiveEncoder.start(WIDTH, HEIGHT, CANVAS_FPS, BITRATE, this::onPacket);
            if (encoder == null) {
                return false;
            }
            mode = Mode.CANVAS;
        }
        main.post(canvasTick);
        return true;
    }

    /** Lay the requested surround view out on the tap. */
    private boolean applyTap() {
        FisheyeGlPipe pipe = tapped;
        float[] rects = PreviewDewarp.laneRects();
        if (pipe == null || rects == null || encoder == null) {
            return false;
        }
        int lanes = Math.min(CompositeStreamGeometry.LANE_COUNT, rects.length / 4);
        int only = RemoteFrame.laneForSource(source);
        float[] out;
        float[] src;
        if (only >= 0) {
            if (only >= lanes) {
                return false;
            }
            out = new float[]{0f, 0f, 1f, 1f};
            src = new float[]{rects[only * 4], rects[only * 4 + 1], rects[only * 4 + 2], rects[only * 4 + 3]};
        } else {
            int[] order = host.laneOrder();
            if (order == null || order.length < lanes) {
                order = new int[]{0, 1, 2, 3};
            }
            out = FisheyeGlPipe.GRID_2X2.clone();
            src = new float[16];
            for (int cell = 0; cell < lanes; cell++) {
                int lane = Math.max(0, Math.min(lanes - 1, order[cell]));
                System.arraycopy(rects, lane * 4, src, cell * 4, 4);
            }
        }
        return pipe.setTap(encoder.inputSurface(), out, src, (float) WIDTH / HEIGHT,
                host.fisheyeFov(), host.fisheyeProjection(), GL_FPS);
    }

    private void unbind() {
        main.removeCallbacks(canvasTick);
        if (tapped != null) {
            tapped.clearTap();
            tapped = null;
        }
        LiveEncoder enc = encoder;
        encoder = null;
        mode = Mode.NONE;
        if (enc != null) {
            // Canvas draw may be mid-flight on drawExec; stop there so the surface is not yanked.
            if (drawExec.isShutdown()) {
                enc.stop();
            } else {
                drawExec.execute(enc::stop);
            }
        }
    }

    /** UI thread: grab the TextureView, then draw into the encoder off-thread. */
    private void feedCanvas() {
        LiveEncoder enc = encoder;
        if (enc == null || !drawing.compareAndSet(false, true)) {
            return;
        }
        final String src = source;
        Bitmap grab = null;
        boolean surround = isSurround(src);
        try {
            TextureView view = surround ? host.surroundView()
                    : RemoteLive.DRIVER.equals(src) ? host.driverView() : host.backseatView();
            if (view != null && view.isAvailable() && view.getWidth() > 1 && view.getHeight() > 1) {
                grab = surround ? grabSurround(view) : view.getBitmap(
                        Math.min(WIDTH, view.getWidth()), Math.min(HEIGHT, view.getHeight()));
            }
        } catch (RuntimeException e) {
            AppLog.w(TAG, "grab failed: " + e.getMessage());
        }
        if (grab == null) {
            drawing.set(false);
            return;
        }
        final Bitmap bmp = grab;
        final boolean straighten = surround;
        final float[] lanes = surround ? windows.clone() : null;
        final int[] order = surround ? host.laneOrder() : null;
        final float fov = host.fisheyeFov();
        final String projection = host.fisheyeProjection();
        drawExec.execute(() -> {
            try {
                if (enc != encoder) {
                    return;
                }
                Surface surface = enc.inputSurface();
                if (surface == null || !surface.isValid()) {
                    return;
                }
                Bitmap picture = bmp;
                if (straighten) {
                    picture = composer.compose(bmp, lanes, order, RemoteFrame.laneForSource(src),
                            true, fov, projection, 1f);
                }
                Canvas canvas = surface.lockHardwareCanvas();
                try {
                    drawFitted(canvas, picture);
                } finally {
                    surface.unlockCanvasAndPost(canvas);
                }
            } catch (RuntimeException e) {
                AppLog.w(TAG, "canvas frame failed: " + e.getMessage());
            } finally {
                bmp.recycle();
                drawing.set(false);
            }
        });
    }

    private Bitmap grabSurround(TextureView view) {
        if (!host.copyLaneWindows(windows)) {
            RemoteFrame.equalVerticalLanes(windows);
        }
        int[] size = RemoteFrame.grabSize(view.getWidth(), view.getHeight());
        if (size[0] < 2 || size[1] < 2) {
            return null;
        }
        return view.getBitmap(size[0], size[1]);
    }

    /** Center-crop to 16:9 so cabin and fallback frames fill the encoder picture. */
    private void drawFitted(Canvas canvas, Bitmap source) {
        canvas.drawColor(Color.BLACK);
        int sw = source.getWidth();
        int sh = source.getHeight();
        float dstAspect = (float) WIDTH / HEIGHT;
        float srcAspect = (float) sw / sh;
        if (srcAspect > dstAspect) {
            int useW = Math.round(sh * dstAspect);
            int left = (sw - useW) / 2;
            srcRect.set(left, 0, left + useW, sh);
        } else {
            int useH = Math.round(sw / dstAspect);
            int top = (sh - useH) / 2;
            srcRect.set(0, top, sw, top + useH);
        }
        dstRect.set(0, 0, canvas.getWidth(), canvas.getHeight());
        canvas.drawBitmap(source, srcRect, dstRect, paint);
    }

    private void onPacket(byte[] data, boolean key, long ptsUs, int width, int height) {
        long now = SystemClock.elapsedRealtime();
        lastPacketMs = now;
        boolean sent = link.pushVideo(frame(data, key, sourceCode, width, height, ptsUs), key);
        // Uplink backed up and deltas got dropped: the phone needs a fresh keyframe to recover.
        if (!sent && now - lastKeyAskMs > 500L) {
            lastKeyAskMs = now;
            LiveEncoder enc = encoder;
            if (enc != null) {
                enc.requestKeyFrame();
            }
        }
    }

    /** Wire packet: 15-byte header then Annex-B. JPEG starts 0xFF, so the phone can tell. */
    public static byte[] frame(byte[] data, boolean key, byte source, int width, int height, long ptsUs) {
        byte[] out = new byte[HEADER + data.length];
        out[0] = MAGIC;
        out[1] = (byte) (key ? 1 : 0);
        out[2] = source;
        out[3] = (byte) (width >> 8);
        out[4] = (byte) width;
        out[5] = (byte) (height >> 8);
        out[6] = (byte) height;
        for (int i = 0; i < 8; i++) {
            out[7 + i] = (byte) (ptsUs >> (56 - 8 * i));
        }
        System.arraycopy(data, 0, out, HEADER, data.length);
        return out;
    }

    public static byte codeOf(String src) {
        String s = RemoteLive.slotFor(src);
        for (int i = 0; i < SOURCES.length; i++) {
            if (SOURCES[i].equals(s)) {
                return (byte) i;
            }
        }
        return 0;
    }
}
