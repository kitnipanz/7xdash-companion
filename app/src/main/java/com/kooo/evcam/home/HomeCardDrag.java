package com.kooo.evcam.home;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ScrollView;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * Hold a home card and drag it to another card's place. Holding and letting go still does what
 * holding always did ({@code onHold}), so the two share one gesture: moving the finger is what
 * makes it a drag.
 *
 * <p>A copy of the card follows the finger and the grid stays put until the finger lifts:
 * rebuilding the rows mid-drag would take the card out from under the touch and end it.</p>
 *
 * <p>Main thread only.</p>
 */
final class HomeCardDrag {

    interface Host {
        /** The cards that can be moved, and swapped with, in page order. */
        List<View> movable();

        /** {@code card} goes to index {@code to} of {@link #movable}. */
        void moved(View card, int to);
    }

    private static final int GHOST_ALPHA = 225;
    private static final float SOURCE_ALPHA = 0.3f;
    private static final float TARGET_ALPHA = 0.6f;
    private static final float TARGET_SCALE = 0.94f;
    /** How close to the top or bottom of the grid the finger has to be to scroll it, and how far one move scrolls. */
    private static final int EDGE_DP = 56;
    private static final int SCROLL_DP = 16;

    private final ScrollView scroll;
    private final Host host;
    private final int slop;
    private final int edge;
    private final int scrollStep;

    private View dragged;
    private View target;
    private Runnable hold;
    private BitmapDrawable ghost;
    private boolean moved;
    private float downX;
    private float downY;
    private float lastX;
    private float lastY;

    HomeCardDrag(ScrollView scroll, Host host) {
        this.scroll = scroll;
        this.host = host;
        slop = ViewConfiguration.get(scroll.getContext()).getScaledTouchSlop() * 2;
        float density = scroll.getResources().getDisplayMetrics().density;
        edge = Math.round(EDGE_DP * density);
        scrollStep = Math.round(SCROLL_DP * density);
    }

    @SuppressLint("ClickableViewAccessibility")
    void attach(View card, @Nullable Runnable onHold) {
        card.setOnTouchListener((v, event) -> touch(v, event));
        card.setOnLongClickListener(v -> {
            start(v, onHold);
            return true;
        });
    }

    private boolean touch(View card, MotionEvent event) {
        lastX = event.getRawX();
        lastY = event.getRawY();
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            // A new press while one is still dragging: its finger-up never came. Drop it, don't move anything
            cancel();
            downX = lastX;
            downY = lastY;
        }
        if (dragged != card) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_MOVE:
                follow();
                break;
            case MotionEvent.ACTION_UP:
                finish(true);
                break;
            case MotionEvent.ACTION_CANCEL:
                finish(false);
                break;
            default:
                break;
        }
        return true;
    }

    /** Ends a drag without moving anything. Does nothing when there is none. */
    void cancel() {
        if (dragged != null) {
            finish(false);
        }
    }

    private void start(View card, @Nullable Runnable onHold) {
        dragged = card;
        hold = onHold;
        moved = false;
        target = null;
        card.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        scroll.requestDisallowInterceptTouchEvent(true);
        card.setPressed(false);
        Bitmap copy = Bitmap.createBitmap(card.getWidth(), card.getHeight(), Bitmap.Config.ARGB_8888);
        card.draw(new Canvas(copy));
        ghost = new BitmapDrawable(card.getResources(), copy);
        ghost.setAlpha(GHOST_ALPHA);
        place();
        card.getRootView().getOverlay().add(ghost);
    }

    private void follow() {
        if (Math.hypot(lastX - downX, lastY - downY) > slop && !moved) {
            moved = true;
            dragged.setAlpha(SOURCE_ALPHA);
        }
        if (!moved) {
            return;
        }
        place();
        View over = cardUnderFinger();
        if (over != target) {
            mark(target, false);
            mark(over, true);
            target = over;
        }
        Rect box = screenRect(scroll);
        if (lastY < box.top + edge) {
            scroll.scrollBy(0, -scrollStep);
        } else if (lastY > box.bottom - edge) {
            scroll.scrollBy(0, scrollStep);
        }
    }

    private void finish(boolean lifted) {
        View card = dragged;
        Runnable onHold = hold;
        View into = target;
        boolean wasMoved = moved;
        card.getRootView().getOverlay().remove(ghost);
        card.setAlpha(1f);
        card.setPressed(false);
        mark(target, false);
        dragged = null;
        target = null;
        hold = null;
        ghost = null;
        if (!lifted) {
            return;
        }
        if (!wasMoved) {
            if (onHold != null) {
                onHold.run();
            }
        } else if (into != null) {
            int to = host.movable().indexOf(into);
            // Not inside this touch: the rows are rebuilt around the card still handling it
            card.post(() -> host.moved(card, to));
        }
    }

    /** Keeps the copy under the finger, by the middle of the card. */
    private void place() {
        int[] root = new int[2];
        dragged.getRootView().getLocationOnScreen(root);
        int width = dragged.getWidth();
        int height = dragged.getHeight();
        int left = Math.round(lastX) - root[0] - width / 2;
        int top = Math.round(lastY) - root[1] - height / 2;
        ghost.setBounds(left, top, left + width, top + height);
    }

    private View cardUnderFinger() {
        for (View card : host.movable()) {
            if (card != dragged && screenRect(card).contains(Math.round(lastX), Math.round(lastY))) {
                return card;
            }
        }
        return null;
    }

    private static void mark(@Nullable View card, boolean on) {
        if (card != null) {
            card.setAlpha(on ? TARGET_ALPHA : 1f);
            card.setScaleX(on ? TARGET_SCALE : 1f);
            card.setScaleY(on ? TARGET_SCALE : 1f);
        }
    }

    private static Rect screenRect(View view) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return new Rect(at[0], at[1], at[0] + view.getWidth(), at[1] + view.getHeight());
    }
}
