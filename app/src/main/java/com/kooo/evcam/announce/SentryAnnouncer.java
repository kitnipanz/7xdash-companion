package com.kooo.evcam.announce;

import android.content.Context;
import android.os.SystemClock;

import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Telemetry;

/**
 * Plays the picked announcement when Sentry Mode changes to armed (2). The car does not say
 * "someone was detected"; it only reports off (0), on (1) and armed (2), so every change goes to the
 * black box too, to learn what armed means on this car. Core telemetry reads Sentry Mode for as
 * long as the process lives, so listening is enough.
 */
public final class SentryAnnouncer implements Telemetry.Listener {

    static final int ARMED = 2;
    static final long COOLDOWN_MS = 60_000L;

    private static SentryAnnouncer installed;

    private final Context app;
    private Integer last;
    private boolean seen;
    private long playedAt = -COOLDOWN_MS;

    private SentryAnnouncer(Context app) {
        this.app = app;
    }

    /** Application start. Repeat calls do nothing. */
    public static void install(Context context) {
        if (installed != null) {
            return;
        }
        installed = new SentryAnnouncer(context.getApplicationContext());
        Telemetry.get().addListener(installed);
    }

    @Override
    public void onReadingsChanged(Readings readings) {
        Integer now = Telemetry.get().latest().sentry;
        if (seen && java.util.Objects.equals(now, last)) {
            return;
        }
        Integer before = last;
        boolean first = !seen;
        seen = true;
        last = now;
        if (first) {
            return;
        }
        BlackBox.note("Sentry Mode: " + before + " -> " + now);
        long clock = SystemClock.elapsedRealtime();
        if (!shouldPlay(before, now, clock - playedAt)) {
            return;
        }
        AnnouncementStore store = new AnnouncementStore(app);
        Announcement pick = store.find(store.sentryId());
        if (pick == null) {
            return;
        }
        playedAt = clock;
        Announcer.play(app, pick, "sentry armed");
    }

    /** Armed from anything else, and not within the cooldown of the last one. */
    static boolean shouldPlay(Integer before, Integer now, long sinceLastMs) {
        return now != null && now == ARMED && (before == null || before != ARMED) && sinceLastMs >= COOLDOWN_MS;
    }
}
