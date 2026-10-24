package com.kooo.evcam.zeekr;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;

/**
 * Move, pinch and dock for a floating window, the way Super mirror does it
 * ({@link RearViewMirrorView}), for windows without its framing zone (AirPlay, cabin passenger).
 *
 * <ul>
 *   <li>Drag anywhere: the window follows the finger.</li>
 *   <li>Pinch: resizes around the point between the fingers and follows it; the shape is kept.</li>
 *   <li>Let go: pushed half off a side or flung at it, the window docks there with a
 *       {@link #PEEK_WIDTH_PX} strip showing; otherwise it glides fully back on screen.
 *       A tap on a docked strip, or a short pull, brings it back.</li>
 *   <li>Position and size are saved when the gesture ends (the size already when the second
 *       finger lifts).</li>
 * </ul>
 *
 * <p>The window needs {@code FLAG_LAYOUT_NO_LIMITS} to dock. Main thread only.</p>
 */
public final class FloatingWindowGestures {

    /** Same numbers as Super mirror, so the windows feel the same. */
    public static final int PEEK_WIDTH_PX = 72;
    private static final int DRAG_SLOP_PX = 12;
    private static final int UNDOCK_MIN_PX = 60;
    private static final long GLIDE_MIN_MS = 120L;
    private static final long GLIDE_MAX_MS = 380L;

    public interface Host {
        WindowManager.LayoutParams params();

        int screenWidth();

        int screenHeight();

        /** Smallest side a pinch may shrink the window to. */
        int minSize();

        /** Push {@link #params()} to the window manager. */
        void apply();

        /** Remember position and size. */
        void save();

        /** A plain tap that was not on a docked strip. */
        default void onTap(MotionEvent up) {
        }
    }

    private final Host host;
    private final int minFlingVelocity;

    private VelocityTracker velocity;
    private ValueAnimator glide;
    private float touchStartX;
    private float touchStartY;
    private int windowStartX;
    private int windowStartY;
    private float lastDx;
    private boolean dragging;
    private boolean pinching;
    private boolean pinchedThisGesture;
    private float pinchStartSpan;
    private int pinchStartWidth;
    private int pinchStartHeight;
    private float pinchAnchorRatioX;
    private float pinchAnchorRatioY;

    public FloatingWindowGestures(Context context, Host host) {
        this.host = host;
        this.minFlingVelocity = ViewConfiguration.get(context).getScaledMinimumFlingVelocity();
    }

    /** Feed every touch event of the window's root view. Always consumes it. */
    public boolean onTouch(MotionEvent event) {
        WindowManager.LayoutParams p = host.params();
        if (p == null) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (velocity == null) {
                    velocity = VelocityTracker.obtain();
                } else {
                    velocity.clear();
                }
                velocity.addMovement(event);
                cancelGlide();
                touchStartX = event.getRawX();
                touchStartY = event.getRawY();
                windowStartX = p.x;
                windowStartY = p.y;
                lastDx = 0f;
                dragging = false;
                pinching = false;
                pinchedThisGesture = false;
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.getPointerCount() == 2) {
                    beginPinch(event, p);
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (velocity != null) {
                    velocity.addMovement(event);
                }
                if (pinching && event.getPointerCount() >= 2) {
                    updatePinch(event, p);
                } else if (!pinching && !pinchedThisGesture) {
                    updateDrag(event, p);
                }
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                // The pinch ends when one of the two fingers lifts: save the size now, not at UP
                if (event.getPointerCount() <= 2 && pinching) {
                    pinching = false;
                    pinchedThisGesture = true;
                    host.save();
                }
                return true;

            case MotionEvent.ACTION_UP:
                endTouch(event, takeXVelocity(event), true);
                return true;

            case MotionEvent.ACTION_CANCEL:
                endTouch(event, takeXVelocity(event), false);
                return true;

            default:
                return true;
        }
    }

    /** Stop a running glide, e.g. when the window closes. */
    public void cancel() {
        cancelGlide();
    }

    private void beginPinch(MotionEvent event, WindowManager.LayoutParams p) {
        pinching = true;
        dragging = false;
        pinchStartSpan = spanOf(event);
        pinchStartWidth = p.width;
        pinchStartHeight = p.height;
        float focusX = (event.getX(0) + event.getX(1)) / 2f;
        float focusY = (event.getY(0) + event.getY(1)) / 2f;
        pinchAnchorRatioX = clamp01(focusX / Math.max(1, pinchStartWidth));
        pinchAnchorRatioY = clamp01(focusY / Math.max(1, pinchStartHeight));
    }

    private void updatePinch(MotionEvent event, WindowManager.LayoutParams p) {
        float span = spanOf(event);
        if (pinchStartSpan <= 0f || span <= 0f) {
            return;
        }
        RearViewTouchModel.PinchResult result = RearViewTouchModel.pinch(
                p.x, p.y, pinchStartWidth, pinchStartHeight,
                pinchAnchorRatioX, pinchAnchorRatioY,
                rawFocusX(event), rawFocusY(event), span / pinchStartSpan,
                host.minSize(), host.screenWidth(), host.screenHeight());
        p.width = result.width;
        p.height = result.height;
        p.x = RearViewTouchModel.clampX(result.x, result.width, host.screenWidth(), PEEK_WIDTH_PX);
        p.y = RearViewTouchModel.clampY(result.y, result.height, host.screenHeight());
        host.apply();
    }

    private void updateDrag(MotionEvent event, WindowManager.LayoutParams p) {
        float dx = event.getRawX() - touchStartX;
        float dy = event.getRawY() - touchStartY;
        if (!dragging && Math.abs(dx) < DRAG_SLOP_PX && Math.abs(dy) < DRAG_SLOP_PX) {
            return;
        }
        dragging = true;
        lastDx = dx;
        p.x = RearViewTouchModel.clampX(windowStartX + (int) dx, p.width, host.screenWidth(), PEEK_WIDTH_PX);
        p.y = RearViewTouchModel.clampY(windowStartY + (int) dy, p.height, host.screenHeight());
        host.apply();
    }

    private void endTouch(MotionEvent event, float velocityX, boolean wasTap) {
        WindowManager.LayoutParams p = host.params();
        if (p == null) {
            return;
        }
        if (!dragging && !pinching && !pinchedThisGesture) {
            if (!wasTap) {
                return;
            }
            RearViewTouchModel.Dock dock = RearViewTouchModel.dockedAt(p.x, p.width, host.screenWidth());
            if (dock != RearViewTouchModel.Dock.NONE) {
                // Docked, only a strip shows: a tap brings it back
                glideTo(RearViewTouchModel.flushX(dock, p.width, host.screenWidth()), 0f);
            } else {
                host.onTap(event);
            }
            return;
        }
        settle(p, velocityX);
        dragging = false;
        pinching = false;
        pinchedThisGesture = false;
    }

    /** Where the window lands: docked, fully on screen, or back in its dock. Never half out. */
    private void settle(WindowManager.LayoutParams p, float velocityX) {
        int screenW = host.screenWidth();
        RearViewTouchModel.Dock wasDocked = RearViewTouchModel.dockedAt(windowStartX, p.width, screenW);
        if (wasDocked != RearViewTouchModel.Dock.NONE) {
            if (RearViewTouchModel.shouldUndock(wasDocked, lastDx, velocityX, UNDOCK_MIN_PX, minFlingVelocity)) {
                glideTo(RearViewTouchModel.flushX(wasDocked, p.width, screenW), velocityX);
            } else {
                glideTo(RearViewTouchModel.dockedX(wasDocked, p.x, p.width, screenW, PEEK_WIDTH_PX), velocityX);
            }
            host.save();
            return;
        }
        RearViewTouchModel.Dock dock = RearViewTouchModel.deliberateDock(
                p.x, p.width, screenW, velocityX, minFlingVelocity);
        if (dock != RearViewTouchModel.Dock.NONE) {
            glideTo(RearViewTouchModel.dockedX(dock, p.x, p.width, screenW, PEEK_WIDTH_PX), velocityX);
        } else {
            int onScreen = Math.max(0, Math.min(screenW - p.width, p.x));
            if (onScreen != p.x) {
                glideTo(onScreen, velocityX);
            }
        }
        host.save();
    }

    private void glideTo(int targetX, float velocityX) {
        WindowManager.LayoutParams p = host.params();
        if (p == null || targetX == p.x) {
            return;
        }
        cancelGlide();
        long duration = RearViewTouchModel.glideDurationMs(targetX - p.x, velocityX, GLIDE_MIN_MS, GLIDE_MAX_MS);
        glide = ValueAnimator.ofInt(p.x, targetX);
        glide.setDuration(duration);
        glide.setInterpolator(new DecelerateInterpolator());
        glide.addUpdateListener(animation -> {
            WindowManager.LayoutParams now = host.params();
            if (now != null) {
                now.x = (Integer) animation.getAnimatedValue();
                host.apply();
            }
        });
        glide.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                glide = null;
                host.save();
            }
        });
        glide.start();
    }

    private void cancelGlide() {
        if (glide != null) {
            glide.cancel();
            glide = null;
        }
    }

    private float takeXVelocity(MotionEvent event) {
        if (velocity == null) {
            return 0f;
        }
        float vx = 0f;
        try {
            velocity.addMovement(event);
            velocity.computeCurrentVelocity(1000);
            vx = velocity.getXVelocity();
        } finally {
            velocity.recycle();
            velocity = null;
        }
        return vx;
    }

    private static float spanOf(MotionEvent event) {
        return (float) Math.hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1));
    }

    /** Screen point between the two fingers; getRawX(int) needs API 29. */
    private static float rawFocusX(MotionEvent event) {
        return event.getRawX() + (event.getX(1) - event.getX(0)) / 2f;
    }

    private static float rawFocusY(MotionEvent event) {
        return event.getRawY() + (event.getY(1) - event.getY(0)) / 2f;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
