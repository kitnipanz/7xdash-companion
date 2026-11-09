package com.kooo.evcam.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.kooo.evcam.R;
import com.kooo.evcam.telemetry.VehicleState;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The Dash panel's picture, drawn to scale so a pinch makes everything bigger or smaller together:
 * clock and recording dot on top, speed with the turn arrows either side and the gear in the middle,
 * a row of four buttons at the bottom ({@link #buttonAt}). On the right, a card of its own with what the
 * floating button used to carry (record, dim, quit), one above the other.
 *
 * <p>Always dark, like an instrument cluster, whatever the app's day / night theme.</p>
 */
public final class DashPanelView extends View {

    /** Width : height of the whole window: the 2 : 1 panel, the gap and the right-hand card. */
    public static final float ASPECT = 2.34f;
    /** The four buttons along the bottom, left to right, then the column on the right, top to bottom. */
    public static final int[] ICONS = {R.drawable.ic_surround, R.drawable.ic_flash, R.drawable.ic_rearview,
            R.drawable.ic_home, R.drawable.ic_record, R.drawable.ic_dim, R.drawable.ic_power};
    /** Buttons in the bottom row; the rest are the column. */
    public static final int ROW = 4;
    /** {@link #buttonAt} for the record button, the first of the column. */
    public static final int RECORD = ROW;
    /** {@link #buttonAt} for the dim button: tap turns it on or off, slide sets how dark. */
    public static final int DIM_BUTTON = ROW + 1;
    /** {@link #buttonAt} for the quit button: the first tap arms it, the second quits. */
    public static final int QUIT_BUTTON = ROW + 2;
    /** Width of the right-hand card, in hundredths of the height. */
    private static final float COLUMN_U = 30f;
    /** See-through gap between the panel and the right-hand card. */
    private static final float GAP_U = 4f;

    private static final int BG = 0xE6141518;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int DIM = 0xFF8E9096;
    private static final int BUTTON = 0xFF26282C;
    private static final int TURN_ON = 0xFF3DD68C;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path arrow = new Path();
    private final Drawable[] icons = new Drawable[ICONS.length];
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final int energy;
    private final int recording;

    private VehicleState state;
    private boolean rec;
    private boolean blinkOn = true;
    /** Battery at arrival line (beta), or null. */
    private String arrival;
    private int arrivalColor = TEXT;
    private boolean dimOn;
    private boolean quitArmed;

    public DashPanelView(Context context) {
        super(context);
        energy = ContextCompat.getColor(context, R.color.energy);
        recording = ContextCompat.getColor(context, R.color.recording);
        for (int i = 0; i < ICONS.length; i++) {
            Drawable d = ContextCompat.getDrawable(context, ICONS[i]);
            if (d != null) {
                d = d.mutate();
                d.setTint(TEXT);
            }
            icons[i] = d;
        }
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    }

    public void show(VehicleState state, boolean recording, boolean blinkOn) {
        this.state = state;
        this.rec = recording;
        this.blinkOn = blinkOn;
        invalidate();
    }

    /** Dim on (moon lit) and quit armed (power red): the right-hand card's two states. */
    public void controls(boolean dimOn, boolean quitArmed) {
        if (this.dimOn != dimOn || this.quitArmed != quitArmed) {
            this.dimOn = dimOn;
            this.quitArmed = quitArmed;
            invalidate();
        }
    }

    /** The battery at arrival line at the top, or null for none. */
    public void arrival(String line, int color) {
        this.arrival = line;
        this.arrivalColor = color;
    }

    /** Which button a tap at (x, y) in this view hit, or -1. */
    public int buttonAt(float x, float y) {
        for (int i = 0; i < ICONS.length; i++) {
            buttonRect(i, rect);
            if (rect.contains(x, y)) {
                return i;
            }
        }
        return -1;
    }

    /** Same as {@link #buttonAt} for a touch in screen coordinates. */
    public int buttonAt(MotionEvent up) {
        int[] at = new int[2];
        getLocationOnScreen(at);
        return buttonAt(up.getRawX() - at[0], up.getRawY() - at[1]);
    }

    private float unit() {
        return getHeight() / 100f;
    }

    private void buttonRect(int i, RectF out) {
        float u = unit();
        float pad = 5 * u;
        float gap = 3 * u;
        if (i >= ROW) {
            int count = ICONS.length - ROW;
            float h = (getHeight() - 2 * pad - gap * (count - 1)) / count;
            float top = pad + (i - ROW) * (h + gap);
            out.set(getWidth() - COLUMN_U * u + pad, top, getWidth() - pad, top + h);
            return;
        }
        float right = contentWidth();
        float w = (right - 2 * pad - gap * (ROW - 1)) / ROW;
        float top = getHeight() - pad - 24 * u;
        float left = pad + i * (w + gap);
        out.set(left, top, left + w, getHeight() - pad);
    }

    /** The panel itself, left of the gap and the right-hand card. */
    private float contentWidth() {
        return getWidth() - (COLUMN_U + GAP_U) * unit();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float u = unit();
        float w = contentWidth();
        fill.setColor(BG);
        rect.set(0, 0, w, getHeight());
        canvas.drawRoundRect(rect, 8 * u, 8 * u, fill);
        rect.set(getWidth() - COLUMN_U * u, 0, getWidth(), getHeight());
        canvas.drawRoundRect(rect, 8 * u, 8 * u, fill);

        // top: clock left, recording right
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(11 * u);
        text.setColor(DIM);
        text.setFakeBoldText(false);
        canvas.drawText(clock.format(new Date()), 6 * u, 16 * u, text);
        if (rec) {
            text.setTextAlign(Paint.Align.RIGHT);
            text.setColor(recording);
            canvas.drawText("REC", w - 6 * u, 16 * u, text);
            fill.setColor(recording);
            float tw = text.measureText("REC");
            canvas.drawCircle(w - 6 * u - tw - 6 * u, 12 * u, 3.5f * u, fill);
        }

        // top centre: battery at arrival
        if (arrival != null) {
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(11 * u);
            text.setColor(arrivalColor);
            text.setFakeBoldText(true);
            canvas.drawText(arrival, w / 2f, 16 * u, text);
            text.setFakeBoldText(false);
        }

        // middle: speed, unit, gear
        VehicleState s = state;
        Float speed = s == null ? null : s.speedKmh;
        String speedText = speed == null ? "--" : String.valueOf(Math.round(Math.max(0f, speed)));
        float cx = w / 2f;
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(TEXT);
        text.setFakeBoldText(true);
        text.setTextSize(40 * u);
        canvas.drawText(speedText, cx, 54 * u, text);
        text.setFakeBoldText(false);
        text.setTextSize(9 * u);
        text.setColor(DIM);
        canvas.drawText("km/h", cx, 66 * u, text);

        String gear = s == null || s.gear == null ? "" : s.gear;
        if (!gear.isEmpty()) {
            text.setTextSize(22 * u);
            text.setFakeBoldText(true);
            text.setColor(energy);
            text.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(gear, cx + 0.5f * w * 0.42f, 50 * u, text);
            text.setFakeBoldText(false);
        }

        // turn arrows either side of the speed
        Integer turn = s == null ? null : s.turnSignal;
        boolean hazard = s != null && Boolean.TRUE.equals(s.hazard);
        boolean left = hazard || (turn != null && (turn & VehicleState.TURN_LEFT) != 0);
        boolean right = hazard || (turn != null && (turn & VehicleState.TURN_RIGHT) != 0);
        drawArrow(canvas, w * 0.14f, 42 * u, 11 * u, true, left && blinkOn);
        drawArrow(canvas, w * 0.86f, 42 * u, 11 * u, false, right && blinkOn);

        // buttons
        for (int i = 0; i < ICONS.length; i++) {
            buttonRect(i, rect);
            fill.setColor(i == QUIT_BUTTON && quitArmed ? recording : BUTTON);
            canvas.drawRoundRect(rect, 5 * u, 5 * u, fill);
            Drawable d = icons[i];
            if (i == RECORD && rec) {
                // recording: a red stop square instead of the record dot
                fill.setColor(recording);
                float half = 6 * u;
                canvas.drawRoundRect(rect.centerX() - half, rect.centerY() - half, rect.centerX() + half,
                        rect.centerY() + half, 2 * u, 2 * u, fill);
            } else if (d != null) {
                d.setTint(i == DIM_BUTTON && dimOn ? energy : TEXT);
                int size = Math.round(13 * u);
                int l = Math.round(rect.centerX() - size / 2f);
                int t = Math.round(rect.centerY() - size / 2f);
                d.setBounds(l, t, l + size, t + size);
                d.draw(canvas);
            }
        }
    }

    private void drawArrow(Canvas canvas, float cx, float cy, float size, boolean pointLeft, boolean on) {
        float dir = pointLeft ? -1f : 1f;
        arrow.reset();
        arrow.moveTo(cx + dir * size, cy);
        arrow.lineTo(cx, cy - size * 0.8f);
        arrow.lineTo(cx, cy - size * 0.35f);
        arrow.lineTo(cx - dir * size * 0.8f, cy - size * 0.35f);
        arrow.lineTo(cx - dir * size * 0.8f, cy + size * 0.35f);
        arrow.lineTo(cx, cy + size * 0.35f);
        arrow.lineTo(cx, cy + size * 0.8f);
        arrow.close();
        fill.setColor(on ? TURN_ON : 0xFF2E3034);
        canvas.drawPath(arrow, fill);
    }
}
