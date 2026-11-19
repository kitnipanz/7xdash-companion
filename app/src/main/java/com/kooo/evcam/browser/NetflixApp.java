package com.kooo.evcam.browser;

import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.kooo.evcam.R;
import com.kooo.evcam.screen.AppWindow;
import com.kooo.evcam.ui.Notice;

/**
 * The Netflix Android app: opened in a floating window from its Home card and the shortcut action,
 * and hidden while the car is driving by {@link NetflixGuard}, which knows it by its package name.
 */
public final class NetflixApp {

    public static final String PACKAGE = "com.netflix.mediaclient";
    /** First installed wins: the phone / tablet app, then the Android TV one some head units carry. */
    static final String[] PACKAGES = {PACKAGE, "com.netflix.ninja"};

    private NetflixApp() {
    }

    static Intent launchIntent(Context context) {
        for (String p : PACKAGES) {
            Intent launch = AppWindow.launchIntent(context, p);
            if (launch != null) {
                return launch;
            }
        }
        return null;
    }

    /**
     * Netflix in a floating window where the head unit has them ({@link AppWindow}), else as the
     * head unit allows. Says so when Netflix isn't installed or could only open full screen.
     */
    public static void openInWindow(Context context) {
        Intent launch = launchIntent(context);
        if (launch == null) {
            Notice.makeText(context, R.string.netflix_not_installed, Toast.LENGTH_LONG).show();
            return;
        }
        if (AppWindow.open(context, launch.getComponent()) == AppWindow.Mode.FULL) {
            Notice.makeText(context, R.string.app_window_opened_full, Toast.LENGTH_LONG).show();
        }
    }
}
