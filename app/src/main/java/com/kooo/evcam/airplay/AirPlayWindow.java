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

import com.kooo.evcam.R;

/**
 * The floating screen the iPhone mirrors into: a rounded card over whatever is on the head
 * unit. It moves, pinches and docks to a screen edge the way Super mirror does
 * ({@link com.kooo.evcam.zeekr.FloatingWindowGestures}); a pinch keeps the iPhone's shape.
 * Double-tap switches between small and large, × ends the mirroring. Where it sat and how big it was are
 * remembered. Main thread only.
 */
final class AirPlayWindow {

    interface Listener {
        void onSurface(Surface surface);

        void onCloseTapped();
    }

    private static final String PREFS = "airplay_window";
    /** Bumped when saved places and sizes should be forgotten. */
    private static final int LAYOUT = 1;
    /** Small and large widths as a share of the screen width; it opens large (full width). */
    private static final float SMALL = 0.42f;
    private static final float LARGE = 1f;
    private static final float MIN = 0.22f;
    /** Smallest side a pinch shrinks the window to. */
    private static final int MIN_SIDE_DP = 120;

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
        if (prefs.getInt("layout", 0) < LAYOUT) {
            // Sizes saved before it opened full width are dropped once, so everyone starts full width.
            prefs.edit().clear().putInt("layout", LAYOUT).apply();
        }
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
        root.setOnTouchListener(new MoveTouch());

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        DisplayMetrics dm = screen();
        lp.width = Math.round(prefs.getFloat("width", LARGE) * dm.widthPixels);
        lp.height = heightFor(lp.width);
        fitWidth();
        lp.x = com.kooo.evcam.zeekr.RearViewTouchModel.clampX(
                prefs.getInt("x", (dm.widthPixels - lp.width) / 2), lp.width, dm.widthPixels,
                com.kooo.evcam.zeekr.FloatingWindowGestures.PEEK_WIDTH_PX);
        lp.y = com.kooo.evcam.zeekr.RearViewTouchModel.clampY(
                prefs.getInt("y", (dm.heightPixels - lp.height) / 2), lp.height, dm.heightPixels);
        paintStatus();
        try {
            wm.addView(root, lp);
            AirPlayLog.i("floating window shown " + lp.width + "x" + lp.height + " at " + lp.x + "," + lp.y);
        } catch (RuntimeException e) {
            AirPlayLog.w("floating window refused: " + e);
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

    /** Move, pinch and dock like Super mirror; double-tap for small / large. */
    private final class MoveTouch implements View.OnTouchListener {
        private final GestureDetector taps = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        toggleSize();
                        return true;
                    }
                });
        private final com.kooo.evcam.zeekr.FloatingWindowGestures gestures =
                new com.kooo.evcam.zeekr.FloatingWindowGestures(context,
                        new com.kooo.evcam.zeekr.FloatingWindowGestures.Host() {
                            @Override
                            public WindowManager.LayoutParams params() {
                                return lp;
                            }

                            @Override
                            public int screenWidth() {
                                return screen().widthPixels;
                            }

                            @Override
                            public int screenHeight() {
                                return screen().heightPixels;
                            }

                            @Override
                            public int minSize() {
                                return Math.round(MIN_SIDE_DP * density);
                            }

                            @Override
                            public void apply() {
                                update();
                            }

                            @Override
                            public void save() {
                                AirPlayWindow.this.save();
                            }
                        });

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if (e.getPointerCount() == 1) {
                taps.onTouchEvent(e);
            }
            return gestures.onTouch(e);
        }
    }
}
