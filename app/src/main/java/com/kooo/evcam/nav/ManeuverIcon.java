package com.kooo.evcam.nav;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;

/**
 * The turn arrow, drawn rather than taken from icons: one drawing for every maneuver, at any size,
 * on the screen and on the HUD. Left turns are the right ones mirrored.
 */
public final class ManeuverIcon {

    private ManeuverIcon() {
    }

    /** The arrow's path in a 1 x 1 box, pointing right for turns; {@code null} for the destination flag. */
    private static float[] line(Maneuver m) {
        switch (m) {
            case TURN_SLIGHT_LEFT:
            case TURN_SLIGHT_RIGHT:
            case RAMP_LEFT:
            case RAMP_RIGHT:
            case FORK_LEFT:
            case FORK_RIGHT:
                return new float[]{0.42f, 0.95f, 0.42f, 0.55f, 0.75f, 0.2f};
            case TURN_LEFT:
            case TURN_RIGHT:
                return new float[]{0.32f, 0.95f, 0.32f, 0.45f, 0.86f, 0.45f};
            case TURN_SHARP_LEFT:
            case TURN_SHARP_RIGHT:
                return new float[]{0.32f, 0.95f, 0.32f, 0.2f, 0.78f, 0.7f};
            case UTURN_LEFT:
            case UTURN_RIGHT:
                return new float[]{0.3f, 0.95f, 0.3f, 0.3f, 0.5f, 0.12f, 0.7f, 0.3f, 0.7f, 0.68f};
            case ARRIVE:
                return null;
            default:
                return new float[]{0.5f, 0.95f, 0.5f, 0.1f};
        }
    }

    /** Draws the arrow for {@code m} filling {@code box}. */
    public static void draw(Canvas canvas, RectF box, Maneuver m, int color) {
        float size = Math.min(box.width(), box.height());
        float left = box.centerX() - size / 2;
        float top = box.centerY() - size / 2;
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setColor(color);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(size * 0.13f);
        stroke.setStrokeCap(Paint.Cap.BUTT);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(color);
        fill.setStyle(Paint.Style.FILL);

        canvas.save();
        if (m.left()) {
            canvas.scale(-1, 1, box.centerX(), box.centerY());
        }
        if (m == Maneuver.ARRIVE) {
            flag(canvas, left, top, size, stroke, fill);
            canvas.restore();
            return;
        }
        if (m == Maneuver.ROUNDABOUT_LEFT || m == Maneuver.ROUNDABOUT_RIGHT) {
            Paint ring = new Paint(stroke);
            ring.setStrokeWidth(size * 0.08f);
            ring.setAlpha(150);
            canvas.drawCircle(left + 0.5f * size, top + 0.5f * size, 0.2f * size, ring);
        }
        if (m == Maneuver.RAMP_LEFT || m == Maneuver.RAMP_RIGHT || m == Maneuver.FORK_LEFT || m == Maneuver.FORK_RIGHT) {
            // the road not taken, faint
            Paint ghost = new Paint(stroke);
            ghost.setAlpha(90);
            canvas.drawLine(left + 0.42f * size, top + 0.55f * size, left + 0.42f * size, top + 0.12f * size, ghost);
        }
        float[] p = line(m);
        Path path = new Path();
        path.moveTo(left + p[0] * size, top + p[1] * size);
        int n = p.length / 2;
        // stop the shaft short of the tip so the head's point stays sharp
        float ex = p[2 * n - 2];
        float ey = p[2 * n - 1];
        float dx = ex - p[2 * n - 4];
        float dy = ey - p[2 * n - 3];
        float len = (float) Math.hypot(dx, dy);
        float ux = dx / len;
        float uy = dy / len;
        float head = 0.26f;
        for (int i = 1; i < n - 1; i++) {
            path.lineTo(left + p[2 * i] * size, top + p[2 * i + 1] * size);
        }
        path.lineTo(left + (ex - ux * head * 0.6f) * size, top + (ey - uy * head * 0.6f) * size);
        canvas.drawPath(path, stroke);

        Path tip = new Path();
        float bx = ex - ux * head;
        float by = ey - uy * head;
        float half = head * 0.62f;
        tip.moveTo(left + ex * size, top + ey * size);
        tip.lineTo(left + (bx - uy * half) * size, top + (by + ux * half) * size);
        tip.lineTo(left + (bx + uy * half) * size, top + (by - ux * half) * size);
        tip.close();
        canvas.drawPath(tip, fill);
        canvas.restore();
    }

    private static void flag(Canvas canvas, float left, float top, float size, Paint stroke, Paint fill) {
        canvas.drawLine(left + 0.3f * size, top + 0.95f * size, left + 0.3f * size, top + 0.1f * size, stroke);
        Path flag = new Path();
        flag.moveTo(left + 0.3f * size, top + 0.1f * size);
        flag.lineTo(left + 0.82f * size, top + 0.3f * size);
        flag.lineTo(left + 0.3f * size, top + 0.5f * size);
        flag.close();
        canvas.drawPath(flag, fill);
    }

    /** A view that shows one maneuver's arrow. */
    public static final class ArrowView extends android.view.View {
        private final RectF box = new RectF();
        private Maneuver maneuver = Maneuver.STRAIGHT;
        private int color = Color.WHITE;

        public ArrowView(Context context) {
            super(context);
        }

        public ArrowView(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        public void set(Maneuver m, int color) {
            if (m != maneuver || color != this.color) {
                maneuver = m;
                this.color = color;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            box.set(getPaddingLeft(), getPaddingTop(), getWidth() - getPaddingRight(), getHeight() - getPaddingBottom());
            ManeuverIcon.draw(canvas, box, maneuver, color);
        }
    }
}
