package com.kooo.evcam.browser;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.BuildConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.airplay.DrivingGate;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;

import java.util.ArrayList;
import java.util.List;

/**
 * Hides the Netflix app's picture while the car is in gear or moving, the same rule as AirPlay
 * and the browser: a black card over each Netflix window (full screen, split screen or
 * picture-in-picture). The sound keeps playing. Uses the accessibility service's window list to
 * know where Netflix is on the screen; only the window positions and which app owns them are
 * looked at, nothing inside them.
 *
 * <p>Debug builds only, for the emulator: {@code adb shell setprop debug.sevenx.driving 1} counts
 * as driving.</p>
 */
public final class NetflixGuard {

    private static final String TAG = "NetflixGuard";
    private static final String TELEMETRY_USER = "netflix-guard";
    private static final NetflixGuard INSTANCE = new NetflixGuard();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<View> covers = new ArrayList<>();
    private final List<Rect> netflix = new ArrayList<>();
    private Context app;
    private boolean watchingCar;

    private NetflixGuard() {
    }

    public static NetflixGuard get() {
        return INSTANCE;
    }

    /** The accessibility service saw windows change. Main thread. */
    public void onWindowsChanged(AccessibilityService service) {
        app = service.getApplicationContext();
        List<Rect> found = new ArrayList<>();
        List<AccessibilityWindowInfo> windows;
        try {
            windows = service.getWindows();
        } catch (RuntimeException e) {
            return;
        }
        for (AccessibilityWindowInfo w : windows) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                continue;
            }
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) {
                continue;
            }
            CharSequence pkg = root.getPackageName();
            if (NetflixApp.PACKAGE.contentEquals(pkg == null ? "" : pkg)) {
                Rect r = new Rect();
                w.getBoundsInScreen(r);
                if (!r.isEmpty()) {
                    found.add(r);
                }
            }
        }
        if (found.equals(netflix)) {
            return;
        }
        netflix.clear();
        netflix.addAll(found);
        watchCar(!netflix.isEmpty());
        apply();
    }

    private void watchCar(boolean on) {
        if (on == watchingCar || app == null) {
            return;
        }
        watchingCar = on;
        if (on) {
            Telemetry.get().acquire(app, TELEMETRY_USER);
            Telemetry.get().addListener(carListener);
        } else {
            Telemetry.get().removeListener(carListener);
            Telemetry.get().release(TELEMETRY_USER);
        }
    }

    private final Telemetry.Listener carListener = readings -> main.post(this::apply);

    private boolean driving() {
        if (BuildConfig.DEBUG && "1".equals(debugProp())) {
            return true;
        }
        if (app != null && BrowserPrefs.showWhileDriving(app)) {
            return false;
        }
        Readings r = Telemetry.get().readings();
        return DrivingGate.blocked(r.text(Signal.GEAR), Telemetry.get().latest().speedKmh);
    }

    /** Covers every Netflix window while driving, none otherwise. Main thread. */
    public void apply() {
        boolean hide = !netflix.isEmpty() && driving();
        List<Rect> want = hide ? netflix : new ArrayList<>();
        if (covers.size() == want.size()) {
            boolean same = true;
            for (int i = 0; i < want.size(); i++) {
                Rect at = (Rect) covers.get(i).getTag();
                same &= want.get(i).equals(at);
            }
            if (same) {
                return;
            }
        }
        removeCovers();
        if (want.isEmpty() || app == null || !Settings.canDrawOverlays(app)) {
            return;
        }
        WindowManager wm = app.getSystemService(WindowManager.class);
        for (Rect r : want) {
            View cover = DrivingCover.build(new android.view.ContextThemeWrapper(
                    com.kooo.evcam.settings.Languages.localized(app), R.style.Theme_Cam),
                    R.string.netflix_paused_driving, this::apply);
            cover.setTag(new Rect(r));
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(r.width(), r.height(),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.OPAQUE);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = r.left;
            lp.y = r.top;
            try {
                wm.addView(cover, lp);
                covers.add(cover);
            } catch (RuntimeException e) {
                AppLog.w(TAG, "cover refused: " + e);
            }
        }
        AppLog.i(TAG, "Netflix hidden while driving: " + covers.size() + " window(s)");
    }

    private void removeCovers() {
        if (covers.isEmpty() || app == null) {
            return;
        }
        WindowManager wm = app.getSystemService(WindowManager.class);
        for (View v : covers) {
            try {
                wm.removeView(v);
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
        covers.clear();
        AppLog.i(TAG, "Netflix shown");
    }

    private static String debugProp() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (String) sp.getMethod("get", String.class).invoke(null, "debug.sevenx.driving");
        } catch (Throwable t) {
            return "";
        }
    }
}
