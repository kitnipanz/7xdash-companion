package com.kooo.evcam.browser;

import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.kooo.evcam.R;
import com.kooo.evcam.screen.AppWindow;
import com.kooo.evcam.ui.Notice;

/**
 * The YouTube app on the head unit: Google's, or one of the other players people put on cars when
 * it isn't there. Opened in a floating window from its Home card and the shortcut action.
 */
public final class YouTubeApp {

    /** First installed wins: Google's own, then the usual stand-ins. YouTube Music is left out (it is for listening). */
    static final String[] PACKAGES = {
            "com.google.android.youtube",
            "app.revanced.android.youtube",
            "org.smarttube.stable",
            "com.teamsmart.videomanager.tv",
            "org.smarttube.beta",
            "org.schabi.newpipe",
            "com.github.libretube",
            "com.google.android.youtube.tv",
    };

    private YouTubeApp() {
    }

    /** How to open the first installed YouTube player, or null when there is none. */
    static Intent launchIntent(Context context) {
        for (String p : PACKAGES) {
            Intent launch = AppWindow.launchIntent(context, p);
            if (launch != null) {
                return launch;
            }
        }
        return null;
    }

    /** YouTube in a floating window where the head unit has them ({@link AppWindow}), else as it allows. */
    public static void openInWindow(Context context) {
        Intent launch = launchIntent(context);
        if (launch == null) {
            Notice.makeText(context, R.string.youtube_not_installed, Toast.LENGTH_LONG).show();
            return;
        }
        if (AppWindow.open(context, launch.getComponent()) == AppWindow.Mode.FULL) {
            Notice.makeText(context, R.string.app_window_opened_full, Toast.LENGTH_LONG).show();
        }
    }
}
