package com.kooo.evcam.nav;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.ContextThemeWrapper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.overlay.OverlayCoordinator;
import com.kooo.evcam.zeekr.FloatingWindowGestures;
import com.kooo.evcam.zeekr.RearViewTouchModel;

import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;

/**
 * Navigation in a floating window over whatever else is on the head unit: the map, with the next
 * turn (or the trip) in a bar on top. Moved and docked by its bar like the AirPlay window
 * ({@link FloatingWindowGestures}), double-tap the bar for small / large, and the corner grip
 * changes the width and the height freely. Full screen opens {@link NavigationActivity} (which
 * closes this); × closes it while guiding goes on. Where it sat and its size are remembered.
 * Main thread only.
 */
public final class NavFloatWindow implements Navigator.Listener {

    private static final String TAG = "NavFloatWindow";
    private static final String PREFS = "nav_window";
    /** Default and double-tap sizes, as shares of the screen. */
    private static final float SMALL_W = 0.32f;
    private static final float SMALL_H = 0.45f;
    private static final float LARGE_W = 0.6f;
    private static final float LARGE_H = 0.86f;
    private static final int MIN_W_DP = 300;
    private static final int MIN_H_DP = 220;
    private static final int BAR_DP = 68;

    private static final NavFloatWindow INSTANCE = new NavFloatWindow();

    public static NavFloatWindow get() {
        return INSTANCE;
    }

    private Context context;
    private WindowManager wm;
    private SharedPreferences prefs;
    private float density;
    private FrameLayout root;
    private WindowManager.LayoutParams lp;
    private MapView mapView;
    private NavMapPainter painter;
    private ManeuverIcon.ArrowView arrow;
    private TextView title;
    private TextView line;
    /** Right of the texts: km left · battery at arrival. */
    private TextView trip;
    /** Under the bar, over the map: battery at arrival in words, while there is a route. */
    private TextView battery;
    private View recenter;
    /** Start (on a route preview) or Try again (no route), right on the bar. */
    private ImageView go;
    private FloatingWindowGestures gestures;
    private NavFloatSettings settings;
    /** Something was planned while open: back to nothing closes the window. */
    private boolean hadTrip;
    private Navigator.Phase shownPhase;
    /** The head unit switched day / night: recolour the map. */
    private final android.content.ComponentCallbacks dayNight = new android.content.ComponentCallbacks() {
        @Override
        public void onConfigurationChanged(android.content.res.Configuration c) {
            if (painter != null) {
                painter.applyScheme(new AppConfig(context).getNavMapScheme());
            }
        }

        @Override
        public void onLowMemory() {
        }
    };

    private NavFloatWindow() {
    }

    public boolean isShown() {
        return root != null;
    }

    /** Opens the window (needs the overlay permission). */
    public void show(Context from) {
        if (root != null || !OverlayCoordinator.canShowOverlay(from)) {
            return;
        }
        context = new ContextThemeWrapper(from.getApplicationContext(), R.style.Theme_Cam);
        wm = context.getSystemService(WindowManager.class);
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        density = context.getResources().getDisplayMetrics().density;
        hadTrip = false;
        shownPhase = null;

        root = new FrameLayout(context);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ContextCompat.getColor(context, R.color.surface));
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

        int bar = Math.round(BAR_DP * density);
        addMap(bar);
        root.addView(bar(), new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, bar, Gravity.TOP));

        recenter = round(R.drawable.ic_recenter, R.string.nav_recenter, v -> {
            recenter.setVisibility(View.GONE);
            if (painter != null) {
                painter.recenter(Navigator.get().snapshot());
            }
        });
        recenter.setVisibility(View.GONE);
        int b = Math.round(68 * density);
        int rp = Math.round(16 * density);
        recenter.setPadding(rp, rp, rp, rp);
        FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(b, b, Gravity.BOTTOM | Gravity.START);
        at.setMargins(Math.round(10 * density), 0, 0, Math.round(10 * density));
        root.addView(recenter, at);

        battery = new TextView(context);
        battery.setTextAppearance(R.style.TextAppearance_Cam_Caption);
        battery.setTypeface(battery.getTypeface(), android.graphics.Typeface.BOLD);
        int chip = Math.round(10 * density);
        battery.setPadding(chip, chip / 2, chip, chip / 2);
        battery.setVisibility(View.GONE);
        FrameLayout.LayoutParams batteryAt = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        batteryAt.setMargins(Math.round(10 * density), bar + Math.round(8 * density), 0, 0);
        root.addView(battery, batteryAt);

        int g = Math.round(64 * density);
        View grip = new Grip(context);
        grip.setContentDescription(context.getString(R.string.nav_float_resize));
        grip.setOnTouchListener(new ResizeTouch());
        root.addView(grip, new FrameLayout.LayoutParams(g, g, Gravity.BOTTOM | Gravity.END));

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        DisplayMetrics dm = screen();
        lp.width = Math.round(prefs.getFloat("w", SMALL_W) * dm.widthPixels);
        lp.height = Math.round(prefs.getFloat("h", SMALL_H) * dm.heightPixels);
        fitSize();
        lp.x = RearViewTouchModel.clampX(prefs.getInt("x", dm.widthPixels - lp.width - Math.round(24 * density)),
                lp.width, dm.widthPixels, FloatingWindowGestures.PEEK_WIDTH_PX);
        lp.y = RearViewTouchModel.clampY(prefs.getInt("y", Math.round(90 * density)), lp.height, dm.heightPixels);
        try {
            wm.addView(root, lp);
        } catch (RuntimeException e) {
            AppLog.w(TAG, "floating window refused: " + e.getMessage());
            dropViews();
            return;
        }
        if (mapView != null) {
            mapView.onStart();
            mapView.onResume();
        }
        Navigator.get().attach(context);
        Navigator.get().addListener(this);
        context.getApplicationContext().registerComponentCallbacks(dayNight);
        AppLog.i(TAG, "shown " + lp.width + "x" + lp.height + " at " + lp.x + "," + lp.y);
    }

    public void hide() {
        if (root == null) {
            return;
        }
        Navigator.get().removeListener(this);
        Navigator.get().detach();
        context.getApplicationContext().unregisterComponentCallbacks(dayNight);
        if (settings != null) {
            settings.hide();
            settings = null;
        }
        if (gestures != null) {
            gestures.cancel();
        }
        if (painter != null) {
            painter.release();
        }
        if (mapView != null) {
            mapView.onPause();
            mapView.onStop();
            mapView.onDestroy();
        }
        try {
            wm.removeView(root);
        } catch (RuntimeException ignored) {
            // already gone with the display
        }
        dropViews();
        AppLog.i(TAG, "closed");
    }

    /** The map colour setting changed on the full screen. */
    void applyScheme(int scheme) {
        if (painter != null) {
            painter.applyScheme(scheme);
        }
    }

    @Override
    public void onNavChanged(Navigator.Snapshot s) {
        if (root == null) {
            return;
        }
        if (s.phase != Navigator.Phase.IDLE) {
            hadTrip = true;
        } else if (hadTrip) {
            // ended or arrived and done: nothing left to show
            hide();
            return;
        }
        boolean guiding = s.phase == Navigator.Phase.GUIDING;
        int flags = lp.flags;
        lp.flags = guiding ? flags | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                : flags & ~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        if (lp.flags != flags) {
            update();
        }
        showBar(s);
        if (painter != null) {
            if (s.phase == Navigator.Phase.PREVIEW && s.phase != shownPhase) {
                painter.following = false;
                painter.fitRoute(s.route);
            } else if (s.phase == Navigator.Phase.GUIDING && s.phase != shownPhase) {
                painter.following = true;
                recenter.setVisibility(View.GONE);
            }
            shownPhase = s.phase;
            painter.draw(s);
        }
    }

    /** Battery at arrival as a chip on the map: white when fine, orange when low, red when short. */
    private void showBattery(Navigator.Snapshot s) {
        boolean trip = s.route != null && (s.phase == Navigator.Phase.PREVIEW || s.phase == Navigator.Phase.GUIDING);
        battery.setVisibility(trip ? View.VISIBLE : View.GONE);
        if (!trip) {
            return;
        }
        battery.setText(NavText.battery(context, s.battery));
        int fill = s.battery == null || s.battery.level == com.kooo.evcam.trip.Arrival.Level.OK ? 0xE6101114
                : s.battery.level == com.kooo.evcam.trip.Arrival.Level.LOW
                ? ContextCompat.getColor(context, R.color.energy) : ContextCompat.getColor(context, R.color.recording);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(100 * density);
        battery.setBackground(bg);
        battery.setTextColor(Color.WHITE);
    }

    /** A setting changed on the full screen: show it now. */
    public void refresh() {
        if (root != null) {
            onNavChanged(Navigator.get().snapshot());
        }
    }

    /** The bar: the next turn (or the trip before it starts), and km left · battery at arrival on the right. */
    private void showBar(Navigator.Snapshot s) {
        showBattery(s);
        TripLine t = s.phase == Navigator.Phase.IDLE ? null : TripLine.now(context);
        trip.setVisibility(t == null ? View.GONE : View.VISIBLE);
        if (t != null) {
            trip.setText(t.text);
            trip.setTextColor(t.color(Color.WHITE, ContextCompat.getColor(context, R.color.energy),
                    ContextCompat.getColor(context, R.color.recording)));
        }
        go.setVisibility(s.phase == Navigator.Phase.PREVIEW || s.phase == Navigator.Phase.FAILED
                ? View.VISIBLE : View.GONE);
        go.setContentDescription(context.getString(s.phase == Navigator.Phase.FAILED
                ? R.string.nav_retry : R.string.nav_start));
        Navigator.HudLine next = Navigator.hudLine(context, s);
        if (next != null) {
            arrow.setVisibility(View.VISIBLE);
            arrow.set(next.maneuver, Color.WHITE);
            title.setText(next.distance);
            line.setText(next.text);
            return;
        }
        arrow.setVisibility(View.GONE);
        if (s.destination == null) {
            title.setText(R.string.nav_title);
            line.setText(R.string.nav_idle_hint);
        } else {
            title.setText(NavMapPainter.placeName(s.destination));
            if (s.route != null) {
                line.setText(context.getString(R.string.nav_summary, NavText.duration(context, s.route.seconds),
                        NavText.distance(context, s.route.meters), NavText.eta(context, s.route.seconds)));
            } else {
                line.setText(s.phase == Navigator.Phase.FAILED ? R.string.nav_error_route : R.string.nav_routing);
            }
        }
    }

    // ---- building ----

    private void addMap(int bar) {
        if (!Navigator.hasKey()) {
            TextView none = new TextView(context);
            none.setText(R.string.nav_no_map);
            none.setTextAppearance(R.style.TextAppearance_Cam_Body);
            none.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
            none.setGravity(Gravity.CENTER);
            int pad = Math.round(24 * density);
            none.setPadding(pad, bar + pad, pad, pad);
            root.addView(none, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            return;
        }
        NavMap.start(context);
        mapView = new MapView(context, NavMap.options(context));
        mapView.onCreate(null);
        root.addView(mapView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        mapView.getMapAsync(map -> {
            if (root == null) {
                return;
            }
            painter = new NavMapPainter(context, map, mapView, 0, bar);
            map.getUiSettings().setCompassEnabled(false);
            map.addOnCameraMoveStartedListener(reason -> {
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE && painter != null) {
                    painter.following = false;
                    recenter.setVisibility(View.VISIBLE);
                }
            });
            Navigator.Snapshot s = Navigator.get().snapshot();
            if (s.phase == Navigator.Phase.PREVIEW) {
                painter.following = false;
                painter.fitRoute(s.route);
            }
            painter.draw(s);
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private View bar() {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xE6101114);
        int pad = Math.round(12 * density);
        bar.setPadding(pad, 0, Math.round(6 * density), 0);

        arrow = new ManeuverIcon.ArrowView(context);
        int icon = Math.round(44 * density);
        LinearLayout.LayoutParams arrowAt = new LinearLayout.LayoutParams(icon, icon);
        arrowAt.rightMargin = pad;
        bar.addView(arrow, arrowAt);

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        title = text(R.style.TextAppearance_Cam_Row, Color.WHITE, true);
        line = text(R.style.TextAppearance_Cam_Caption, 0xFFD0D2D6, false);
        texts.addView(title);
        texts.addView(line);
        bar.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        trip = text(R.style.TextAppearance_Cam_Row, Color.WHITE, true);
        trip.setVisibility(View.GONE);
        LinearLayout.LayoutParams tripAt = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tripAt.leftMargin = pad;
        bar.addView(trip, tripAt);

        int b = Math.round(48 * density);
        go = plain(R.drawable.ic_navigation, R.string.nav_start, v -> {
            if (Navigator.get().snapshot().phase == Navigator.Phase.FAILED) {
                Navigator.get().retry();
            } else {
                if (painter != null) {
                    painter.following = true;
                }
                Navigator.get().start();
            }
        });
        go.setVisibility(View.GONE);
        bar.addView(go, new LinearLayout.LayoutParams(b, b));
        bar.addView(plain(R.drawable.ic_settings, R.string.nav_settings_title, v -> toggleSettings()),
                new LinearLayout.LayoutParams(b, b));
        bar.addView(plain(R.drawable.ic_fullscreen, R.string.nav_float_expand, v -> {
            Intent open = new Intent(context, NavigationActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(open);
        }), new LinearLayout.LayoutParams(b, b));
        bar.addView(plain(R.drawable.ic_close, R.string.nav_float_close, v -> hide()),
                new LinearLayout.LayoutParams(b, b));
        bar.setOnTouchListener(new MoveTouch());
        return bar;
    }

    /** The settings in a window of their own beside the map, so nothing of the car's covers them. */
    private void toggleSettings() {
        if (settings == null) {
            settings = new NavFloatSettings(context, new NavSettings.Host() {
                @Override
                public void hudChanged() {
                    refresh();
                }

                @Override
                public void schemeChanged(int scheme) {
                    applyScheme(scheme);
                }

                @Override
                public void carColorChanged() {
                    refresh();
                }
            });
        }
        if (settings.isShown()) {
            settings.hide();
        } else {
            DisplayMetrics dm = screen();
            settings.show(lp.x, lp.y, lp.width, dm.widthPixels, dm.heightPixels);
        }
    }

    private TextView text(int style, int color, boolean bold) {
        TextView t = new TextView(context);
        t.setTextAppearance(style);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) {
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return t;
    }

    /** An icon button on the dark bar. */
    private ImageView plain(int icon, int label, View.OnClickListener onClick) {
        ImageView b = new ImageView(context);
        b.setImageResource(icon);
        b.setColorFilter(Color.WHITE);
        b.setContentDescription(context.getString(label));
        int pad = Math.round(12 * density);
        b.setPadding(pad, pad, pad, pad);
        b.setOnClickListener(onClick);
        return b;
    }

    /** An icon button on the map: a round card. */
    private ImageView round(int icon, int label, View.OnClickListener onClick) {
        ImageView b = plain(icon, label, onClick);
        b.clearColorFilter();
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(ContextCompat.getColor(context, R.color.surface));
        circle.setStroke(Math.round(1 * density), ContextCompat.getColor(context, R.color.line));
        b.setBackground(circle);
        return b;
    }

    private void dropViews() {
        root = null;
        mapView = null;
        painter = null;
        arrow = null;
        title = null;
        line = null;
        trip = null;
        battery = null;
        recenter = null;
        go = null;
        gestures = null;
    }

    // ---- size and place ----

    private DisplayMetrics screen() {
        return context.getResources().getDisplayMetrics();
    }

    /** Never smaller than the minimum, never bigger than the screen. */
    private void fitSize() {
        DisplayMetrics dm = screen();
        lp.width = Math.max(Math.round(MIN_W_DP * density), Math.min(lp.width, dm.widthPixels));
        lp.height = Math.max(Math.round(MIN_H_DP * density), Math.min(lp.height, dm.heightPixels));
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
                .putFloat("w", (float) lp.width / dm.widthPixels)
                .putFloat("h", (float) lp.height / dm.heightPixels)
                .apply();
    }

    private void toggleSize() {
        DisplayMetrics dm = screen();
        boolean small = lp.width < (SMALL_W + LARGE_W) / 2 * dm.widthPixels;
        int centreX = lp.x + lp.width / 2;
        int centreY = lp.y + lp.height / 2;
        lp.width = Math.round((small ? LARGE_W : SMALL_W) * dm.widthPixels);
        lp.height = Math.round((small ? LARGE_H : SMALL_H) * dm.heightPixels);
        fitSize();
        lp.x = Math.max(0, Math.min(centreX - lp.width / 2, dm.widthPixels - lp.width));
        lp.y = Math.max(0, Math.min(centreY - lp.height / 2, dm.heightPixels - lp.height));
        update();
        save();
    }

    /** The bar: move and dock like the AirPlay window; double-tap for small / large. */
    private final class MoveTouch implements View.OnTouchListener {
        private final int slop = android.view.ViewConfiguration.get(context).getScaledTouchSlop();
        private float downX;
        private float downY;
        /** The finger moved past the slop since it went down: a drag, not a tap. */
        private boolean dragged;
        /**
         * Small / large on a double-tap, but only once the second tap lifts without moving: switching
         * on its touch-down made a tap followed by a quick drag jump size under the finger.
         */
        private final GestureDetector taps = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTapEvent(MotionEvent e) {
                        if (e.getActionMasked() == MotionEvent.ACTION_UP && !dragged) {
                            toggleSize();
                        }
                        return true;
                    }
                });

        MoveTouch() {
            gestures = new FloatingWindowGestures(context, new FloatingWindowGestures.Host() {
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
                    return Math.round(MIN_H_DP * density);
                }

                @Override
                public boolean keepShape() {
                    // any width, any height: the map is not a picture with a shape to keep
                    return false;
                }

                @Override
                public void apply() {
                    update();
                }

                @Override
                public void save() {
                    NavFloatWindow.this.save();
                }
            });
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = e.getRawX();
                downY = e.getRawY();
                dragged = false;
            } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE && !dragged
                    && Math.hypot(e.getRawX() - downX, e.getRawY() - downY) > slop) {
                dragged = true;
            } else if (e.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
                // a pinch is no tap
                dragged = true;
            }
            if (e.getPointerCount() == 1) {
                taps.onTouchEvent(e);
            }
            return gestures.onTouch(e);
        }
    }

    /** The corner grip: width and height follow the finger separately; the top-left corner stays. */
    private final class ResizeTouch implements View.OnTouchListener {
        private float startX;
        private float startY;
        private int startW;
        private int startH;

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = e.getRawX();
                    startY = e.getRawY();
                    startW = lp.width;
                    startH = lp.height;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    DisplayMetrics dm = screen();
                    lp.width = Math.min(startW + Math.round(e.getRawX() - startX), dm.widthPixels - Math.max(0, lp.x));
                    lp.height = Math.min(startH + Math.round(e.getRawY() - startY), dm.heightPixels - Math.max(0, lp.y));
                    fitSize();
                    update();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    save();
                    return true;
                default:
                    return true;
            }
        }
    }

    /** Three short diagonal lines in the corner: grab here to resize. */
    private static final class Grip extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint back = new Paint(Paint.ANTI_ALIAS_FLAG);

        Grip(Context context) {
            super(context);
            float d = context.getResources().getDisplayMetrics().density;
            paint.setColor(0xFFF0622D);
            paint.setStrokeWidth(3.5f * d);
            paint.setStrokeCap(Paint.Cap.ROUND);
            back.setColor(0xB3101114);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            // a dark quarter-circle in the corner, so the lines show on any map
            c.drawCircle(w, h, w * 0.85f, back);
            float m = w * 0.18f;
            for (int i = 1; i <= 3; i++) {
                float t = i * (w - m) / 4.5f;
                c.drawLine(w - m - t, h - m, w - m, h - m - t, paint);
            }
        }
    }
}
