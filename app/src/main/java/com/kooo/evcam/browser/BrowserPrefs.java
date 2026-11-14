package com.kooo.evcam.browser;

import android.content.Context;
import android.content.SharedPreferences;

/** What the browser remembers: the last page, and whether the picture shows while driving. */
public final class BrowserPrefs {

    static final String HOME = "https://www.google.com/";
    private static final String PREFS = "browser";
    private static final String KEY_LAST = "last_url";
    private static final String KEY_WHILE_DRIVING = "show_while_driving";

    private BrowserPrefs() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String lastUrl(Context context) {
        String url = prefs(context).getString(KEY_LAST, null);
        return url == null || url.isEmpty() ? HOME : url;
    }

    static void setLastUrl(Context context, String url) {
        if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
            prefs(context).edit().putString(KEY_LAST, url).apply();
        }
    }

    /** Show the picture while driving (for passengers). Off by default, like AirPlay. */
    public static boolean showWhileDriving(Context context) {
        return prefs(context).getBoolean(KEY_WHILE_DRIVING, false);
    }

    public static void setShowWhileDriving(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_WHILE_DRIVING, on).apply();
    }

    /** What was typed in the address box as a page to open: a URL, a bare host, or a search. */
    static String toUrl(String typed) {
        String t = typed == null ? "" : typed.trim();
        if (t.isEmpty()) {
            return HOME;
        }
        if (t.startsWith("http://") || t.startsWith("https://")) {
            return t;
        }
        if (!t.contains(" ") && t.contains(".")) {
            return "https://" + t;
        }
        try {
            return "https://www.google.com/search?q=" + java.net.URLEncoder.encode(t, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return HOME;
        }
    }
}
