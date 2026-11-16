package com.kooo.evcam.audio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.kooo.evcam.R;

/**
 * Drawn over or under the fifteen faders, lined up with them.
 *
 * <p>Behind the faders: a live spectrum, one bar per band with a falling peak mark. In front:
 * the response the car really gets (sliders plus bass, treble and loudness) as a smooth line
 * with a soft fill to 0 dB. It never takes a touch, so the faders under it still work.</p>
 */
public class EqOverlayView extends View {

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint area = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint peak = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path fillPath = new Path();
    private final RectF rect = new RectF();
    private final int[] here = new int[2];
    private final int[] there = new int[2];
    private final float density;

    private EqBarView[] sliders;
    /** Curve mode: the shaped gains (dB), or null for spectrum mode. */
    private float[] curve;
    /** Spectrum mode: 0..1 per band, or null when off. */
    private float[] levels;
    private float[] peaks;

    public EqOverlayView(Context context) {
        this(context, null);
    }

    public EqOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        int energy = ContextCompat.getColor(context, R.color.energy);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.5f * density);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setColor(ContextCompat.getColor(context, R.color.text_primary));
        line.setAlpha(200);
        area.setColor(energy);
        area.setAlpha(36);
        bar.setColor(energy);
        bar.setAlpha(56);
        peak.setColor(energy);
        peak.setAlpha(150);
        setWillNotDraw(false);
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        return false;
    }

    public void attach(EqBarView[] sliders) {
        this.sliders = sliders;
    }

    /** Shows the response line for these shaped gains. */
    public void showCurve(float[] shaped) {
        curve = shaped == null ? null : shaped.clone();
        invalidate();
    }

    /** Shows these spectrum levels (0..1 per band); null hides the spectrum. */
    public void showLevels(float[] next) {
        if (next == null) {
            levels = null;
            peaks = null;
        } else {
            levels = next.clone();
            if (peaks == null || peaks.length != next.length) {
                peaks = new float[next.length];
            }
            for (int i = 0; i < next.length; i++) {
                peaks[i] = Math.max(next[i], peaks[i] - 0.012f);
            }
        }
        invalidate();
    }

    /** Where slider {@code i}'s track sits in this view: centre x, y of +12 dB, y of -12 dB. */
    private boolean track(int i, float[] out) {
        EqBarView s = sliders[i];
        if (s.getWidth() == 0) {
            return false;
        }
        getLocationInWindow(here);
        s.getLocationInWindow(there);
        float left = there[0] - here[0];
        float top = there[1] - here[1];
        out[0] = left + s.getWidth() / 2f;
        out[1] = top + s.yForValue(CabinEq.GAIN_MAX);
        out[2] = top + s.yForValue(CabinEq.GAIN_MIN);
        out[3] = s.getWidth();
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (sliders == null) {
            return;
        }
        float[] t = new float[4];
        if (levels != null) {
            for (int i = 0; i < sliders.length && i < levels.length; i++) {
                if (!track(i, t)) {
                    continue;
                }
                float w = Math.min(t[3] * 0.56f, 44 * density);
                float h = t[2] - t[1];
                float yTop = t[2] - levels[i] * h;
                rect.set(t[0] - w / 2, yTop, t[0] + w / 2, t[2]);
                canvas.drawRoundRect(rect, 6 * density, 6 * density, bar);
                if (peaks[i] < 0.02f) {
                    continue;
                }
                float yPeak = t[2] - peaks[i] * h;
                rect.set(t[0] - w / 2, yPeak - 1.5f * density, t[0] + w / 2, yPeak + 1.5f * density);
                canvas.drawRect(rect, peak);
            }
        }
        if (curve != null) {
            int n = Math.min(sliders.length, curve.length);
            float[] xs = new float[n];
            float[] ys = new float[n];
            float zeroY = 0;
            for (int i = 0; i < n; i++) {
                if (!track(i, t)) {
                    return;
                }
                xs[i] = t[0];
                float v = Math.max(CabinEq.GAIN_MIN, Math.min(CabinEq.GAIN_MAX, curve[i]));
                ys[i] = t[2] - (v - CabinEq.GAIN_MIN) / (CabinEq.GAIN_MAX - CabinEq.GAIN_MIN) * (t[2] - t[1]);
                zeroY = (t[1] + t[2]) / 2f;
            }
            path.reset();
            path.moveTo(xs[0], ys[0]);
            // Catmull-Rom through the points, as cubic Béziers
            for (int i = 0; i < n - 1; i++) {
                float x0 = i > 0 ? xs[i - 1] : xs[i];
                float y0 = i > 0 ? ys[i - 1] : ys[i];
                float x3 = i + 2 < n ? xs[i + 2] : xs[i + 1];
                float y3 = i + 2 < n ? ys[i + 2] : ys[i + 1];
                path.cubicTo(xs[i] + (xs[i + 1] - x0) / 6f, ys[i] + (ys[i + 1] - y0) / 6f,
                        xs[i + 1] - (x3 - xs[i]) / 6f, ys[i + 1] - (y3 - ys[i]) / 6f,
                        xs[i + 1], ys[i + 1]);
            }
            fillPath.reset();
            fillPath.addPath(path);
            fillPath.lineTo(xs[n - 1], zeroY);
            fillPath.lineTo(xs[0], zeroY);
            fillPath.close();
            canvas.drawPath(fillPath, area);
            canvas.drawPath(path, line);
        }
    }
}
