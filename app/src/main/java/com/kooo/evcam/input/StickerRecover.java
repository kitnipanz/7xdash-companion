package com.kooo.evcam.input;

import java.util.List;
import java.util.Map;

/**
 * Pure rules for the sticker setup / battery-check links. {@link StickerHub} owns the radio;
 * this decides who dials next and how long to wait, so the rules can be tested off the car.
 *
 * <p>What this head unit's radio does (learned the hard way):</p>
 * <ul>
 *   <li>{@code autoConnect=true} never calls back. Every dial is direct.</li>
 *   <li>A second connectGatt while one is in flight kills the live link. One dial at a time.</li>
 *   <li>Discovery and scans go quiet while a link is up. The scan page pauses all links.</li>
 * </ul>
 */
public final class StickerRecover {

    public static final long CONNECT_TIMEOUT_MS = 10_000L;
    public static final long DISCOVER_TIMEOUT_MS = 10_000L;
    public static final long SUBSCRIBE_TIMEOUT_MS = 5_000L;
    /** Wrong device or a sticker with no button service: try again rarely. */
    public static final long NO_SERVICE_MS = 60_000L;
    /** Safety net: anything wanted and idle gets a dial at least this often. */
    public static final long AUDIT_MS = 20_000L;

    /**
     * Gap between dials. The sticker sleeps its radio and only advertises for a moment when
     * pressed, and autoConnect does not work here, so a direct dial has to be pending nearly
     * all the time to catch that advert. A long backoff made a dropped sticker look dead.
     */
    public static final long REDIAL_GAP_MS = 300L;

    private StickerRecover() {
    }

    /** Wait before the next dial. Same short gap every time; it never gives up. */
    public static long backoffMs(int attempt) {
        return REDIAL_GAP_MS;
    }

    /** Tell the user once after three misses in a row, then about every few minutes. */
    public static boolean reportFailure(int attempt) {
        return attempt == 3 || (attempt > 0 && attempt % 30 == 0);
    }


    /** Status 133 / 62: stale GATT cache. Clear it before redialling. */
    public static boolean refreshCache(int status) {
        return status == 133 || status == 62;
    }


    /**
     * The one sticker to dial now, or "" when the radio is busy or nobody is due.
     * A sticker the user just tapped goes first; otherwise the one that waited longest,
     * so one sticker that keeps failing cannot starve the other.
     *
     * @param idle     wanted stickers with no link, in saved order
     * @param dueAt    earliest dial time per address (missing = now)
     * @param lastDial when each address was last dialled (missing = never)
     */
    public static String next(List<String> idle, Map<String, Long> dueAt, Map<String, Long> lastDial,
                              String priority, boolean radioBusy, long now) {
        if (radioBusy || idle == null || idle.isEmpty()) {
            return "";
        }
        if (priority != null && !priority.isEmpty() && idle.contains(priority)
                && due(dueAt, priority, now)) {
            return priority;
        }
        String best = "";
        long bestLast = Long.MAX_VALUE;
        for (String address : idle) {
            if (!due(dueAt, address, now)) {
                continue;
            }
            Long last = lastDial == null ? null : lastDial.get(address);
            long at = last == null ? Long.MIN_VALUE : last;
            if (best.isEmpty() || at < bestLast) {
                best = address;
                bestLast = at;
            }
        }
        return best;
    }

    /** How long until someone in {@code idle} is due; -1 when none is waiting. */
    public static long untilNextDue(List<String> idle, Map<String, Long> dueAt, long now) {
        long wait = -1;
        if (idle == null) {
            return wait;
        }
        for (String address : idle) {
            Long at = dueAt == null ? null : dueAt.get(address);
            long left = at == null ? 0 : Math.max(0, at - now);
            if (wait < 0 || left < wait) {
                wait = left;
            }
        }
        return wait;
    }

    private static boolean due(Map<String, Long> dueAt, String address, long now) {
        Long at = dueAt == null ? null : dueAt.get(address);
        return at == null || at <= now;
    }
}
