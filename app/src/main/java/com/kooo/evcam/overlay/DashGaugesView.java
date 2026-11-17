package com.kooo.evcam.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.kooo.evcam.R;

/**
 * Three round dials in a row under the Dash panel, like a car-scanner app: a 270° scale with ticks
 * and its red part, a needle, the number and unit under the hub, the name on top, and a small mark
 * where the needle has been furthest (since the gauge was chosen or the panel opened). Drawn to scale. Always dark.
 */
public final class DashGaugesView extends View {

    /** Width : height of the strip. */
    public static final float ASPECT = 3.3f;
    /**
     * Gauges only: the Dash panel's right-hand card (minimize, record, dim, quit) beside the dials,
     * in hundredths of the height, and the see-through gap before it.
     */
    private static final float COLUMN_U = 30f;
    private static final float GAP_U = 4f;
    /** Gauges only: the Dash panel's bottom row under the dials, in hundredths of the height. */
    private static final float ROW_U = 22f;
    /** Width : height with the card and the row: the dials keep their shape in what is left above the row. */
    public static final float ASPECT_WITH_BUTTONS = ASPECT * (1f - ROW_U / 100f) + (COLUMN_U + GAP_U) / 100f;
    /** Width : height of the card alone (minimized). */
    public static final float ASPECT_BUTTONS_ONLY = COLUMN_U / 100f;
    /** {@link #buttonAt} values, top to bottom. */
    public static final int MINIMIZE = 0;
    public static final int RECORD = 1;
    public static final int DIM_BUTTON = 2;
    public static final int QUIT_BUTTON = 3;
    /** The bottom row, left to right, after the card's four: surround photo, instant capture, Super mirror, open 7xDash. */
    public static final int ROW_START = 4;
    private static final int[] BUTTON_ICONS = {R.drawable.ic_collapse, R.drawable.ic_record,
            R.drawable.ic_dim, R.drawable.ic_power,
            R.drawable.ic_surround, R.drawable.ic_flash, R.drawable.ic_rearview, R.drawable.ic_home};
    private static final int COLUMN_COUNT = 4;
    /** The dial's sweep, starting bottom left, clockwise. */
    private static final float START = 135f;
    private static final float SWEEP = 270f;
    private static final int MAJOR_TICKS = 10;

    private static final int BG = 0xE6141518;
    private static final int FACE = 0xFF1E2024;
    private static final int TRACK = 0xFF34373D;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int DIM = 0xFF8E9096;
    private static final int RED = 0xFFFF4D4F;
    private static final int NEEDLE = 0xFFFF7A1A;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final int accent;

    private DashGauge[] gauges = DashGauge.DEFAULTS.clone();
    private final Float[] values = new Float[DashGauge.COUNT];
    /** Furthest the needle went, as a dial fraction; NaN when not yet. */
    private final float[] peaks = new float[DashGauge.COUNT];
    private final String[] labels = new String[DashGauge.COUNT];
    private final String[] units = new String[DashGauge.COUNT];
    private int pressed = -1;
    /** Gauges only: draw the card of buttons on the right. */
    private boolean buttons;
    /** Only the card (the dials folded away). */
    private boolean minimized;
    private boolean rec;
    private boolean dimOn;
    private boolean quitArmed;
    private final android.graphics.drawable.Drawable[] icons =
            new android.graphics.drawable.Drawable[BUTTON_ICONS.length];
    private final android.graphics.drawable.Drawable expandIcon;
    private final int recording;

    public DashGaugesView(Context context) {
        super(context);
        accent = ContextCompat.getColor(context, R.color.energy);
        recording = ContextCompat.getColor(context, R.color.recording);
        for (int i = 0; i < BUTTON_ICONS.length; i++) {
            icons[i] = tinted(context, BUTTON_ICONS[i]);
        }
        expandIcon = tinted(context, R.drawable.ic_maximize);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        java.util.Arrays.fill(peaks, Float.NaN);
        setContentDescription(context.getString(R.string.gauges_title));
        setGauges(gauges);
    }

    private static android.graphics.drawable.Drawable tinted(Context context, int res) {
        android.graphics.drawable.Drawable d = ContextCompat.getDrawable(context, res);
        if (d != null) {
            d = d.mutate();
            d.setTint(TEXT);
        }
        return d;
    }

    /** Gauges only: show the card of buttons, and whether the dials are folded away. */
    public void setButtons(boolean buttons, boolean minimized) {
        if (this.buttons != buttons || this.minimized != minimized) {
            this.buttons = buttons;
            this.minimized = buttons && minimized;
            invalidate();
        }
    }

    /** Recording (red stop), dim on (lit moon), quit armed (red power). */
    public void controls(boolean rec, boolean dimOn, boolean quitArmed) {
        if (this.rec != rec || this.dimOn != dimOn || this.quitArmed != quitArmed) {
            this.rec = rec;
            this.dimOn = dimOn;
            this.quitArmed = quitArmed;
            invalidate();
        }
    }

    /** Width of the dials, left of the gap and the card. */
    private float dialsWidth() {
        if (!buttons) {
            return getWidth();
        }
        return minimized ? 0 : getWidth() - (COLUMN_U + GAP_U) * getHeight() / 100f;
    }

    /** Which button of the card a touch (screen coordinates) hit, or -1. */
    public int buttonAt(MotionEvent e) {
        if (!buttons) {
            return -1;
        }
        int[] at = new int[2];
        getLocationOnScreen(at);
        float x = e.getRawX() - at[0];
        float y = e.getRawY() - at[1];
        for (int i = 0; i < BUTTON_ICONS.length; i++) {
            if (minimized && i >= ROW_START) {
                break;
            }
            buttonRect(i, rect);
            if (rect.contains(x, y)) {
                return i;
            }
        }
        return -1;
    }

    /** Height of the dials: all of it, or above the bottom row. */
    private float dialsHeight() {
        return buttons ? getHeight() * (1f - ROW_U / 100f) : getHeight();
    }

    private void buttonRect(int i, RectF out) {
        float u = getHeight() / 100f;
        float pad = 5 * u;
        float gap = 3 * u;
        if (i >= ROW_START) {
            // the bottom row, across the dials' card
            int n = BUTTON_ICONS.length - ROW_START;
            float w = (dialsWidth() - 2 * pad - gap * (n - 1)) / n;
            float left = pad + (i - ROW_START) * (w + gap);
            out.set(left, getHeight() - pad - (ROW_U - 6) * u, left + w, getHeight() - pad);
            return;
        }
        int count = COLUMN_COUNT;
        float h = (getHeight() - 2 * pad - gap * (count - 1)) / count;
        float top = pad + i * (h + gap);
        out.set(getWidth() - COLUMN_U * u + pad, top, getWidth() - pad, top + h);
    }

    /** Which gauges, left to right. A gauge that changed forgets its furthest mark. */
    public void setGauges(DashGauge[] chosen) {
        for (int i = 0; i < DashGauge.COUNT; i++) {
            DashGauge g = i < chosen.length && chosen[i] != null ? chosen[i] : DashGauge.DEFAULTS[i];
            if (g != gauges[i]) {
                peaks[i] = Float.NaN;
                values[i] = null;
            }
            gauges[i] = g;
            labels[i] = getContext().getString(g.labelRes);
            units[i] = getContext().getString(g.unitRes);
        }
        invalidate();
    }

    /** The readings now, one per gauge (null = no reading). */
    public void setValues(Float[] now) {
        boolean changed = false;
        for (int i = 0; i < DashGauge.COUNT; i++) {
            Float v = i < now.length ? now[i] : null;
            if (!java.util.Objects.equals(v, values[i])) {
                values[i] = v;
                changed = true;
            }
            if (v != null) {
                DashGauge g = gauges[i];
                float f = g.fraction(v);
                float zero = g.centreZero ? g.fraction(0f) : 0f;
                if (Float.isNaN(peaks[i]) || Math.abs(f - zero) > Math.abs(peaks[i] - zero)) {
                    peaks[i] = f;
                    changed = true;
                }
            }
        }
        if (changed) {
            invalidate();
        }
    }

    /** Which gauge a touch (screen coordinates) hit, or -1. */
    public int gaugeAt(MotionEvent e) {
        int[] at = new int[2];
        getLocationOnScreen(at);
        float x = e.getRawX() - at[0];
        float y = e.getRawY() - at[1];
        float w = dialsWidth();
        if (x < 0 || y < 0 || x > w || y > dialsHeight() || w <= 0) {
            return -1;
        }
        return Math.min(DashGauge.COUNT - 1, (int) (x / (w / DashGauge.COUNT)));
    }

    /** Lights gauge {@code i} for a moment, so a tap is seen to land. */
    public void flash(int i) {
        pressed = i;
        invalidate();
        postDelayed(() -> {
            pressed = -1;
            invalidate();
        }, 180);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float u = h / 100f;
        if (buttons) {
            drawButtons(canvas, u);
            w = dialsWidth();
            if (w <= 0) {
                return;
            }
        }
        fill.setColor(BG);
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, 10 * u, 10 * u, fill);
        if (buttons) {
            for (int i = ROW_START; i < BUTTON_ICONS.length; i++) {
                drawButton(canvas, i, u);
            }
        }
        float dh = dialsHeight();
        float cell = w / DashGauge.COUNT;
        for (int i = 0; i < DashGauge.COUNT; i++) {
            float cx = cell * i + cell / 2f;
            float r = Math.min(cell, dh) / 2f - 5 * u;
            dial(canvas, i, cx, dh / 2f + 2 * u, r);
        }
    }

    /** The card on the right, drawn like the Dash panel's. */
    private void drawButtons(Canvas canvas, float u) {
        fill.setColor(BG);
        rect.set(getWidth() - COLUMN_U * u, 0, getWidth(), getHeight());
        canvas.drawRoundRect(rect, 8 * u, 8 * u, fill);
        for (int i = 0; i < COLUMN_COUNT; i++) {
            drawButton(canvas, i, u);
        }
    }

    private void drawButton(Canvas canvas, int i, float u) {
        buttonRect(i, rect);
        fill.setColor(i == QUIT_BUTTON && quitArmed ? recording : (pressed == 10 + i ? TRACK : 0xFF26282C));
        canvas.drawRoundRect(rect, 5 * u, 5 * u, fill);
        android.graphics.drawable.Drawable d = minimized && i == MINIMIZE ? expandIcon : icons[i];
        float size = Math.min(rect.width(), rect.height()) * 0.5f;
        if (i == RECORD && rec) {
            fill.setColor(recording);
            float half = size * 0.45f;
            canvas.drawRoundRect(rect.centerX() - half, rect.centerY() - half, rect.centerX() + half,
                    rect.centerY() + half, 2 * u, 2 * u, fill);
        } else if (d != null) {
            d.setTint((i == DIM_BUTTON && dimOn) || i == MINIMIZE ? accent : TEXT);
            int s = Math.round(size);
            int l = Math.round(rect.centerX() - s / 2f);
            int t = Math.round(rect.centerY() - s / 2f);
            d.setBounds(l, t, l + s, t + s);
            d.draw(canvas);
        }
    }

    /** Lights button {@code i} of the card for a moment. */
    public void flashButton(int i) {
        flash(10 + i);
    }

    private void dial(Canvas canvas, int i, float cx, float cy, float r) {
        DashGauge g = gauges[i];
        Float v = values[i];
        fill.setColor(pressed == i ? TRACK : FACE);
        canvas.drawCircle(cx, cy, r, fill);

        float band = r * 0.09f;
        float ar = r - band * 1.2f;
        rect.set(cx - ar, cy - ar, cx + ar, cy + ar);
        // scale track, then its red part
        stroke.setStrokeCap(Paint.Cap.BUTT);
        stroke.setStrokeWidth(band);
        stroke.setColor(TRACK);
        canvas.drawArc(rect, START, SWEEP, false, stroke);
        stroke.setColor(RED);
        if (g.centreZero) {
            float edge = g.fraction(g.red);
            float low = g.fraction(-g.red);
            canvas.drawArc(rect, START + edge * SWEEP, (1f - edge) * SWEEP, false, stroke);
            canvas.drawArc(rect, START, low * SWEEP, false, stroke);
        } else if (g.red < 0) {
            canvas.drawArc(rect, START, g.fraction(-g.red) * SWEEP, false, stroke);
        } else {
            float edge = g.fraction(g.red);
            canvas.drawArc(rect, START + edge * SWEEP, (1f - edge) * SWEEP, false, stroke);
        }
        // the value's own arc, from zero (or the bottom) to the needle
        if (v != null) {
            float from = g.centreZero ? g.fraction(0f) : 0f;
            float to = g.fraction(v);
            stroke.setColor(g.inRed(v) ? RED : accent);
            stroke.setStrokeWidth(band * 0.55f);
            float a = Math.min(from, to);
            canvas.drawArc(rect, START + a * SWEEP, Math.abs(to - from) * SWEEP, false, stroke);
        }

        // ticks: long ones with numbers every tenth, short ones between
        float tickOut = ar - band * 0.9f;
        stroke.setColor(DIM);
        text.setColor(DIM);
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        text.setTextSize(r * 0.13f);
        for (int t = 0; t <= MAJOR_TICKS * 2; t++) {
            boolean major = t % 2 == 0;
            float f = t / (MAJOR_TICKS * 2f);
            double ang = Math.toRadians(START + f * SWEEP);
            float cos = (float) Math.cos(ang);
            float sin = (float) Math.sin(ang);
            float len = major ? r * 0.11f : r * 0.06f;
            stroke.setStrokeWidth(major ? r * 0.025f : r * 0.015f);
            canvas.drawLine(cx + cos * tickOut, cy + sin * tickOut,
                    cx + cos * (tickOut - len), cy + sin * (tickOut - len), stroke);
            if (major && t % 4 == 0) {
                float nr = tickOut - r * 0.23f;
                float value = g.min + f * (g.max - g.min);
                String s = g.decimals > 0 ? String.format(java.util.Locale.US, "%.1f", value)
                        : Integer.toString(Math.round(value));
                canvas.drawText(s, cx + cos * nr, cy + sin * nr - (text.ascent() + text.descent()) / 2f, text);
            }
        }

        // name on top
        text.setColor(DIM);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setTextSize(r * 0.13f);
        // under the top numbers, over the hub
        canvas.drawText(fit(labels[i], r * 0.8f), cx, cy - r * 0.2f, text);

        // furthest mark: a small notch just outside the scale
        if (!Float.isNaN(peaks[i])) {
            double ang = Math.toRadians(START + peaks[i] * SWEEP);
            float cos = (float) Math.cos(ang);
            float sin = (float) Math.sin(ang);
            stroke.setColor(TEXT);
            stroke.setStrokeWidth(r * 0.03f);
            canvas.drawLine(cx + cos * (ar - band * 0.6f), cy + sin * (ar - band * 0.6f),
                    cx + cos * (ar + band * 0.6f), cy + sin * (ar + band * 0.6f), stroke);
        }

        // needle and hub
        if (v != null) {
            double ang = Math.toRadians(START + g.fraction(v) * SWEEP);
            float cos = (float) Math.cos(ang);
            float sin = (float) Math.sin(ang);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setColor(NEEDLE);
            stroke.setStrokeWidth(r * 0.045f);
            canvas.drawLine(cx - cos * r * 0.1f, cy - sin * r * 0.1f,
                    cx + cos * (tickOut - r * 0.02f), cy + sin * (tickOut - r * 0.02f), stroke);
        }
        fill.setColor(0xFF0E0F11);
        canvas.drawCircle(cx, cy, r * 0.09f, fill);
        fill.setColor(v != null ? NEEDLE : TRACK);
        canvas.drawCircle(cx, cy, r * 0.045f, fill);

        // number and unit in the open bottom of the dial
        text.setColor(v != null && g.inRed(v) ? RED : TEXT);
        text.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        // below the end numbers of the scale, which sit about 0.45 r under the hub
        text.setTextSize(r * 0.28f);
        canvas.drawText(v == null ? "–" : g.format(v), cx, cy + r * 0.74f, text);
        text.setColor(DIM);
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        text.setTextSize(r * 0.11f);
        canvas.drawText(units[i], cx, cy + r * 0.90f, text);
    }

    /** {@code s}, cut with an ellipsis to fit {@code width}. */
    private String fit(@Nullable String s, float width) {
        if (s == null) {
            return "";
        }
        if (text.measureText(s) <= width) {
            return s;
        }
        String cut = s;
        while (cut.length() > 1 && text.measureText(cut + "…") > width) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }
}
