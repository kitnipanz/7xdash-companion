package com.kooo.evcam.airplay;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;

/**
 * The floating screen the iPhone mirrors into: a rounded card over whatever is on the head
 * unit. Drag it to move, drag the corner to resize (it keeps the iPhone's shape), double-tap to
 * switch between small and large, × to end the mirroring. Where it sat and how big it was are
 * remembered. Main thread only.
 */
final class AirPlayWindow {

    interface Listener {
        void onSurface(Surface surface);

        void onCloseTapped();
    }

    private static final String TAG = "AirPlayWindow";
    private static final String PREFS = "airplay_window";
    /** Small and large widths as a share of the screen width. */
    private static final float SMALL = 0.42f;
    private static final float LARGE = 0.86f;
    private static final float MIN = 0.22f;

    private final Context context;
    private final WindowManager wm;
    private final Listener listener;
    private final SharedPreferences prefs;
    private final float density;

    private FrameLayout root;
    private TextureView video;
    private TextView status;
    private WindowManager.LayoutParams lp;
    private Surface surface;
    private float aspect = 16f / 9f;
    private boolean blocked;
    private boolean playing;

    AirPlayWindow(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        wm = context.getSystemService(WindowManager.class);
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        density = context.getResources().getDisplayMetrics().density;
    }

    boolean isShown() {
        return root != null;
    }

    void show() {
        if (root != null) {
            return;
        }
        root = new FrameLayout(context);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF000000);
        bg.setCornerRadius(18 * density);
        bg.setStroke(Math.round(2 * density), ContextCompat.getColor(context, R.color.line));
        root.setBackground(bg);
        root.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), 18 * density);
            }
        });
        root.setClipToOutline(true);

        video = new TextureView(context);
        video.setSurfaceTextureListener(textureListener);
        root.addView(video, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        status = new TextView(context);
        status.setTextAppearance(R.style.TextAppearance_Cam_Body);
        status.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
        status.setGravity(Gravity.CENTER);
        int pad = Math.round(24 * density);
        status.setPadding(pad, pad, pad, pad);
        root.addView(status, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        root.addView(button(R.drawable.ic_close, R.string.airplay_close, v -> listener.onCloseTapped()),
                corner(Gravity.TOP | Gravity.END));
        ImageView resize = button(R.drawable.ic_resize, R.string.airplay_resize, null);
        resize.setOnTouchListener(new ResizeTouch());
        root.addView(resize, corner(Gravity.BOTTOM | Gravity.END));

        root.setOnTouchListener(new MoveTouch());

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        DisplayMetrics dm = screen();
        lp.width = Math.round(prefs.getFloat("width", SMALL) * dm.widthPixels);
        lp.height = heightFor(lp.width);
        fitWidth();
        lp.x = prefs.getInt("x", (dm.widthPixels - lp.width) / 2);
        lp.y = prefs.getInt("y", (dm.heightPixels - lp.height) / 2);
        clampPosition();
        paintStatus();
        try {
            wm.addView(root, lp);
        } catch (RuntimeException e) {
            AppLog.w(TAG, "floating screen refused: " + e);
            root = null;
        }
    }

    void hide() {
        if (root == null) {
            return;
        }
        try {
            wm.removeView(root);
        } catch (RuntimeException ignored) {
            // already gone with the display
        }
        root = null;
        video = null;
        status = null;
        if (surface != null) {
            listener.onSurface(null);
            surface.release();
            surface = null;
        }
    }

    /** The iPhone told us its picture's size: reshape the card to match. */
    void setVideoSize(int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        aspect = (float) w / h;
        if (root == null) {
            return;
        }
        lp.height = heightFor(lp.width);
        fitWidth();
        clampPosition();
        update();
    }

    void setPlaying(boolean playing) {
        this.playing = playing;
        paintStatus();
    }

    /** True while the car is moving: the picture hides, the sound keeps playing. */
    void setBlocked(boolean blocked) {
        this.blocked = blocked;
        paintStatus();
    }

    private void paintStatus() {
        if (status == null) {
            return;
        }
        boolean showVideo = playing && !blocked;
        video.setAlpha(showVideo ? 1f : 0f);
        status.setVisibility(showVideo ? View.GONE : View.VISIBLE);
        status.setText(blocked ? R.string.airplay_paused_driving : R.string.airplay_waiting);
    }

    private ImageView button(int icon, int label, View.OnClickListener onClick) {
        ImageView b = new ImageView(context);
        b.setImageResource(icon);
        b.setContentDescription(context.getString(label));
        int pad = Math.round(10 * density);
        b.setPadding(pad, pad, pad, pad);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0x99000000);
        b.setBackground(circle);
        if (onClick != null) {
            b.setOnClickListener(onClick);
        }
        return b;
    }

    private FrameLayout.LayoutParams corner(int gravity) {
        int size = Math.round(48 * density);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(size, size, gravity);
        int m = Math.round(8 * density);
        p.setMargins(m, m, m, m);
        return p;
    }

    private DisplayMetrics screen() {
        return context.getResources().getDisplayMetrics();
    }

    private int heightFor(int width) {
        return Math.round(width / aspect);
    }

    /** A tall iPhone picture must still fit the screen's height. */
    private void fitWidth() {
        DisplayMetrics dm = screen();
        int maxH = Math.round(dm.heightPixels * 0.92f);
        if (lp.height > maxH) {
            lp.height = maxH;
            lp.width = Math.round(maxH * aspect);
        }
        int minW = Math.round(dm.widthPixels * MIN);
        if (lp.width < minW && aspect >= 1f) {
            lp.width = minW;
            lp.height = heightFor(minW);
        }
    }

    private void clampPosition() {
        DisplayMetrics dm = screen();
        lp.x = Math.max(0, Math.min(lp.x, dm.widthPixels - lp.width));
        lp.y = Math.max(0, Math.min(lp.y, dm.heightPixels - lp.height));
    }

    private void update() {
        if (root == null) {
            return;
        }
        try {
            wm.updateViewLayout(root, lp);
        } catch (RuntimeException ignored) {
            // window went away
        }
    }

    private void save() {
        prefs.edit()
                .putInt("x", lp.x)
                .putInt("y", lp.y)
                .putFloat("width", (float) lp.width / screen().widthPixels)
                .apply();
    }

    private void toggleSize() {
        DisplayMetrics dm = screen();
        float share = (float) lp.width / dm.widthPixels;
        int centreX = lp.x + lp.width / 2;
        int centreY = lp.y + lp.height / 2;
        lp.width = Math.round((share < (SMALL + LARGE) / 2 ? LARGE : SMALL) * dm.widthPixels);
        lp.height = heightFor(lp.width);
        fitWidth();
        lp.x = centreX - lp.width / 2;
        lp.y = centreY - lp.height / 2;
        clampPosition();
        update();
        save();
    }

    private final TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture st, int w, int h) {
            surface = new Surface(st);
            listener.onSurface(surface);
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture st, int w, int h) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture st) {
            listener.onSurface(null);
            if (surface != null) {
                surface.release();
                surface = null;
            }
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture st) {
        }
    };

    /** Drag the card around; double-tap for small / large. */
    private final class MoveTouch implements View.OnTouchListener {
        private final GestureDetector taps = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        toggleSize();
                        return true;
                    }
                });
        private float downX;
        private float downY;
        private int startX;
        private int startY;

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            taps.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lp.x = startX + Math.round(e.getRawX() - downX);
                    lp.y = startY + Math.round(e.getRawY() - downY);
                    clampPosition();
                    update();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    save();
                    return true;
                default:
                    return false;
            }
        }
    }

    /** Drag the corner: width follows the finger, height follows the iPhone's shape. */
    private final class ResizeTouch implements View.OnTouchListener {
        private float downX;
        private int startW;

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    startW = lp.width;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    DisplayMetrics dm = screen();
                    int w = startW + Math.round(e.getRawX() - downX);
                    w = Math.max(Math.round(dm.widthPixels * MIN), Math.min(w, dm.widthPixels - lp.x));
                    lp.width = w;
                    lp.height = heightFor(w);
                    fitWidth();
                    clampPosition();
                    update();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    save();
                    return true;
                default:
                    return false;
            }
        }
    }
}
