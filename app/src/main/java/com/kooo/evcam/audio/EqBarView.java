package com.kooo.evcam.audio;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.kooo.evcam.R;

/**
 * 均衡器的一段：竖着拖的推子，和调音台一样 —— 往上加、往下减，中间那条线是 0 dB。
 *
 * <p>Material 的 Slider 只有横的，横着排十五条看起来像进度条，不像均衡器。
 * 这里自己画：一条细槽、0 dB 的刻线、从 0 到当前值的一段橙色、一个圆钮。
 * 整列都能按，手指上下拖就改，按 1 dB 一档对齐。</p>
 */
public class EqBarView extends View {

    /** 值变了。{@code fromUser} 为假是代码里设的（换预设、恢复平直）。 */
    public interface OnChange {
        void onChange(float value, boolean fromUser);
    }

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zero = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private float value;
    private OnChange listener;

    public EqBarView(Context context) {
        this(context, null);
    }

    public EqBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        track.setColor(ContextCompat.getColor(context, R.color.line));
        fill.setColor(ContextCompat.getColor(context, R.color.energy));
        zero.setColor(ContextCompat.getColor(context, R.color.text_tertiary));
        zero.setStrokeWidth(2 * density);
        thumb.setColor(ContextCompat.getColor(context, R.color.text_primary));
    }

    public void setOnChange(OnChange listener) {
        this.listener = listener;
    }

    public float getValue() {
        return value;
    }

    public void setValue(float v) {
        set(v, false);
    }

    private void set(float v, boolean fromUser) {
        float snapped = Math.round(CabinEq.clamp(v));
        if (snapped == value) {
            return;
        }
        value = snapped;
        invalidate();
        if (listener != null) {
            listener.onChange(value, fromUser);
        }
    }

    /** 圆钮上下各留半个钮的余量，±12 dB 时钮不会被裁掉。 */
    private float inset() {
        return 14 * density;
    }

    private float yFor(float v) {
        float top = inset();
        float bottom = getHeight() - inset();
        float t = (v - CabinEq.GAIN_MIN) / (CabinEq.GAIN_MAX - CabinEq.GAIN_MIN);
        return bottom - t * (bottom - top);
    }

    private float valueFor(float y) {
        float top = inset();
        float bottom = getHeight() - inset();
        float t = (bottom - y) / Math.max(1f, bottom - top);
        return CabinEq.GAIN_MIN + t * (CabinEq.GAIN_MAX - CabinEq.GAIN_MIN);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float half = 3 * density;
        rect.set(cx - half, inset(), cx + half, getHeight() - inset());
        canvas.drawRoundRect(rect, half, half, track);

        float y0 = yFor(0);
        float y = yFor(value);
        canvas.drawLine(cx - 10 * density, y0, cx + 10 * density, y0, zero);
        rect.set(cx - half, Math.min(y, y0), cx + half, Math.max(y, y0));
        canvas.drawRoundRect(rect, half, half, fill);

        canvas.drawCircle(cx, y, 11 * density, fill);
        canvas.drawCircle(cx, y, 5 * density, thumb);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                set(valueFor(event.getY()), true);
                return true;
            case MotionEvent.ACTION_MOVE:
                set(valueFor(event.getY()), true);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }
}
