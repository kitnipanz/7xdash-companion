package com.kooo.evcam.browser;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.zeekr.FloatingWindowGestures;
import com.kooo.evcam.zeekr.RearViewTouchModel;

/**
 * The browser as a floating window over whatever is on the screen. The grip at the left of the
 * toolbar moves it (push it to a side to dock it, like Super mirror), a double-tap on the grip
 * switches small and large, and two fingers on the grip resize it. The window takes key focus
 * only while you touch it, so the app underneath keeps its keys. Main thread only.
 */
public final class BrowserFloat implements BrowserView.Host {

    private static final String TAG = "BrowserFloat";
    private static final String PREFS = "browser_float";
    private static final float SMALL = 0.45f;
    private static final float LARGE = 0.8f;
    private static final int MIN_SIDE_DP = 220;
    private static final int GRIP_DP = 64;

    private static BrowserFloat live;

    private final Context context;
    private final WindowManager wm;
    private final SharedPreferences prefs;
    private final float density;
    private FrameLayout root;
    private BrowserView browser;
    private WindowManager.LayoutParams lp;
    private View resize;

    private BrowserFloat(Context context) {
        this.context = context.getApplicationContext();
        wm = this.context.getSystemService(WindowManager.class);
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        density = this.context.getResources().getDisplayMetrics().density;
    }

    public static boolean isShown() {
        return live != null && live.root != null;
    }

    /** Opens the floating browser at {@code url} (null: the last page). False when 7xDash may not draw over apps yet. */
    public static boolean show(Context context, String url) {
        if (!Settings.canDrawOverlays(context)) {
            Toast.makeText(context, R.string.browser_need_overlay, Toast.LENGTH_LONG).show();
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(i);
            } catch (RuntimeException ignored) {
                // no such settings screen on this head unit
            }
            return false;
        }
        if (live == null) {
            live = new BrowserFloat(context);
        }
        if (live.root == null) {
            live.open(url);
        } else if (url != null) {
            live.browser.load(url);
        }
        return true;
    }

    /** Opens it, or closes it when it is open (the Home card's long press, a shortcut). */
    public static void toggle(Context context) {
        if (isShown()) {
            hide();
        } else {
            show(context, null);
        }
    }

    public static void hide() {
        BrowserFloat f = live;
        if (f != null) {
            f.remove();
        }
    }

    private void open(String url) {
        root = new FrameLayout(context) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    // A touch on the app underneath: give the keys back
                    wantKeys(false);
                    return false;
                }
                return super.dispatchTouchEvent(e);
            }
        };
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
        // The WebView wants a themed context for its text boxes and pop-ups, in the app's language
        browser = new BrowserView(new android.view.ContextThemeWrapper(
                com.kooo.evcam.settings.Languages.localized(context), R.style.Theme_Cam), this, url);
        root.addView(browser, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        // The corner grip, as on Navigation's floating map but in the top-left corner, just under
        // the toolbar (its own left end is the move grip): drag to set width and height
        int g = Math.round(GRIP_DP * density);
        resize = new Grip(context);
        resize.setRotation(180f);
        resize.setContentDescription(context.getString(R.string.nav_float_resize));
        resize.setOnTouchListener(new ResizeTouch());
        FrameLayout.LayoutParams gripAt = new FrameLayout.LayoutParams(g, g, Gravity.TOP | Gravity.START);
        gripAt.topMargin = browser.pageTop();
        root.addView(resize, gripAt);

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        // The keyboard covers the bottom of the window; it must not push or shrink the window
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        DisplayMetrics dm = screen();
        lp.width = Math.round(prefs.getFloat("width", SMALL) * dm.widthPixels);
        lp.height = Math.round(prefs.getFloat("height", 0f) * dm.heightPixels);
        if (lp.height <= 0) {
            lp.height = heightFor(lp.width);
        }
        lp.x = RearViewTouchModel.clampX(prefs.getInt("x", (dm.widthPixels - lp.width) / 2), lp.width,
                dm.widthPixels, FloatingWindowGestures.PEEK_WIDTH_PX);
        lp.y = RearViewTouchModel.clampY(prefs.getInt("y", (dm.heightPixels - lp.height) / 2), lp.height,
                dm.heightPixels);
        try {
            wm.addView(root, lp);
            AppLog.i(TAG, "shown " + lp.width + "x" + lp.height + " at " + lp.x + "," + lp.y);
        } catch (RuntimeException e) {
            AppLog.w(TAG, "floating browser refused: " + e);
            browser.destroy();
            root = null;
            browser = null;
        }
    }

    private void remove() {
        if (root == null) {
            return;
        }
        browser.destroy();
        try {
            wm.removeView(root);
        } catch (RuntimeException ignored) {
            // already gone
        }
        root = null;
        browser = null;
        resize = null;
    }

    /** 16:9 page plus the toolbar. */
    private int heightFor(int width) {
        return Math.round(width * 9f / 16f + (60 + 28) * density);
    }

    private DisplayMetrics screen() {
        return context.getResources().getDisplayMetrics();
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
        DisplayMetrics dm = screen();
        prefs.edit()
                .putInt("x", lp.x)
                .putInt("y", lp.y)
                .putFloat("width", (float) lp.width / dm.widthPixels)
                .putFloat("height", (float) lp.height / dm.heightPixels)
                .apply();
    }

    private void toggleSize() {
        DisplayMetrics dm = screen();
        float share = (float) lp.width / dm.widthPixels;
        int centreX = lp.x + lp.width / 2;
        int centreY = lp.y + lp.height / 2;
        lp.width = Math.round((share < (SMALL + LARGE) / 2 ? LARGE : SMALL) * dm.widthPixels);
        lp.height = Math.min(heightFor(lp.width), Math.round(dm.heightPixels * 0.95f));
        lp.x = Math.max(0, Math.min(centreX - lp.width / 2, dm.widthPixels - lp.width));
        lp.y = Math.max(0, Math.min(centreY - lp.height / 2, dm.heightPixels - lp.height));
        update();
        save();
    }

    // ================================================================= BrowserView.Host

    @Override
    public void close() {
        remove();
    }

    @Override
    public void switchMode(String url) {
        Intent i = new Intent(context, BrowserActivity.class)
                .putExtra(BrowserActivity.EXTRA_URL, url)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        remove();
        context.startActivity(i);
    }

    @Override
    public void wantKeys(boolean on) {
        if (root == null || lp == null) {
            return;
        }
        boolean focusable = (lp.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0;
        if (focusable == on) {
            return;
        }
        if (on) {
            lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        update();
    }

    @Override
    public boolean floating() {
        return true;
    }

    @Override
    public View.OnTouchListener grip(Runnable onTap) {
        return new GripTouch(onTap);
    }

    /** No toolbar: the window drops its height so the page keeps its size and place. */
    @Override
    public void toolbarShown(boolean shown) {
        if (root == null || lp == null || browser == null) {
            return;
        }
        int bar = browser.toolbarHeight();
        DisplayMetrics dm = screen();
        if (shown) {
            lp.height += bar;
            lp.y = Math.max(0, lp.y - bar);
        } else {
            lp.height = Math.max(Math.round(MIN_SIDE_DP * density), lp.height - bar);
            lp.y = Math.min(lp.y + bar, Math.max(0, dm.heightPixels - lp.height));
        }
        update();
        save();
    }

    /** Keeps the resize grip at the top-left of the page, under the toolbar when it shows. */
    @Override
    public void pageTopChanged(int top) {
        if (resize == null) {
            return;
        }
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) resize.getLayoutParams();
        if (p.topMargin != top) {
            p.topMargin = top;
            resize.setLayoutParams(p);
        }
    }

    /** The top-left grip: width and height follow the finger separately; the bottom-right corner stays. */
    private final class ResizeTouch implements View.OnTouchListener {
        private float startX;
        private float startY;
        private int startW;
        private int startH;
        private int startLeft;
        private int startTop;

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = e.getRawX();
                    startY = e.getRawY();
                    startW = lp.width;
                    startH = lp.height;
                    startLeft = lp.x;
                    startTop = lp.y;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int min = Math.round(MIN_SIDE_DP * density);
                    int right = startLeft + startW;
                    int bottom = startTop + startH;
                    // Dragging left or up grows the window; the right and bottom edges stay put
                    int w = Math.max(min, Math.min(startW - Math.round(e.getRawX() - startX), right));
                    int h = Math.max(min, Math.min(startH - Math.round(e.getRawY() - startY), bottom));
                    lp.width = w;
                    lp.height = h;
                    lp.x = right - w;
                    lp.y = bottom - h;
                    update();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    save();
                    return true;
                default:
                    return true;
            }
        }
    }

    /** Three short diagonal lines in the corner: grab here to resize (same look as Navigation's). */
    private static final class Grip extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint back = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        Grip(Context context) {
            super(context);
            float d = context.getResources().getDisplayMetrics().density;
            paint.setColor(0xFFF0622D);
            paint.setStrokeWidth(3.5f * d);
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            back.setColor(0xB3101114);
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth();
            float h = getHeight();
            c.drawCircle(w, h, w * 0.85f, back);
            float m = w * 0.18f;
            for (int i = 1; i <= 3; i++) {
                float t = i * (w - m) / 4.5f;
                c.drawLine(w - m - t, h - m, w - m, h - m - t, paint);
            }
        }
    }

    /** Move, dock and resize from the grip; double-tap for small / large. */
    private final class GripTouch implements View.OnTouchListener {
        private final Runnable onTap;

        GripTouch(Runnable onTap) {
            this.onTap = onTap;
        }

        private final GestureDetector taps = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        toggleSize();
                        return true;
                    }
                });
        private final FloatingWindowGestures gestures = new FloatingWindowGestures(context,
                new FloatingWindowGestures.Host() {
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
                        BrowserFloat.this.save();
                    }

                    @Override
                    public boolean keepShape() {
                        return false;
                    }

                    @Override
                    public void onTap(MotionEvent up) {
                        if (onTap != null) {
                            onTap.run();
                        }
                    }
                });

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if (e.getPointerCount() == 1) {
                taps.onTouchEvent(e);
            }
            // The gestures go by screen (raw) positions, so the grip moving with the window is fine
            return gestures.onTouch(e);
        }
    }
}
