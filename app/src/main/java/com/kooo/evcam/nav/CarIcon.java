package com.kooo.evcam.nav;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * The car on the map: a Zeekr 7X seen from above, nose up, after Zeekr's own top view. A long body
 * with a short sloping bonnet, almost all of the top one dark glass roof framed by a bright trim
 * that sweeps round the back, small mirrors, the light bar across the nose and the red bar across
 * the tail; in the colour the owner picked ({@link Color}). A soft shadow keeps it visible on light
 * and dark maps.
 */
final class CarIcon {

    /** The paint colours: the body's edge and its lit middle. */
    enum Color {
        FOREST_GREEN(0xFF2B4A39, 0xFF5E8C70),
        SILVER(0xFF6F7680, 0xFFB8BEC6),
        BLACK(0xFF14161A, 0xFF40454D),
        WHITE(0xFFE4E7EB, 0xFFFFFFFF);

        final int edge;
        final int light;

        Color(int edge, int light) {
            this.edge = edge;
            this.light = light;
        }

        static Color of(int index) {
            Color[] all = values();
            return index >= 0 && index < all.length ? all[index] : FOREST_GREEN;
        }
    }

    private CarIcon() {
    }

    static Bitmap zeekr7x(float density, Color color) {
        int w = Math.round(44 * density);
        int h = Math.round(84 * density);
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        float l = w * 0.16f;
        float r = w * 0.84f;
        float top = h * 0.06f;
        float bottom = h * 0.95f;
        float bw = r - l;
        Path body = body(l, r, top, bottom, bw);

        // a soft shadow under the car
        p.setColor(0x66000000);
        p.setMaskFilter(new BlurMaskFilter(4 * density, BlurMaskFilter.Blur.NORMAL));
        c.save();
        c.translate(0, 1.5f * density);
        c.drawPath(body, p);
        c.restore();
        p.setMaskFilter(null);

        // the paint, lighter down the middle like the photo
        p.setShader(new LinearGradient(l, 0, r, 0, new int[] {color.edge, color.light, color.edge},
                new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(body, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1f * density);
        // a light edge on the dark colours, a dark one on the light colours
        p.setColor(color == Color.BLACK || color == Color.FOREST_GREEN ? 0xCCDDE2E8 : 0xCC2A2E35);
        c.drawPath(body, p);
        p.setStyle(Paint.Style.FILL);

        // mirrors, just behind where the glass starts
        p.setColor(color.edge);
        float my = top + (bottom - top) * 0.29f;
        c.drawRoundRect(new RectF(l - bw * 0.13f, my, l + bw * 0.02f, my + h * 0.03f), 2 * density, 2 * density, p);
        c.drawRoundRect(new RectF(r - bw * 0.02f, my, r + bw * 0.13f, my + h * 0.03f), 2 * density, 2 * density, p);

        // the bright trim round the glass
        float gl = l + bw * 0.1f;
        float gr = r - bw * 0.1f;
        float gTop = top + (bottom - top) * 0.25f;
        float gBottom = bottom - (bottom - top) * 0.07f;
        RectF trim = new RectF(gl, gTop, gr, gBottom);
        p.setColor(0xFFD9DDE3);
        c.drawRoundRect(trim, bw * 0.3f, bw * 0.24f, p);

        // the glass: windscreen and the long panoramic roof in one, dark with a sheen
        RectF glass = new RectF(gl + bw * 0.05f, gTop + h * 0.006f, gr - bw * 0.05f, gBottom - h * 0.02f);
        p.setShader(new LinearGradient(0, glass.top, 0, glass.bottom, new int[] {0xFF2B3442, 0xFF0D1015, 0xFF1A202A},
                new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRoundRect(glass, bw * 0.26f, bw * 0.2f, p);
        p.setShader(null);

        // light bar across the nose, red bar across the tail
        p.setColor(0xFFEFF8FF);
        c.drawRoundRect(new RectF(l + bw * 0.14f, top + h * 0.012f, r - bw * 0.14f, top + h * 0.027f), density, density, p);
        p.setColor(0xFFE53935);
        c.drawRoundRect(new RectF(l + bw * 0.08f, bottom - h * 0.022f, r - bw * 0.08f, bottom - h * 0.008f), density, density, p);
        return bitmap;
    }

    /** The outline: a rounded nose, straight flanks, a rounded tail. */
    private static Path body(float l, float r, float top, float bottom, float bw) {
        Path b = new Path();
        float nose = (bottom - top) * 0.12f;
        float tail = (bottom - top) * 0.06f;
        b.moveTo(l + bw * 0.2f, top);
        b.quadTo((l + r) / 2f, top - nose * 0.12f, r - bw * 0.2f, top);
        b.cubicTo(r - bw * 0.02f, top + nose * 0.2f, r, top + nose * 0.6f, r, top + nose * 1.4f);
        b.lineTo(r, bottom - tail);
        b.quadTo(r, bottom, r - bw * 0.18f, bottom);
        b.lineTo(l + bw * 0.18f, bottom);
        b.quadTo(l, bottom, l, bottom - tail);
        b.lineTo(l, top + nose * 1.4f);
        b.cubicTo(l, top + nose * 0.6f, l + bw * 0.02f, top + nose * 0.2f, l + bw * 0.2f, top);
        b.close();
        return b;
    }
}
