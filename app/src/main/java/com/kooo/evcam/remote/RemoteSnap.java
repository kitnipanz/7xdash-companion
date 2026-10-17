package com.kooo.evcam.remote;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import com.kooo.evcam.zeekr.CompositeStreamGeometry;
import com.kooo.evcam.zeekr.FisheyeMesh;
import com.kooo.evcam.zeekr.RemoteFrame;

import java.io.ByteArrayOutputStream;

/**
 * Fresh remote stills: split the surround strip, always defish each lane,
 * build a 2×2 drive mosaic, and encode JPEG (wire) + PNG (USB).
 */
public final class RemoteSnap {

    public static final int CELL_WIDTH = RemoteFrame.CELL_WIDTH;
    public static final int CELL_HEIGHT = RemoteFrame.CELL_HEIGHT;

    private static final int JPEG_QUALITY = 72;

    private final FisheyeMesh mesh = new FisheyeMesh();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final float[] window = new float[4];
    private final float[] fallbackLanes = new float[CompositeStreamGeometry.LANE_COUNT * 4];
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();
    /** False while a clip is recording: JPEG stays in RAM, USB PNG is skipped. */
    private volatile boolean dumpUsb = true;

    /** Recording keeps the mailbox in memory. Idle watch may also dump PNG to USB. */
    public void setDumpUsb(boolean dumpUsb) {
        this.dumpUsb = dumpUsb;
    }

    /**
     * Four defished surround channels + a 2×2 drive mosaic.
     * Surround is always straightened (user requirement).
     */
    public void putSurround(RemoteLive live, Bitmap source, float[] lanes, int[] order,
                            float fovDegrees, String projection, float strength) {
        if (live == null || source == null) {
            return;
        }
        float[] use = lanes;
        if (!RemoteFrame.lanesUsable(use)) {
            RemoteFrame.equalVerticalLanes(fallbackLanes);
            use = fallbackLanes;
        }
        int[] cellOrder = order;
        if (cellOrder == null || cellOrder.length < CompositeStreamGeometry.LANE_COUNT) {
            cellOrder = new int[]{0, 1, 2, 3};
        }

        mesh.setCorrection(fovDegrees, projection, strength);
        RemoteFrame.correctedWindow((float) CELL_WIDTH / CELL_HEIGHT, window);

        int sw = source.getWidth();
        int sh = source.getHeight();

        Bitmap[] channels = new Bitmap[CompositeStreamGeometry.LANE_COUNT];
        for (int streamLane = 0; streamLane < CompositeStreamGeometry.LANE_COUNT; streamLane++) {
            Bitmap cell = Bitmap.createBitmap(CELL_WIDTH, CELL_HEIGHT, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(cell);
            canvas.drawColor(Color.BLACK);
            drawLaneDefish(canvas, source, use, streamLane, sw, sh, 0f, 0f, CELL_WIDTH, CELL_HEIGHT);
            channels[streamLane] = cell;
        }

        Bitmap drive = Bitmap.createBitmap(CELL_WIDTH * 2, CELL_HEIGHT * 2, Bitmap.Config.ARGB_8888);
        Canvas driveCanvas = new Canvas(drive);
        driveCanvas.drawColor(Color.BLACK);
        for (int cell = 0; cell < CompositeStreamGeometry.LANE_COUNT; cell++) {
            int streamLane = cellOrder[cell];
            if (streamLane < 0 || streamLane >= channels.length || channels[streamLane] == null) {
                continue;
            }
            float dx = (cell % 2) * CELL_WIDTH;
            float dy = (cell / 2) * CELL_HEIGHT;
            driveCanvas.drawBitmap(channels[streamLane], dx, dy, paint);
        }

        putEncoded(live, RemoteLive.DRIVE, drive);
        putEncoded(live, RemoteLive.CH1, channels[0]);
        putEncoded(live, RemoteLive.CH2, channels[1]);
        putEncoded(live, RemoteLive.CH3, channels[2]);
        putEncoded(live, RemoteLive.CH4, channels[3]);

        drive.recycle();
        for (Bitmap cell : channels) {
            if (cell != null) {
                cell.recycle();
            }
        }
    }

    /** Cabin / non-surround still: center-crop to 16:9, no defish. */
    public void putCabin(RemoteLive live, String slot, Bitmap source) {
        if (live == null || source == null || slot == null) {
            return;
        }
        Bitmap framed = frameSixteenByNine(source, CELL_WIDTH * 2, CELL_HEIGHT * 2);
        putEncoded(live, slot, framed);
        if (framed != source) {
            framed.recycle();
        }
    }

    private void putEncoded(RemoteLive live, String slot, Bitmap bitmap) {
        byte[] jpeg = compress(bitmap, Bitmap.CompressFormat.JPEG, JPEG_QUALITY);
        byte[] png = dumpUsb ? compress(bitmap, Bitmap.CompressFormat.PNG, 100) : null;
        live.put(slot, jpeg, png);
    }

    private static byte[] compress(Bitmap bitmap, Bitmap.CompressFormat format, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        if (!bitmap.compress(format, quality, out)) {
            return null;
        }
        return out.toByteArray();
    }

    private void drawLaneDefish(Canvas canvas, Bitmap source, float[] lanes, int lane, int sw, int sh,
                                float dx, float dy, float dw, float dh) {
        if (lane < 0 || lane >= CompositeStreamGeometry.LANE_COUNT) {
            return;
        }
        int at = lane * 4;
        float left = lanes[at] * sw;
        float top = lanes[at + 1] * sh;
        float laneWidth = (lanes[at + 2] - lanes[at]) * sw;
        float laneHeight = (lanes[at + 3] - lanes[at + 1]) * sh;
        if (laneWidth < 2f || laneHeight < 2f) {
            return;
        }
        int x = Math.max(0, Math.round(left));
        int y = Math.max(0, Math.round(top));
        int w = Math.min(sw - x, Math.max(2, Math.round(laneWidth)));
        int h = Math.min(sh - y, Math.max(2, Math.round(laneHeight)));
        if (x + w > sw || y + h > sh || w < 2 || h < 2) {
            return;
        }
        // One camera, not the whole strip. Each mesh cell redraws this bitmap.
        Bitmap laneBmp = Bitmap.createBitmap(source, x, y, w, h);
        try {
            FisheyeMesh.Painter lanePainter = c -> c.drawBitmap(laneBmp, 0f, 0f, paint);
            mesh.prepare(FisheyeMesh.divisionsFor(Math.max(dw, dh)), 0f, 0f, w, h,
                    window[0], window[1], window[2], window[3]);
            mesh.draw(canvas, dx, dy, dw, dh, lanePainter);
        } finally {
            if (laneBmp != source) {
                laneBmp.recycle();
            }
        }
    }

    private Bitmap frameSixteenByNine(Bitmap source, int outW, int outH) {
        int sw = source.getWidth();
        int sh = source.getHeight();
        if (sw < 2 || sh < 2) {
            return source;
        }
        float srcAspect = (float) sw / sh;
        float dstAspect = (float) outW / outH;
        float left;
        float top;
        float right;
        float bottom;
        if (srcAspect > dstAspect) {
            float useW = sh * dstAspect;
            left = (sw - useW) / 2f;
            top = 0f;
            right = left + useW;
            bottom = sh;
        } else {
            float useH = sw / dstAspect;
            left = 0f;
            top = (sh - useH) / 2f;
            right = sw;
            bottom = top + useH;
        }
        Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.BLACK);
        srcRect.set(Math.round(left), Math.round(top), Math.round(right), Math.round(bottom));
        dstRect.set(0, 0, outW, outH);
        canvas.drawBitmap(source, srcRect, dstRect, paint);
        return out;
    }
}
