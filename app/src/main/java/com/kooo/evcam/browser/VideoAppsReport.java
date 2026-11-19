package com.kooo.evcam.browser;

import android.content.Context;

import com.kooo.evcam.screen.AppWindow;

/** The Diagnostics section on the Netflix and YouTube apps the Home cards open. */
public final class VideoAppsReport {

    private VideoAppsReport() {
    }

    public static void appendTo(StringBuilder sb, Context context) {
        AppWindow.appendVideoApps(sb, context, NetflixApp.PACKAGES, YouTubeApp.PACKAGES);
    }
}
