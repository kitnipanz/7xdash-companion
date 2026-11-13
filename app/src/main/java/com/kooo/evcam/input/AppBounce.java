package com.kooo.evcam.input;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.MainActivity;

/**
 * Minimise and open the app over and over: {@link #STEPS} steps, {@link #STEP_MS} apart. The
 * first step opens 7xDash on its Home page, the next goes to the head unit's home screen, and so
 * on, so 60 steps are 30 opens and 30 minimises. Pressing the shortcut again while it runs stops
 * it where it is.
 */
public final class AppBounce {

    static final int STEPS = 60;
    static final long STEP_MS = 500L;
    private static final String TAG = "AppBounce";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Runnable running;

    private AppBounce() {
    }

    /** Starts it, or stops it when it is already going. */
    static void toggle(Context context) {
        Context app = context.getApplicationContext();
        MAIN.post(() -> {
            if (running != null) {
                MAIN.removeCallbacks(running);
                running = null;
                AppLog.i(TAG, "stopped");
                return;
            }
            AppLog.i(TAG, "start: " + STEPS + " steps, " + STEP_MS + " ms apart");
            step(app, 0);
        });
    }

    /** Main thread. Even steps open the app, odd steps go home. */
    private static void step(Context app, int index) {
        if (index >= STEPS) {
            running = null;
            AppLog.i(TAG, "done");
            return;
        }
        try {
            app.startActivity(opensApp(index) ? appHome(app) : screenHome());
        } catch (RuntimeException e) {
            AppLog.w(TAG, "step " + index + " refused: " + e);
        }
        running = () -> step(app, index + 1);
        MAIN.postDelayed(running, STEP_MS);
    }

    /** Step 0 opens the app, step 1 goes home, step 2 opens it again… */
    static boolean opensApp(int index) {
        return index % 2 == 0;
    }

    private static Intent appHome(Context app) {
        return new Intent(app, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_PAGE, MainActivity.PAGE_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
    }

    private static Intent screenHome() {
        return new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
