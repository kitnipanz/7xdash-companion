package com.kooo.evcam.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.kooo.evcam.R;
import com.kooo.evcam.input.ShortcutAction;

/**
 * The Macro pad's picture: {@link MacroPadSlots#COLUMNS} x {@link MacroPadSlots#ROWS} buttons, each
 * the action's icon with its name under it; an empty button shows a plus. Drawn to scale, so a pinch
 * makes everything bigger or smaller together. Always dark, like the floating buttons.
 */
public final class MacroPadView extends View {

    /** Width : height of the pad. */
    public static final float ASPECT = 1.72f;

    private static final int BG = 0xE6141518;
    private static final int BUTTON = 0xFF26282C;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int DIM = 0xFF8E9096;
    private static final float PAD = 4f;
    private static final float GAP = 4f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Drawable[] icons = new Drawable[MacroPadSlots.COUNT];
    private final String[] labels = new String[MacroPadSlots.COUNT];
    private final Drawable plus;
    private final int pressedColor;
    private int pressed = -1;

    public MacroPadView(Context context) {
        super(context);
        pressedColor = ContextCompat.getColor(context, R.color.energy);
        plus = tinted(context, R.drawable.ic_add, DIM);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        // StaticLayout centres each line itself; LEFT keeps the paint from shifting it again
        text.setTextAlign(Paint.Align.LEFT);
    }

    /** Shows these action keys ("" = empty). */
    public void setSlots(String[] slots) {
        Context context = getContext();
        for (int i = 0; i < MacroPadSlots.COUNT; i++) {
            ShortcutAction action = ShortcutAction.fromKey(i < slots.length ? slots[i] : "");
            icons[i] = action == null ? null : tinted(context, action.iconRes, TEXT);
            labels[i] = action == null ? null : context.getString(action.labelRes);
        }
        invalidate();
    }

    /** Lights button {@code i} for a moment, so a tap is seen to land. */
    public void flash(int i) {
        pressed = i;
        invalidate();
        postDelayed(() -> {
            pressed = -1;
            invalidate();
        }, 180);
    }

    /** Which button a touch (screen coordinates) hit, or -1. */
    public int buttonAt(MotionEvent up) {
        int[] at = new int[2];
        getLocationOnScreen(at);
        float x = up.getRawX() - at[0];
        float y = up.getRawY() - at[1];
        for (int i = 0; i < MacroPadSlots.COUNT; i++) {
            buttonRect(i, rect);
            if (rect.contains(x, y)) {
                return i;
            }
        }
        return -1;
    }

    private float unit() {
        return getHeight() / 100f;
    }

    private void buttonRect(int i, RectF out) {
        float u = unit();
        int cols = MacroPadSlots.COLUMNS;
        int rows = MacroPadSlots.ROWS;
        float w = (getWidth() - 2 * PAD * u - (cols - 1) * GAP * u) / cols;
        float h = (getHeight() - 2 * PAD * u - (rows - 1) * GAP * u) / rows;
        float left = PAD * u + (i % cols) * (w + GAP * u);
        float top = PAD * u + (i / cols) * (h + GAP * u);
        out.set(left, top, left + w, top + h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float u = unit();
        fill.setColor(BG);
        rect.set(0, 0, getWidth(), getHeight());
        canvas.drawRoundRect(rect, 8 * u, 8 * u, fill);
        text.setTextSize(8.5f * u);
        for (int i = 0; i < MacroPadSlots.COUNT; i++) {
            buttonRect(i, rect);
            fill.setColor(i == pressed ? pressedColor : BUTTON);
            canvas.drawRoundRect(rect, 6 * u, 6 * u, fill);
            float cx = rect.centerX();
            Drawable icon = icons[i];
            if (icon == null) {
                draw(canvas, plus, cx, rect.centerY(), 16 * u);
                continue;
            }
            draw(canvas, icon, cx, rect.top + rect.height() * 0.30f, 15 * u);
            text.setColor(TEXT);
            // Up to two lines under the icon, so names like "Recording start/stop" fit
            int width = Math.max(1, Math.round(rect.width() - 6 * u));
            StaticLayout label = StaticLayout.Builder.obtain(labels[i], 0, labels[i].length(), text, width)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setIncludePad(false)
                    .build();
            canvas.save();
            canvas.translate(rect.left + 3 * u, rect.top + rect.height() * 0.52f);
            label.draw(canvas);
            canvas.restore();
        }
    }

    private static void draw(Canvas canvas, Drawable d, float cx, float cy, float size) {
        if (d == null) {
            return;
        }
        int half = Math.round(size / 2f);
        d.setBounds(Math.round(cx) - half, Math.round(cy) - half, Math.round(cx) + half, Math.round(cy) + half);
        d.draw(canvas);
    }

    private static Drawable tinted(Context context, int res, int color) {
        Drawable d = ContextCompat.getDrawable(context, res);
        if (d != null) {
            d = d.mutate();
            d.setTint(color);
        }
        return d;
    }
}
