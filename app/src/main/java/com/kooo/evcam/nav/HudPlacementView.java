package com.kooo.evcam.nav;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/**
 * The HUD drawn small, with what goes on it where the real HUD shows it: the turn card and, when
 * it is on, the map. Drag one to move it, drag its orange corner to change its width and height.
 * Shares of the HUD ({@link HudTurnPlace}) go to the listener as the finger moves, so the real HUD
 * can follow. Main thread.
 */
@SuppressLint("ViewConstructor")
final class HudPlacementView extends FrameLayout {

    /** Which box moved. */
    static final int CARD = 0;
    static final int MAP = 1;

    interface Listener {
        /** @param done true when the finger lifted (time to save) */
        void onPlaced(int which, float[] shares, boolean done);
    }

    private static final int GRIP_DP = 36;

    private final float aspect;
    private final Navigator.HudLine line;
    private final Listener listener;
    private final float density;
    private final Paint grip = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Per box: its shares; null for the map when it is off. */
    private final float[][] shares = new float[2][];
    private final View mapBox;
    private HudTurnCard card;
    private int cardHeight;

    private int active = -1;
    private boolean resizing;
    private float downX;
    private float downY;
    private float[] downShares;

    /**
     * @param aspect    the HUD's width / height
     * @param mapShares where the map sits, or null when the map is not on the HUD
     */
    HudPlacementView(Context context, float aspect, float[] cardShares, float[] mapShares,
                     Navigator.HudLine line, Listener listener) {
        super(context);
        this.aspect = aspect;
        this.line = line;
        this.listener = listener;
        shares[CARD] = cardShares;
        shares[MAP] = mapShares;
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
        mapBox = new MapSketch(context);
        mapBox.setVisibility(mapShares == null ? GONE : VISIBLE);
        addView(mapBox);
    }

    void setShares(int which, float[] s) {
        shares[which] = s;
        requestLayout();
    }

    /** The map switched on or off. */
    void setMap(float[] mapShares) {
        shares[MAP] = mapShares;
        mapBox.setVisibility(mapShares == null ? GONE : VISIBLE);
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
        if (shares[MAP] != null) {
            place(mapBox, shares[MAP], w, h);
        }
        int ch = Math.round(shares[CARD][3] * h);
        if (card == null || Math.abs(ch - cardHeight) > 2) {
            // the card's type scales with its height: build it again at the new one
            if (card != null) {
                removeView(card.view);
            }
            cardHeight = ch;
            card = new HudTurnCard(getContext(), Math.max(1, ch));
            card.set(line);
            addView(card.view);
        }
        place(card.view, shares[CARD], w, h);
    }

    private static void place(View v, float[] f, int w, int h) {
        int vw = Math.round(f[2] * w);
        int vh = Math.round(f[3] * h);
        v.measure(MeasureSpec.makeMeasureSpec(vw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(vh, MeasureSpec.EXACTLY));
        int x = Math.round(f[0] * w);
        int y = Math.round(f[1] * h);
        v.layout(x, y, x + vw, y + vh);
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        if (shares[MAP] != null) {
            drawGrip(c, mapBox);
        }
        if (card != null) {
            drawGrip(c, card.view);
        }
    }

    /** The resize corner, over a box's bottom right. */
    private void drawGrip(Canvas c, View v) {
        float r = v.getRight() - 6 * density;
        float b = v.getBottom() - 6 * density;
        float g = GRIP_DP * density * 0.6f;
        for (int i = 1; i <= 3; i++) {
            float t = i * g / 3f;
            c.drawLine(r - t, b, r, b - t, grip);
        }
    }

    private View viewOf(int which) {
        return which == CARD ? card.view : mapBox;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        return true;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (card == null) {
            return false;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float x = e.getX();
                float y = e.getY();
                float g = GRIP_DP * density;
                active = -1;
                // the card is on top: it wins where the two overlap
                for (int which : new int[] {CARD, MAP}) {
                    if (shares[which] == null) {
                        continue;
                    }
                    View v = viewOf(which);
                    boolean onGrip = x >= v.getRight() - g && x <= v.getRight() + g / 2
                            && y >= v.getBottom() - g && y <= v.getBottom() + g / 2;
                    boolean inside = x >= v.getLeft() && x <= v.getRight() && y >= v.getTop() && y <= v.getBottom();
                    if (onGrip || inside) {
                        active = which;
                        resizing = onGrip;
                        break;
                    }
                }
                downX = x;
                downY = y;
                downShares = active < 0 ? null : shares[active].clone();
                getParent().requestDisallowInterceptTouchEvent(true);
                return active >= 0;
            }
            case MotionEvent.ACTION_MOVE: {
                if (active < 0) {
                    return true;
                }
                float dx = (e.getX() - downX) / getWidth();
                float dy = (e.getY() - downY) / getHeight();
                float[] f = downShares;
                // resizing keeps the top-left corner where it is
                shares[active] = resizing
                        ? HudTurnPlace.clamp(f[0], f[1], Math.min(1f - f[0], f[2] + dx), Math.min(1f - f[1], f[3] + dy))
                        : HudTurnPlace.clamp(f[0] + dx, f[1] + dy, f[2], f[3]);
                requestLayout();
                listener.onPlaced(active, shares[active], false);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (active >= 0) {
                    listener.onPlaced(active, shares[active], true);
                }
                active = -1;
                resizing = false;
                return true;
            default:
                return true;
        }
    }

    /** A stand-in for the HUD map: dark streets and a blue route, with the car at the bottom. */
    private static final class MapSketch extends View {
        private final Paint road = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint route = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint car = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        MapSketch(Context context) {
            super(context);
            float d = context.getResources().getDisplayMetrics().density;
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF1D2026);
            bg.setCornerRadius(8 * d);
            bg.setStroke(Math.round(1.5f * d), 0xFF3A3F47);
            setBackground(bg);
            road.setColor(0xFF363B44);
            road.setStrokeWidth(5 * d);
            route.setColor(0xFF4285F4);
            route.setStrokeWidth(6 * d);
            route.setStyle(Paint.Style.STROKE);
            route.setStrokeCap(Paint.Cap.ROUND);
            route.setStrokeJoin(Paint.Join.ROUND);
            car.setColor(Color.WHITE);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            c.drawLine(w * 0.5f, h, w * 0.5f, 0, road);
            c.drawLine(0, h * 0.35f, w, h * 0.35f, road);
            c.drawLine(w * 0.15f, h, w * 0.85f, 0, road);
            path.reset();
            path.moveTo(w * 0.5f, h * 0.85f);
            path.lineTo(w * 0.5f, h * 0.35f);
            path.lineTo(w * 0.9f, h * 0.35f);
            c.drawPath(path, route);
            c.drawCircle(w * 0.5f, h * 0.85f, Math.min(w, h) * 0.05f, car);
        }
    }
}
