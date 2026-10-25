package com.kooo.evcam.analytics;

import android.app.Activity;
import android.widget.CheckBox;
import android.widget.FrameLayout;

import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;

/**
 * The first-open question: turn anonymous usage stats on or off. Nothing is sent before an answer.
 *
 * <p>With "Remember my choice" ticked (or the switch set in Settings) it never asks again;
 * without, the answer holds for this run and the next open asks again. Both flags live in
 * app_config, so the USB settings copy brings them back after a reinstall.</p>
 */
public final class UsageConsent {

    /** Asked (or showing) in this process: one question per open. */
    private static boolean askedThisRun;

    private UsageConsent() {
    }

    /** Pure: whether to ask now. Builds without GA keys send nothing, so they never ask. */
    static boolean shouldAsk(boolean configured, boolean remembered, boolean askedThisRun) {
        return configured && !remembered && !askedThisRun;
    }

    /** Pure: whether sending starts with the process (before any question this run). */
    static boolean sendsAtStart(boolean enabled, boolean remembered) {
        return enabled && remembered;
    }

    /** From MainActivity.onResume. */
    public static void maybeAsk(Activity activity) {
        AppConfig config = new AppConfig(activity);
        if (!shouldAsk(Analytics.configured(), config.isUsageStatsRemembered(), askedThisRun)) {
            return;
        }
        askedThisRun = true;
        float density = activity.getResources().getDisplayMetrics().density;
        CheckBox remember = new CheckBox(activity);
        remember.setText(R.string.usage_consent_remember);
        remember.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
        FrameLayout box = new FrameLayout(activity);
        int pad = Math.round(20 * density);
        box.setPadding(pad, Math.round(8 * density), pad, 0);
        box.addView(remember);
        com.kooo.evcam.ui.CamDialogs.show(new MaterialAlertDialogBuilder(activity, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.usage_consent_title)
                .setMessage(R.string.usage_consent_msg)
                .setView(box)
                .setPositiveButton(R.string.usage_consent_on, (d, w) -> answer(activity, true, remember.isChecked()))
                .setNegativeButton(R.string.usage_consent_off, (d, w) -> answer(activity, false, remember.isChecked())));
        // Closed without an answer (back, outside tap): nothing is sent, the next open asks again
    }

    private static void answer(Activity activity, boolean on, boolean remember) {
        new AppConfig(activity).setUsageStats(on, remember);
        Analytics.setEnabled(on);
    }
}
