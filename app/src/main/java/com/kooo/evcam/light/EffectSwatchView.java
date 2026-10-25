package com.kooo.evcam.light;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.kooo.evcam.R;

/**
 * A dot, or when it is laid out wide a strip of LEDs, that plays a {@link LightProtocol.Effect} the way the light does, so the effect names
 * mean something before a light is connected. Same palette as the light: Breathe and Blink use
 * the basic colour nearest to the picked one, the rainbow effects step through all seven.
 *
 * <p>Only an approximation of the light's own timing: its speed scale is not documented, so
 * {@link #periodMs} maps 0–100 onto a plausible range. Animates only while attached.</p>
 */
public final class EffectSwatchView extends View {

    /** Fill share of a strobe flash. */
    private static final float STROBE_ON = 0.25f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int offColor;
    private LightProtocol.Effect effect = LightProtocol.Effect.STATIC;
    private int color = 0xFFFFFF;
    private int speed = 50;
    private long start = SystemClock.uptimeMillis();

    public EffectSwatchView(Context context) {
        this(context, null);
    }

    public EffectSwatchView(Context context, AttributeSet attrs) {
        super(context, attrs);
        offColor = ContextCompat.getColor(context, R.color.text_tertiary);
    }

    public void show(LightLook look) {
        show(look.effect(), look.color, look.speed);
    }

    public void show(LightProtocol.Effect effect, int rgb, int speed) {
        if (effect != this.effect) {
            start = SystemClock.uptimeMillis();
        }
        this.effect = effect;
        this.color = rgb & 0xFFFFFF;
        this.speed = speed;
        invalidate();
    }

    /** One cycle: 3 s at speed 0 down to 0.4 s at 100. */
    static long periodMs(int speed) {
        int s = Math.max(0, Math.min(100, speed));
        return 3000L - s * 26L;
    }

    /** The colour {@code 0xRRGGBB} at {@code elapsedMs}, or -1 for "off" (drawn dim). */
    static int frame(LightProtocol.Effect effect, int rgb, int speed, long elapsedMs) {
        long period = periodMs(speed);
        float phase = (elapsedMs % period) / (float) period;
        long cycle = elapsedMs / period;
        int[] all = LightProtocol.BASIC_RGB;
        switch (effect) {
            case OFF:
                return -1;
            case BREATHE: {
                float level = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * phase));
                return scale(all[LightProtocol.basic(rgb)], 0.08f + 0.92f * level);
            }
            case BLINK:
                return phase < 0.5f ? all[LightProtocol.basic(rgb)] : -1;
            case FADE_ALL: {
                int from = all[(int) (cycle % all.length)];
                int to = all[(int) ((cycle + 1) % all.length)];
                return GearLightRules.mix(new LightLook(from, 100, LightProtocol.Effect.STATIC, 0),
                        new LightLook(to, 100, LightProtocol.Effect.STATIC, 0), phase).color;
            }
            case JUMP_ALL:
                return all[(int) (cycle % all.length)];
            case STROBE_ALL:
                return phase < STROBE_ON ? all[(int) (cycle % all.length)] : -1;
            default:
                return rgb;
        }
    }

    /**
     * An LED strip: a dark tape, a glow of the colour on it, and a row of LEDs. These strips are
     * one colour along their whole length, so every LED shows the same frame.
     */
    private void drawStrip(Canvas canvas, float w, float h, int lit, boolean on) {
        float corner = h / 2f;
        paint.setColor(0xFF15161A);
        canvas.drawRoundRect(0, 0, w, h, corner, corner, paint);
        if (on) {
            paint.setColor((lit & 0x00FFFFFF) | 0x40000000);
            canvas.drawRoundRect(0, 0, w, h, corner, corner, paint);
        }
        float led = h * 0.55f;
        float gap = h * 0.45f;
        int count = Math.max(1, (int) ((w - gap) / (led + gap)));
        float used = count * led + (count - 1) * gap;
        float x = (w - used) / 2f + led / 2f;
        paint.setColor(lit);
        for (int i = 0; i < count; i++) {
            canvas.drawCircle(x, h / 2f, led / 2f, paint);
            x += led + gap;
        }
    }

    private static int scale(int rgb, float level) {
        int r = Math.round(((rgb >> 16) & 0xFF) * level);
        int g = Math.round(((rgb >> 8) & 0xFF) * level);
        int b = Math.round((rgb & 0xFF) * level);
        return (r << 16) | (g << 8) | b;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int now = frame(effect, color, speed, SystemClock.uptimeMillis() - start);
        int lit = now < 0 ? (offColor & 0x00FFFFFF) | 0x50000000 : 0xFF000000 | now;
        float w = getWidth();
        float h = getHeight();
        if (w > h * 2) {
            drawStrip(canvas, w, h, lit, now >= 0);
        } else {
            paint.setColor(lit);
            canvas.drawCircle(w / 2f, h / 2f, Math.min(w, h) / 2f, paint);
        }
        if (effect != LightProtocol.Effect.STATIC && effect != LightProtocol.Effect.OFF && isAttachedToWindow()) {
            postInvalidateOnAnimation();
        }
    }
}
