package com.kooo.evcam.nav;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.overlay.OverlayCoordinator;
import com.kooo.evcam.zeekr.HudDisplay;

/**
 * The next turn on the HUD while guiding: arrow, distance, and the road. Stays up and changes in
 * place (unlike {@link com.kooo.evcam.hud.HudNoticeWindow}'s cards, which come and go). Placed where
 * it was set in the Navigation screen ({@link HudTurnPlace}); until then in the part of the HUD the
 * notice cards use, so Settings' left and right edges apply. The card
 * itself is {@link HudTurnCard}, which the Navigation screen also shows as a preview. Main thread.
 */
final class HudTurnWindow {

    private static final String TAG = "HudTurnWindow";

    private WindowManager wm;
    private FrameLayout root;
    private HudTurnCard card;
    private boolean failed;

    /** Shows (or updates) the card. Does nothing on a car without a HUD. */
    void show(Context context, Navigator.HudLine line) {
        if (root == null && !open(context)) {
            return;
        }
        card.set(line);
    }

    void hide() {
        if (wm != null && root != null) {
            try {
                wm.removeView(root);
            } catch (RuntimeException ignored) {
                // gone with the display
            }
        }
        wm = null;
        root = null;
        card = null;
        failed = false;
    }

    private boolean open(Context context) {
        if (failed || Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !OverlayCoordinator.canShowOverlay(context)) {
            return false;
        }
        Display hud = HudDisplay.find(context);
        if (hud == null) {
            failed = true;
            return false;
        }
        try {
            Context window = context.getApplicationContext().createWindowContext(hud,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            WindowManager manager = window.getSystemService(WindowManager.class);
            Point size = HudDisplay.sizeOf(hud);
            AppConfig config = new AppConfig(context);
            int[] box = HudTurnPlace.place(size.x, size.y, HudTurnPlace.restore(config.getNavHudPlace()),
                    config.getHudNoticeLeftPercent(), config.getHudNoticeRightPercent());
            int height = box[3];

            card = new HudTurnCard(window, height);

            FrameLayout frame = new FrameLayout(window);
            FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(box[2], box[3]);
            at.gravity = Gravity.TOP | Gravity.START;
            at.leftMargin = box[0];
            at.topMargin = box[1];
            frame.addView(card.view, at);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    box[0] + box[2], box[1] + box[3],
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            manager.addView(frame, lp);
            wm = manager;
            root = frame;
            return true;
        } catch (RuntimeException e) {
            failed = true;
            AppLog.w(TAG, "no turn card on the HUD: " + e.getMessage());
            return false;
        }
    }
}
