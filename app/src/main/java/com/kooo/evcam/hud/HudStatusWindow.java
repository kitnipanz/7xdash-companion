package com.kooo.evcam.hud;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.camera.RecordingLanes;
import com.kooo.evcam.recording.RecordingCoordinator;
import com.kooo.evcam.recording.RecordingStops;
import com.kooo.evcam.zeekr.HudDisplay;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * An always-on status card on the HUD: recording, cameras, USB stick, the last instant capture,
 * announcements, the time. Which lines show and where the
 * card sits are set in Settings ({@link HudStatus}, {@link HudStatusPlaceActivity}).
 *
 * <p>The HUD is a presentation screen, so this needs neither the overlay permission nor the
 * accessibility service: it is how the driver still sees the status on a car where the floating
 * windows can't open. Updates in place once a second. Main thread.</p>
 */
public final class HudStatusWindow {

    private static final String TAG = "HudStatusWindow";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long TICK_MS = 1000L;
    private static final long USB_EVERY_MS = 10_000L;
    /** 极氪橙，和 HudNoticeWindow 一样。 */
    private static final int ENERGY = 0xFFF0622D;
    private static final int RED = 0xFFFF4D4D;
    private static final int AMBER = 0xFFFFB020;
    private static final int GREEN = 0xFF4CD964;
    private static final int GREY = 0xFFB0B3B8;

    private static final HudStatusWindow INSTANCE = new HudStatusWindow();

    /** The last instant capture, from its own thread. */
    private static volatile long captureAtMs;
    private static volatile boolean captureSaved;

    private Context app;
    private WindowManager wm;
    private FrameLayout root;
    private Card card;
    private int[] box;
    private Point hudSize;
    private boolean previewing;
    private long usbCheckedAt;
    private String usbLine;
    private int usbColor;

    private final Runnable tick = this::tick;

    private HudStatusWindow() {
    }

    public static HudStatusWindow get() {
        return INSTANCE;
    }

    /** Instant capture finished: saved to the stick or not. Any thread. */
    public static void noteCapture(boolean saved) {
        captureSaved = saved;
        captureAtMs = System.currentTimeMillis();
    }

    /** Shows or hides the card as the settings say. Any thread. */
    public static void sync(Context context) {
        Context appContext = context.getApplicationContext();
        MAIN.post(() -> INSTANCE.syncNow(appContext));
    }

    /** While the placement screen is open, the card shows even when switched off. Main thread. */
    void setPreviewing(Context context, boolean on) {
        previewing = on;
        syncNow(context.getApplicationContext());
    }

    /** Moves or resizes the card while it is dragged on the placement screen. Main thread. */
    void place(float[] shares) {
        if (root == null || hudSize == null || card == null) {
            return;
        }
        box = HudStatus.toPixels(hudSize.x, hudSize.y, shares);
        FrameLayout.LayoutParams at = (FrameLayout.LayoutParams) card.view.getLayoutParams();
        at.width = box[2];
        at.leftMargin = box[0];
        at.topMargin = box[1];
        card.view.setLayoutParams(at);
        card.setHeight(box[3]);
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) root.getLayoutParams();
        lp.width = box[0] + box[2];
        lp.height = box[1] + box[3];
        try {
            com.kooo.evcam.overlay.OverlayAccess.update(wm, root, lp);
        } catch (RuntimeException e) {
            AppLog.w(TAG, "move: " + e.getMessage());
        }
    }

    private void syncNow(Context context) {
        app = context;
        if (previewing || new AppConfig(context).isHudStatusEnabled()) {
            if (root == null) {
                open();
            }
            MAIN.removeCallbacks(tick);
            tick();
        } else {
            hide();
        }
    }

    private void hide() {
        MAIN.removeCallbacks(tick);
        if (wm != null && root != null) {
            try {
                com.kooo.evcam.overlay.OverlayAccess.remove(wm, root);
            } catch (RuntimeException ignored) {
                // gone with the display
            }
        }
        wm = null;
        root = null;
        card = null;
        box = null;
        hudSize = null;
    }

    private boolean open() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                || !com.kooo.evcam.overlay.OverlayAccess.canDrawOnHud(app)) {
            return false;
        }
        Display hud = HudDisplay.find(app);
        if (hud == null) {
            return false;
        }
        try {
            Context window = com.kooo.evcam.overlay.OverlayAccess.windowContext(app, hud);
            WindowManager manager = window.getSystemService(WindowManager.class);
            Point size = HudDisplay.sizeOf(hud);
            AppConfig config = new AppConfig(app);
            int[] at = HudStatus.toPixels(size.x, size.y,
                    HudStatus.placeOr(config.getHudStatusPlace(), config.getHudNoticeRightPercent()));
            Card made = new Card(window, at[3]);
            // The window starts at (0,0) and the card sits inside it at (x, y), like the notice
            // cards: a window that doesn't start at the origin lands off on this HUD.
            FrameLayout frame = new FrameLayout(window);
            FrameLayout.LayoutParams in = new FrameLayout.LayoutParams(at[2], FrameLayout.LayoutParams.WRAP_CONTENT);
            in.gravity = Gravity.TOP | Gravity.START;
            in.leftMargin = at[0];
            in.topMargin = at[1];
            frame.addView(made.view, in);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    at[0] + at[2], at[1] + at[3],
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            com.kooo.evcam.overlay.OverlayAccess.add(manager, frame, lp);
            wm = manager;
            root = frame;
            card = made;
            box = at;
            hudSize = size;
            return true;
        } catch (RuntimeException e) {
            AppLog.w(TAG, "no status card on the HUD: " + e.getMessage());
            return false;
        }
    }

    private void tick() {
        if (card == null) {
            // the HUD wasn't there yet (head unit still waking): try again
            if (!open()) {
                MAIN.postDelayed(tick, 5 * TICK_MS);
                return;
            }
        }
        card.show(lines(app));
        MAIN.postDelayed(tick, TICK_MS);
    }

    // ------------------------------------------------------------------ lines

    /** One line on the card. */
    static final class Line {
        final String text;
        final int color;
        final boolean bold;

        Line(String text, int color, boolean bold) {
            this.text = text;
            this.color = color;
            this.bold = bold;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Line)) {
                return false;
            }
            Line l = (Line) o;
            return color == l.color && bold == l.bold && text.equals(l.text);
        }

        @Override
        public int hashCode() {
            return text.hashCode() * 31 + color;
        }
    }

    private List<Line> lines(Context c) {
        AppConfig config = new AppConfig(c);
        List<Line> out = new ArrayList<>();
        RecordingCoordinator rec = RecordingCoordinator.get(c);
        if (config.isHudStatusItemOn(HudStatus.Item.RECORDING)) {
            out.add(recording(c, rec));
        }
        if (config.isHudStatusItemOn(HudStatus.Item.CAMERAS)) {
            RecordingLanes.Summary s = rec.summary();
            if (s != null && !s.planned.isEmpty()) {
                int live = s.liveKeys.size();
                int planned = s.planned.size();
                out.add(new Line(c.getString(R.string.hud_status_cameras, live, planned),
                        live >= planned ? GREEN : AMBER, false));
            }
        }
        if (config.isHudStatusItemOn(HudStatus.Item.USB)) {
            usb(c);
            out.add(new Line(usbLine, usbColor, false));
        }
        if (config.isHudStatusItemOn(HudStatus.Item.CAPTURE)) {
            long at = captureAtMs;
            if (at > 0 && System.currentTimeMillis() - at < HudStatus.CAPTURE_SHOW_MS) {
                out.add(captureSaved
                        ? new Line(c.getString(R.string.hud_status_capture_saved), GREEN, true)
                        : new Line(c.getString(R.string.hud_status_capture_failed), RED, true));
            }
        }
        if (config.isHudStatusItemOn(HudStatus.Item.ANNOUNCE)) {
            String saying = com.kooo.evcam.announce.Announcer.speaking();
            long failed = com.kooo.evcam.announce.Announcer.failedAtMs();
            if (saying != null) {
                out.add(new Line("🔊 " + HudStatus.clip(saying), ENERGY, true));
            } else if (failed > 0 && System.currentTimeMillis() - failed < HudStatus.ANNOUNCE_FAIL_SHOW_MS) {
                out.add(new Line(c.getString(R.string.hud_status_announce_failed), RED, false));
            }
        }
        if (config.isHudStatusItemOn(HudStatus.Item.CLOCK)) {
            out.add(new Line(new SimpleDateFormat("HH:mm", Locale.US).format(new Date()), Color.WHITE, false));
        }
        return out;
    }

    private static Line recording(Context c, RecordingCoordinator rec) {
        switch (rec.view()) {
            case RECORDING: {
                long since = rec.liveSinceElapsedMs();
                if (since <= 0) {
                    since = rec.startedAtElapsedMs();
                }
                long ms = since > 0 ? SystemClock.elapsedRealtime() - since : 0;
                return new Line("● " + c.getString(R.string.hud_status_rec, HudStatus.duration(ms)), RED, true);
            }
            case PREPARING:
                return new Line(c.getString(R.string.hud_status_starting), AMBER, true);
            case WAITING:
                return new Line(c.getString(R.string.hud_status_waiting), AMBER, true);
            default:
                break;
        }
        if (rec.isWaiting()) {
            return new Line(c.getString(R.string.hud_status_starting), AMBER, true);
        }
        RecordingStops.Reason why = rec.lastStopReason();
        if (why == RecordingStops.Reason.STORAGE_FULL || why == RecordingStops.Reason.STORAGE_CANNOT_FREE
                || why == RecordingStops.Reason.STORAGE_LOCKED) {
            return new Line("■ " + c.getString(R.string.hud_status_stopped_usb), RED, true);
        }
        if (why != null && why != RecordingStops.Reason.USER && why != RecordingStops.Reason.SCREEN_OFF) {
            return new Line("■ " + c.getString(R.string.hud_status_stopped_error), RED, true);
        }
        return new Line("■ " + c.getString(R.string.hud_status_idle), GREY, true);
    }

    private void usb(Context c) {
        long now = SystemClock.elapsedRealtime();
        if (usbLine != null && now - usbCheckedAt < USB_EVERY_MS) {
            return;
        }
        usbCheckedAt = now;
        File root = StorageHelper.getExternalSdCardRoot(c);
        if (root == null || !root.exists()) {
            usbLine = c.getString(R.string.hud_status_no_usb);
            usbColor = RED;
            return;
        }
        long free = root.getUsableSpace();
        usbLine = c.getString(R.string.hud_status_usb_free, HudStatus.size(free));
        usbColor = free < 2_000_000_000L ? AMBER : GREY;
    }

    // ------------------------------------------------------------------ the card

    /**
     * The card itself; the placement screen shows one too, as a preview. Type sizes follow the
     * card's height: HUD densities differ from car to car, so dp would come out too big or small.
     */
    static final class Card {
        final LinearLayout view;
        private final Context context;
        private final GradientDrawable bg = new GradientDrawable();
        private List<Line> showing = new ArrayList<>();
        private int height;

        Card(Context context, int height) {
            this.context = context;
            view = new LinearLayout(context);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            bg.setColor(0xD9101114);
            view.setBackground(bg);
            setHeight(height);
        }

        /** The box's height: sets the type size, about six lines fill it. */
        void setHeight(int h) {
            height = Math.max(1, h);
            int pad = Math.max(4, height / 30);
            view.setPadding(pad * 2, pad, pad * 2, pad);
            bg.setCornerRadius(height / 14f);
            bg.setStroke(Math.max(2, height / 90), ENERGY);
            List<Line> again = showing;
            showing = new ArrayList<>();
            show(again);
        }

        void show(List<Line> lines) {
            if (lines.equals(showing)) {
                return;
            }
            showing = new ArrayList<>(lines);
            view.removeAllViews();
            view.setVisibility(lines.isEmpty() ? View.GONE : View.VISIBLE);
            float px = height / 7.5f;
            for (Line line : lines) {
                TextView t = new TextView(context);
                t.setText(line.text);
                t.setTextSize(TypedValue.COMPLEX_UNIT_PX, line.bold ? px * 1.1f : px);
                t.setTextColor(line.color);
                t.setSingleLine(false);
                t.setIncludeFontPadding(false);
                t.setPadding(0, Math.round(px * 0.08f), 0, Math.round(px * 0.08f));
                if (line.bold) {
                    t.setTypeface(Typeface.DEFAULT_BOLD);
                }
                view.addView(t);
            }
        }

        /** Sample lines for the placement preview. */
        static List<Line> sample(Context c) {
            List<Line> out = new ArrayList<>();
            out.add(new Line("● " + c.getString(R.string.hud_status_rec, "12:34"), RED, true));
            out.add(new Line(c.getString(R.string.hud_status_cameras, 4, 4), GREEN, false));
            out.add(new Line(c.getString(R.string.hud_status_usb_free, "23.4 GB"), GREY, false));
            out.add(new Line("18:05", Color.WHITE, false));
            return out;
        }
    }
}
