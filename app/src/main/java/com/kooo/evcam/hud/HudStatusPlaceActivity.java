package com.kooo.evcam.hud;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.zeekr.HudDisplay;

/**
 * Where the HUD status card sits: the HUD drawn small, the card on it. Drag the card to move it,
 * drag its orange corner to resize it; the real HUD follows as the finger moves. The parts of the
 * HUD the driver can't see (Settings' left and right edges) are shaded.
 */
public class HudStatusPlaceActivity extends AppCompatActivity {

    private AppConfig config;
    private Placer placer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        config = new AppConfig(this);
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * density);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(ContextCompat.getColor(this, R.color.bg));
        page.setPadding(pad, pad, pad, pad);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_back);
        back.setBackground(null);
        back.setColorFilter(ContextCompat.getColor(this, R.color.text_primary));
        back.setContentDescription(getString(R.string.action_back));
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(Math.round(48 * density), Math.round(48 * density)));
        TextView title = new TextView(this);
        title.setText(R.string.hud_status_place_title);
        title.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tl.leftMargin = Math.round(14 * density);
        bar.addView(title, tl);
        Button reset = new Button(this);
        reset.setText(R.string.hud_status_reset);
        reset.setOnClickListener(v -> {
            config.setHudStatusPlace("");
            float[] f = HudStatus.defaults(config.getHudNoticeRightPercent());
            if (placer == null) {
                return;
            }
            placer.setShares(f);
            HudStatusWindow.get().place(f);
        });
        bar.addView(reset);
        page.addView(bar);

        TextView hint = new TextView(this);
        hint.setText(R.string.hud_status_place_summary);
        hint.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        hint.setPadding(0, pad / 2, 0, pad);
        page.addView(hint);

        Display hud = HudDisplay.find(this);
        if (hud == null) {
            TextView none = new TextView(this);
            none.setText(R.string.hud_status_no_hud);
            none.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
            page.addView(none);
            setContentView(page);
            return;
        }
        Point size = HudDisplay.sizeOf(hud);
        float[] start = HudStatus.placeOr(config.getHudStatusPlace(), config.getHudNoticeRightPercent());
        placer = new Placer(this, (float) size.x / Math.max(1, size.y), start,
                config.getHudNoticeLeftPercent() / 100f, config.getHudNoticeRightPercent() / 100f,
                (shares, done) -> {
                    HudStatusWindow.get().place(shares);
                    if (done) {
                        config.setHudStatusPlace(HudStatus.store(shares));
                    }
                });
        page.addView(placer, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(page);
    }

    @Override
    protected void onResume() {
        super.onResume();
        HudStatusWindow.get().setPreviewing(this, true);
    }

    @Override
    protected void onPause() {
        HudStatusWindow.get().setPreviewing(this, false);
        super.onPause();
    }

    private interface Listener {
        /** @param done true when the finger lifted (time to save) */
        void onPlaced(float[] shares, boolean done);
    }

    /** The HUD drawn small with the card on it. */
    @SuppressLint("ViewConstructor")
    private static final class Placer extends FrameLayout {
        private static final int GRIP_DP = 36;

        private final float aspect;
        private final float hiddenLeft;
        private final float hiddenRight;
        private final Listener listener;
        private final float density;
        private final Paint grip = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shade = new Paint();
        private float[] shares;
        private HudStatusWindow.Card card;
        private int cardHeight;

        private boolean active;
        private boolean resizing;
        private float downX;
        private float downY;
        private float[] downShares;

        Placer(Context context, float aspect, float[] shares, float hiddenLeft, float hiddenRight, Listener listener) {
            super(context);
            this.aspect = aspect;
            this.shares = shares;
            this.hiddenLeft = hiddenLeft;
            this.hiddenRight = hiddenRight;
            this.listener = listener;
            density = context.getResources().getDisplayMetrics().density;
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF000000);
            bg.setCornerRadius(10 * density);
            bg.setStroke(Math.round(2 * density), 0xFF5A5C61);
            setBackground(bg);
            setWillNotDraw(false);
            grip.setColor(0xFFF0622D);
            grip.setStrokeWidth(3 * density);
            grip.setStrokeCap(Paint.Cap.ROUND);
            shade.setColor(0x33FFFFFF);
        }

        void setShares(float[] s) {
            shares = s;
            requestLayout();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int w = MeasureSpec.getSize(widthSpec);
            int h = Math.round(w / aspect);
            super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int w = r - l;
            int h = b - t;
            int vw = Math.round(shares[2] * w);
            int vh = Math.round(shares[3] * h);
            if (card == null || Math.abs(vh - cardHeight) > 2) {
                if (card == null) {
                    card = new HudStatusWindow.Card(getContext(), Math.max(1, vh));
                    card.show(HudStatusWindow.Card.sample(getContext()));
                    addView(card.view);
                } else {
                    card.setHeight(Math.max(1, vh));
                }
                cardHeight = vh;
            }
            card.view.measure(MeasureSpec.makeMeasureSpec(vw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(vh, MeasureSpec.AT_MOST));
            int x = Math.round(shares[0] * w);
            int y = Math.round(shares[1] * h);
            card.view.layout(x, y, x + vw, y + card.view.getMeasuredHeight());
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            // what the driver can't see on the car
            c.drawRect(0, 0, hiddenLeft * getWidth(), getHeight(), shade);
            c.drawRect(getWidth() * (1f - hiddenRight), 0, getWidth(), getHeight(), shade);
        }

        @Override
        protected void dispatchDraw(Canvas c) {
            super.dispatchDraw(c);
            // the box is the card's whole height: show its bottom edge as the grip's place
            float r = shares[0] * getWidth() + shares[2] * getWidth() - 6 * density;
            float b = shares[1] * getHeight() + shares[3] * getHeight() - 6 * density;
            float g = GRIP_DP * density * 0.6f;
            for (int i = 1; i <= 3; i++) {
                float t = i * g / 3f;
                c.drawLine(r - t, b, r, b - t, grip);
            }
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            return true;
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float w = getWidth();
            float h = getHeight();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    float x = e.getX();
                    float y = e.getY();
                    float left = shares[0] * w;
                    float top = shares[1] * h;
                    float right = left + shares[2] * w;
                    float bottom = top + shares[3] * h;
                    float g = GRIP_DP * density;
                    boolean onGrip = x >= right - g && x <= right + g / 2 && y >= bottom - g && y <= bottom + g / 2;
                    boolean inside = x >= left && x <= right && y >= top && y <= bottom;
                    active = onGrip || inside;
                    resizing = onGrip;
                    downX = x;
                    downY = y;
                    downShares = shares.clone();
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return active;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (!active) {
                        return true;
                    }
                    float dx = (e.getX() - downX) / w;
                    float dy = (e.getY() - downY) / h;
                    float[] f = downShares;
                    // resizing keeps the top-left corner where it is
                    shares = resizing
                            ? HudStatus.clamp(f[0], f[1], Math.min(1f - f[0], f[2] + dx), Math.min(1f - f[1], f[3] + dy))
                            : HudStatus.clamp(f[0] + dx, f[1] + dy, f[2], f[3]);
                    requestLayout();
                    invalidate();
                    listener.onPlaced(shares, false);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (active) {
                        listener.onPlaced(shares, true);
                    }
                    active = false;
                    resizing = false;
                    return true;
                default:
                    return true;
            }
        }
    }
}
