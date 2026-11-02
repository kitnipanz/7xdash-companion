package com.kooo.evcam.update;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Shows {@link WhatsNew} once, the first time the app opens after an update, and on demand from
 * Settings → Check for updates → What's new.
 */
public final class WhatsNewDialog {

    private static final String TAG = "WhatsNew";
    private static final String PREFS = "whats_new";
    private static final String KEY_SEEN = "seen_version";
    /** Settings → What's new shows this many versions. */
    private static final int LATEST = 3;

    private WhatsNewDialog() {
    }

    /** App start (not the very first launch): show the notes if this version is new to the driver. */
    public static void maybeShow(Activity activity) {
        Context app = activity.getApplicationContext();
        String current = UpdateFlow.currentVersion(app);
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String seen = prefs.getString(KEY_SEEN, null);
        if (current.equals(seen)) {
            return;
        }
        prefs.edit().putString(KEY_SEEN, current).apply();
        String text;
        if (seen == null) {
            // the first version with this screen: only an update gets notes, a fresh install does not
            text = updated(app) ? WhatsNew.latest(changelog(app), current, 1) : "";
        } else {
            text = WhatsNew.since(changelog(app), seen, current);
        }
        if (!text.isEmpty()) {
            show(activity, current, text);
        }
    }

    /** Settings: the latest few versions, whatever was seen. */
    public static void showLatest(Activity activity) {
        String current = UpdateFlow.currentVersion(activity);
        String text = WhatsNew.latest(changelog(activity), current, LATEST);
        if (text.isEmpty()) {
            text = activity.getString(R.string.whats_new_none);
        }
        show(activity, current, text);
    }

    private static void show(Activity activity, String version, String text) {
        if (activity.isFinishing()) {
            return;
        }
        androidx.appcompat.app.AlertDialog dialog = com.kooo.evcam.ui.CamDialogs.show(
                new MaterialAlertDialogBuilder(activity, R.style.Theme_Cam_MaterialAlertDialog)
                        .setTitle(activity.getString(R.string.whats_new_title, version))
                        .setMessage(styled(activity, text))
                        .setPositiveButton(R.string.action_got_it, null));
        android.widget.TextView message = dialog == null ? null : dialog.findViewById(android.R.id.message);
        if (message != null) {
            message.setLineSpacing(0f, 1.2f);
        }
    }

    /**
     * Version labels ({@code [1.6.9-beta]}) in bold orange without the brackets, section headings in
     * bold, bullets as they are: the plain text from {@link WhatsNew} reads like the changelog.
     */
    static CharSequence styled(Context context, String text) {
        android.text.SpannableStringBuilder out = new android.text.SpannableStringBuilder();
        int accent = androidx.core.content.ContextCompat.getColor(context, R.color.energy_text);
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int start = out.length();
            boolean version = line.startsWith("[") && line.endsWith("]");
            out.append(version ? line.substring(1, line.length() - 1) : line);
            int end = out.length();
            if (version) {
                out.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, end, 0);
                out.setSpan(new android.text.style.ForegroundColorSpan(accent), start, end, 0);
                out.setSpan(new android.text.style.RelativeSizeSpan(1.15f), start, end, 0);
            } else if (!line.isEmpty() && !line.startsWith("• ") && !line.equals("…")) {
                out.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, end, 0);
            }
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out;
    }

    private static boolean updated(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.lastUpdateTime > info.firstInstallTime;
        } catch (Exception e) {
            return false;
        }
    }

    static String changelog(Context context) {
        try (InputStream in = context.getAssets().open("CHANGELOG.md")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AppLog.w(TAG, "no CHANGELOG in the APK: " + e.getMessage());
            return "";
        }
    }
}
