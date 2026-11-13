package com.kooo.evcam.browser;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import com.kooo.evcam.AppLog;

/**
 * The Netflix Android app. Netflix plays only in its own app on Android (its website refuses an
 * Android device's copy protection), so the Netflix buttons open the app when it is installed and
 * fall back to the browser when it is not. While it shows, {@link NetflixGuard} hides its picture
 * when the car is driving.
 */
public final class NetflixApp {

    public static final String PACKAGE = "com.netflix.mediaclient";
    private static final String TAG = "NetflixApp";

    private NetflixApp() {
    }

    public static boolean installed(Context context) {
        try {
            context.getPackageManager().getPackageInfo(PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Opens the Netflix app, or Netflix in the browser when the app is not installed. */
    public static void open(Context context) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(PACKAGE);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(launch);
                AppLog.i(TAG, "opened the Netflix app");
                return;
            } catch (RuntimeException e) {
                AppLog.w(TAG, "could not open the Netflix app: " + e);
            }
        }
        context.startActivity(new Intent(context, BrowserActivity.class)
                .putExtra(BrowserActivity.EXTRA_URL, "https://www.netflix.com/browse")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
