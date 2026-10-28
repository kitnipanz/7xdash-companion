package com.kooo.evcam.analytics;

import android.app.Activity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;

/**
 * The first-open question: send anonymous crash reports and usage stats, crash reports only, or
 * nothing. Nothing is sent before an answer, and an answer is remembered (Settings → Interface
 * changes it later). Closed without an answer, the next open asks again.
 *
 * <p>People who said no to the question before {@link #QUESTION} are asked this one once more:
 * it now explains what is sent and lets crash reports go without usage stats. People who said yes
 * are not asked; their yes covers crash reports too ({@link AppConfig#isCrashReportsEnabled}).
 * The flags live in app_config, so the USB settings copy brings them back after a reinstall.</p>
 */
public final class UsageConsent {

    /** The question's version. Raise it to ask people who said no once more. */
    static final int QUESTION = 2;

    /** Asked (or showing) in this process: one question per open. */
    private static boolean askedThisRun;

    private UsageConsent() {
    }

    /**
     * Pure: whether to ask now. Builds without GA keys send nothing, so they never ask. Asks until
     * there is a remembered answer, and once more after a new {@link #QUESTION} when it was no.
     */
    static boolean shouldAsk(boolean configured, boolean remembered, boolean askedThisRun,
                             boolean usageOn, int askedVersion) {
        if (!configured || askedThisRun) {
            return false;
        }
        return !remembered || (!usageOn && askedVersion < QUESTION);
    }

    /** Pure: whether sending starts with the process (before any question this run). */
    static boolean sendsAtStart(boolean enabled, boolean remembered) {
        return enabled && remembered;
    }

    /** From MainActivity.onResume. */
    public static void maybeAsk(Activity activity) {
        AppConfig config = new AppConfig(activity);
        if (!shouldAsk(Analytics.configured(), config.isUsageStatsRemembered(), askedThisRun,
                config.isUsageStatsEnabled(), config.getConsentAsked())) {
            return;
        }
        askedThisRun = true;
        // Shown counts as asked: the once-more question is not repeated if closed unanswered
        config.setConsentAsked(QUESTION);
        com.kooo.evcam.ui.CamDialogs.show(new MaterialAlertDialogBuilder(activity, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.usage_consent_title)
                .setMessage(R.string.usage_consent_msg)
                .setPositiveButton(R.string.usage_consent_both, (d, w) -> answer(activity, true, true))
                .setNeutralButton(R.string.usage_consent_crash_only, (d, w) -> answer(activity, false, true))
                .setNegativeButton(R.string.usage_consent_off, (d, w) -> answer(activity, false, false)));
    }

    private static void answer(Activity activity, boolean usage, boolean crash) {
        new AppConfig(activity).setConsent(usage, crash);
        Analytics.setCrashReports(crash);
        Analytics.setEnabled(usage);
    }
}
