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
 * a row of four buttons at the bottom ({@link #buttonAt}). On the right, a card of its own with minimize
 * and what the floating button used to carry (record, dim, quit), one above the other.
 *
 * <p>Minimized ({@link #setMinimized}) it is a small pill instead: turn arrows, speed and gear, and a
 * red dot while recording, with the right-hand card's buttons in a row beside it (expand, record,
 * dim, quit); at the pill's height a column of four would be too small to hit. A tap on the pill or
 * on expand brings the panel back.</p>
 *
 * <p>Always dark, like an instrument cluster, whatever the app's day / night theme.</p>
 */
public final class DashPanelView extends View {

    /** Width : height of the whole window: the 2 : 1 panel, the gap and the right-hand card. */
    public static final float ASPECT = 2.34f;
    /** The four buttons along the bottom, left to right, then the column on the right, top to bottom. */
    public static final int[] ICONS = {R.drawable.ic_surround, R.drawable.ic_flash, R.drawable.ic_rearview,
            R.drawable.ic_home, R.drawable.ic_collapse, R.drawable.ic_record, R.drawable.ic_dim, R.drawable.ic_power};
    /** Buttons in the bottom row; the rest are the column. */
    public static final int ROW = 4;
    /** {@link #buttonAt} for the minimize button, the first of the column. */
    public static final int MINIMIZE = ROW;
    /** {@link #buttonAt} for the record button. */
    public static final int RECORD = ROW + 1;
    /** {@link #buttonAt} for the dim button: tap turns it on or off, slide sets how dark. */
    public static final int DIM_BUTTON = ROW + 2;
    /** {@link #buttonAt} for the quit button: the first tap arms it, the second quits. */
    public static final int QUIT_BUTTON = ROW + 3;
    /** Minimized: width of the pill, of the gap after it, of the button card's padding, buttons and gaps between them, in hundredths of the height. */
    private static final float PILL_U = 300f;
    private static final float MINI_GAP_U = 6f;
    private static final float MINI_PAD_U = 8f;
    private static final float MINI_BUTTON_U = 84f;
    private static final float MINI_BUTTON_GAP_U = 6f;
    /** Width : height of the minimized window: the pill, the gap and the card of four buttons. */
    public static final float MINI_ASPECT = (PILL_U + MINI_GAP_U + 2 * MINI_PAD_U
            + 4 * MINI_BUTTON_U + 3 * MINI_BUTTON_GAP_U) / 100f;
    /** Stacked (no Macro pad): the four buttons in a row under the pill, as wide as it. */
    private static final float STACK_BUTTON_U = (PILL_U - 2 * MINI_PAD_U - 3 * MINI_BUTTON_GAP_U) / 4f;
    /** Height : pill height of the stacked minimized window: the pill, the gap and the row card. */
    public static final float STACK_HEIGHT = (100f + MINI_GAP_U + 2 * MINI_PAD_U + STACK_BUTTON_U) / 100f;
    /** Height of the minimized pill, as a share of the full panel's height. */
    public static final float MINI_SCALE = 0.32f;
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
    /** On the minimize button while minimized: maximize, lit, so the way back to the panel is plain to see. */
    private final Drawable expandIcon;
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
    private boolean minimized;
    /** Minimized with the buttons under the pill rather than beside it. */
    private boolean stacked;

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
        Drawable expand = ContextCompat.getDrawable(context, R.drawable.ic_maximize);
        if (expand != null) {
            expand = expand.mutate();
            expand.setTint(TEXT);
        }
        expandIcon = expand;
        setContentDescription(context.getString(R.string.dash_panel_minimize));
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

    /**
     * The small pill (true) or the whole panel. Minimized, {@code stacked} puts the buttons in a row
     * under the pill, as wide as it (no Macro pad), instead of beside it (the pad docks under both).
     * The window's size is the service's business ({@link #miniSize}).
     */
    public void setMinimized(boolean minimized, boolean stacked) {
        if (this.minimized != minimized || this.stacked != stacked) {
            this.minimized = minimized;
            this.stacked = stacked;
            setContentDescription(getContext().getString(
                    minimized ? R.string.dash_panel_expand : R.string.dash_panel_minimize));
            invalidate();
        }
    }

    /** The minimized window's size {width, height} for a full panel {@code fullHeight} tall, pill at least {@code minHeight}. */
    public static int[] miniSize(int fullHeight, int minHeight, boolean stacked) {
        int h = Math.max(minHeight, Math.round(fullHeight * MINI_SCALE));
        return stacked
                ? new int[]{Math.round(h * PILL_U / 100f), Math.round(h * STACK_HEIGHT)}
                : new int[]{Math.round(h * MINI_ASPECT), h};
    }

    /** The pill's height in a minimized window {@code width} wide. */
    public static int pillHeight(int width, boolean stacked) {
        return Math.round(width / (stacked ? PILL_U / 100f : MINI_ASPECT));
    }

    /** The battery at arrival line at the top, or null for none. */
    public void arrival(String line, int color) {
        this.arrival = line;
        this.arrivalColor = color;
    }

    /** Which button a tap at (x, y) in this view hit, or -1. */
    public int buttonAt(float x, float y) {
        for (int i = minimized ? ROW : 0; i < ICONS.length; i++) {
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

    /** A hundredth of the panel's height, or minimized, of the pill's. */
    private float unit() {
        if (minimized && stacked) {
            return getWidth() / PILL_U;
        }
        return getHeight() / 100f;
    }

    private void buttonRect(int i, RectF out) {
        float u = unit();
        if (minimized && stacked) {
            // the right-hand card's buttons, in a row under the pill
            float left = (MINI_PAD_U + (i - ROW) * (STACK_BUTTON_U + MINI_BUTTON_GAP_U)) * u;
            float top = (100f + MINI_GAP_U + MINI_PAD_U) * u;
            out.set(left, top, left + STACK_BUTTON_U * u, top + STACK_BUTTON_U * u);
            return;
        }
        if (minimized) {
            // the right-hand card's buttons, in a row right of the pill
            float left = (PILL_U + MINI_GAP_U + MINI_PAD_U + (i - ROW) * (MINI_BUTTON_U + MINI_BUTTON_GAP_U)) * u;
            out.set(left, MINI_PAD_U * u, left + MINI_BUTTON_U * u, getHeight() - MINI_PAD_U * u);
            return;
        }
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
        if (minimized) {
            drawMini(canvas);
            return;
        }
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
            drawButton(canvas, i, u);
        }
    }

    /** Button {@code i}, sized in {@code u}: the full panel's hundredth of its height. */
    private void drawButton(Canvas canvas, int i, float u) {
        buttonRect(i, rect);
        fill.setColor(i == QUIT_BUTTON && quitArmed ? recording : BUTTON);
        canvas.drawRoundRect(rect, 5 * u, 5 * u, fill);
        Drawable d = minimized && i == MINIMIZE ? expandIcon : icons[i];
        if (i == RECORD && rec) {
            // recording: a red stop square instead of the record dot
            fill.setColor(recording);
            float half = 6 * u;
            canvas.drawRoundRect(rect.centerX() - half, rect.centerY() - half, rect.centerX() + half,
                    rect.centerY() + half, 2 * u, 2 * u, fill);
        } else if (d != null) {
            // the size control (minimize, or maximize when minimized) is always lit, so it reads as one
            d.setTint((i == DIM_BUTTON && dimOn) || i == MINIMIZE ? energy : TEXT);
            int size = Math.round(13 * u);
            int l = Math.round(rect.centerX() - size / 2f);
            int t = Math.round(rect.centerY() - size / 2f);
            d.setBounds(l, t, l + size, t + size);
            d.draw(canvas);
        }
    }

    /**
     * The pill (left arrow, speed, gear, right arrow, recording dot) and right of it, or under it
     * when stacked, the card of buttons. Scales with the pill's height like the panel.
     */
    private void drawMini(Canvas canvas) {
        float u = unit();
        float w = PILL_U * u;
        float h = 100f * u;
        fill.setColor(BG);
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, h / 2f, h / 2f, fill);
        if (stacked) {
            rect.set(0, h + MINI_GAP_U * u, w, getHeight());
        } else {
            rect.set(w + MINI_GAP_U * u, 0, getWidth(), h);
        }
        canvas.drawRoundRect(rect, 16 * u, 16 * u, fill);
        // buttons sized like the full panel's (its u is this one over MINI_SCALE), a bit smaller stacked
        float bu = u / MINI_SCALE * (stacked ? STACK_BUTTON_U / MINI_BUTTON_U : 1f);
        for (int i = ROW; i < ICONS.length; i++) {
            drawButton(canvas, i, bu);
        }

        VehicleState s = state;
        Integer turn = s == null ? null : s.turnSignal;
        boolean hazard = s != null && Boolean.TRUE.equals(s.hazard);
        boolean left = hazard || (turn != null && (turn & VehicleState.TURN_LEFT) != 0);
        boolean right = hazard || (turn != null && (turn & VehicleState.TURN_RIGHT) != 0);
        drawArrow(canvas, w * 0.13f, 50 * u, 20 * u, true, left && blinkOn);
        drawArrow(canvas, w * 0.78f, 50 * u, 20 * u, false, right && blinkOn);

        Float speed = s == null ? null : s.speedKmh;
        String speedText = speed == null ? "--" : String.valueOf(Math.round(Math.max(0f, speed)));
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(TEXT);
        text.setFakeBoldText(true);
        text.setTextSize(56 * u);
        canvas.drawText(speedText, w * 0.43f, 70 * u, text);

        String gear = s == null || s.gear == null ? "" : s.gear;
        if (!gear.isEmpty()) {
            text.setTextSize(36 * u);
            text.setColor(energy);
            text.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(gear, w * 0.62f, 64 * u, text);
        }
        text.setFakeBoldText(false);

        if (rec) {
            fill.setColor(recording);
            canvas.drawCircle(w - 30 * u, 50 * u, 8 * u, fill);
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
