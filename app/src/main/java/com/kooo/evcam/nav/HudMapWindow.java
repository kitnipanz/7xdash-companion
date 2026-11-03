package com.kooo.evcam.nav;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.maps.MapView;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.overlay.OverlayCoordinator;
import com.kooo.evcam.zeekr.HudDisplay;

/**
 * A live map on the HUD while guiding: the route and the car, heading up, following like the full
 * screen ({@link NavMapPainter}), always dark (it reads better in the windscreen). Where it sits and
 * its size are set with the turn card's ({@link HudTurnPlace}, {@link HudPlacementView}). Does
 * nothing on a car without a HUD or without Google's map. Main thread.
 */
final class HudMapWindow implements Navigator.Listener {

    private static final String TAG = "HudMapWindow";

    private WindowManager wm;
    private FrameLayout root;
    private WindowManager.LayoutParams lp;
    private Point hudSize;
    private MapView mapView;
    private NavMapPainter painter;
    private boolean failed;

    boolean isShown() {
        return root != null;
    }

    void show(Context context) {
        if (root != null || failed || Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                || !OverlayCoordinator.canShowOverlay(context) || !Navigator.hasKey()
                || GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) {
            return;
        }
        Display hud = HudDisplay.find(context);
        if (hud == null) {
            failed = true;
            return;
        }
        try {
            Context window = context.getApplicationContext().createWindowContext(hud,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            WindowManager manager = window.getSystemService(WindowManager.class);
            Point size = HudDisplay.sizeOf(hud);
            hudSize = size;
            int[] box = box(context);

            float radius = Math.max(8, box[3] / 14f);
            FrameLayout frame = new FrameLayout(window);
            frame.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            frame.setClipToOutline(true);
            NavMapPainter.useLatestRenderer(window);
            mapView = new MapView(window);
            mapView.onCreate(null);
            frame.addView(mapView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

            lp = new WindowManager.LayoutParams(box[2], box[3],
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = box[0];
            lp.y = box[1];
            manager.addView(frame, lp);
            wm = manager;
            root = frame;
            mapView.onStart();
            mapView.onResume();
            mapView.getMapAsync(map -> {
                if (root == null) {
                    return;
                }
                painter = new NavMapPainter(window, map, mapView, 0, 0);
                painter.applyScheme(AppConfig.NAV_MAP_DARK);
                painter.trafficAllowed = false;
                map.getUiSettings().setAllGesturesEnabled(false);
                map.getUiSettings().setCompassEnabled(false);
                map.setTrafficEnabled(false);
                painter.draw(Navigator.get().snapshot());
            });
            Navigator.get().addListener(this);
            AppLog.i(TAG, "map on the HUD " + box[2] + "x" + box[3] + " at " + box[0] + "," + box[1]);
        } catch (RuntimeException e) {
            failed = true;
            AppLog.w(TAG, "no map on the HUD: " + e.getMessage());
            hide();
        }
    }

    void hide() {
        Navigator.get().removeListener(this);
        if (mapView != null) {
            try {
                mapView.onPause();
                mapView.onStop();
                mapView.onDestroy();
            } catch (RuntimeException ignored) {
                // already torn down with the display
            }
        }
        if (wm != null && root != null) {
            try {
                wm.removeView(root);
            } catch (RuntimeException ignored) {
                // gone with the display
            }
        }
        wm = null;
        root = null;
        lp = null;
        mapView = null;
        painter = null;
    }

    /** Its place or size changed: move the window, keeping the map. */
    void relayout(Context context) {
        if (root == null || lp == null) {
            return;
        }
        int[] box = box(context);
        lp.x = box[0];
        lp.y = box[1];
        lp.width = box[2];
        lp.height = box[3];
        try {
            wm.updateViewLayout(root, lp);
        } catch (RuntimeException ignored) {
            // gone with the display
        }
    }

    private int[] box(Context context) {
        float[] shares = HudTurnPlace.restore(new AppConfig(context).getNavHudMapPlace());
        return HudTurnPlace.toPixels(hudSize.x, hudSize.y, shares != null ? shares : HudTurnPlace.mapDefaults());
    }

    /** Try again after a failure, e.g. when the HUD comes back. */
    void reset() {
        failed = false;
    }

    @Override
    public void onNavChanged(Navigator.Snapshot s) {
        if (painter != null) {
            painter.draw(s);
        }
    }
}
