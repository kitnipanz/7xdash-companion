package com.kooo.evcam.obd;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Spots the frames worth looking at while the bus scrolls past: which byte of which id just moved
 * after sitting still. Counters and checksums change in every frame and are ignored; a warning
 * lamp's bit sits steady, then flips, which is what an alert should look like here.
 *
 * <p>A change counts as an event when that byte had held its value for {@link #STEADY_MS}. After
 * {@link #GAP_MS} without a frame, as when the adapter listened elsewhere, the id is only brought
 * up to date: a change nobody saw happen says nothing about now. Pure Java; one capture thread writes, the floating window reads.</p>
 */
final class CanSniffer {

    static final long STEADY_MS = 2_000L;
    static final long GAP_MS = 5_000L;
    private static final int MAX_IDS = 4096;
    private static final Pattern HEX = Pattern.compile("^([0-9A-F]{2})+$");

    /** One id as shown: a copy, safe to keep. */
    static final class Row {
        final String id;
        final String[] bytes;
        /** The byte moved in an event within the window asked for. */
        final boolean[] moved;
        final long eventAt;

        Row(String id, String[] bytes, boolean[] moved, long eventAt) {
            this.id = id;
            this.bytes = bytes;
            this.moved = moved;
            this.eventAt = eventAt;
        }
    }

    private static final class Track {
        String[] bytes;
        long[] changedAt;
        long[] eventAt;
        String[] before;
        long lastEvent;
        long seenAt;
    }

    private final Map<String, Track> tracks = new HashMap<>();

    /** One line from monitor mode. Anything that is not a frame is skipped. */
    synchronized void frame(String line, String setup, long now) {
        String id;
        String data;
        int gap = line.indexOf(' ');
        if (gap > 0) {
            id = line.substring(0, gap);
            data = line.substring(gap + 1).replace(" ", "");
        } else {
            int size = "6".equals(setup) || "8".equals(setup) ? 3 : 8;
            if (line.length() <= size) {
                return;
            }
            id = line.substring(0, size);
            data = line.substring(size);
        }
        if (!HEX.matcher(data).matches()) {
            return;
        }
        String[] bytes = new String[data.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = data.substring(i * 2, i * 2 + 2);
        }
        Track track = tracks.get(id);
        if (track == null || track.bytes.length != bytes.length) {
            if (track == null && tracks.size() >= MAX_IDS) {
                return;
            }
            Track fresh = new Track();
            fresh.bytes = bytes;
            fresh.changedAt = new long[bytes.length];
            fresh.eventAt = new long[bytes.length];
            fresh.before = new String[bytes.length];
            for (int i = 0; i < bytes.length; i++) {
                fresh.changedAt[i] = now;
                fresh.eventAt[i] = -1L;
            }
            fresh.lastEvent = -1L;
            fresh.seenAt = now;
            tracks.put(id, fresh);
            return;
        }
        boolean resumed = now - track.seenAt >= GAP_MS;
        track.seenAt = now;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i].equals(track.bytes[i])) {
                continue;
            }
            if (!resumed && now - track.changedAt[i] >= STEADY_MS) {
                track.eventAt[i] = now;
                track.before[i] = track.bytes[i];
                track.lastEvent = now;
            }
            track.changedAt[i] = now;
            track.bytes[i] = bytes[i];
        }
    }

    synchronized int ids() {
        return tracks.size();
    }

    /** Ids with an event in the last {@code windowMs}, newest first. */
    synchronized List<Row> recent(long now, long windowMs, int limit) {
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, Track> entry : tracks.entrySet()) {
            Track track = entry.getValue();
            if (track.lastEvent < 0 || now - track.lastEvent > windowMs) {
                continue;
            }
            boolean[] moved = new boolean[track.bytes.length];
            for (int i = 0; i < moved.length; i++) {
                moved[i] = track.eventAt[i] >= 0 && now - track.eventAt[i] <= windowMs;
            }
            rows.add(new Row(entry.getKey(), track.bytes.clone(), moved, track.lastEvent));
        }
        Collections.sort(rows, (a, b) -> Long.compare(b.eventAt, a.eventAt));
        return rows.size() > limit ? new ArrayList<>(rows.subList(0, limit)) : rows;
    }

    /** "2B1[3] 00>40, 3C2[0] 01>00" for the log: what moved in the last {@code windowMs}. */
    synchronized String movedText(long now, long windowMs) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Track> entry : tracks.entrySet()) {
            Track track = entry.getValue();
            for (int i = 0; i < track.bytes.length; i++) {
                if (track.eventAt[i] >= 0 && now - track.eventAt[i] <= windowMs) {
                    parts.add(String.format(Locale.ROOT, "%s[%d] %s>%s",
                            entry.getKey(), i, track.before[i], track.bytes[i]));
                }
            }
        }
        Collections.sort(parts);
        return parts.isEmpty() ? "nothing" : String.join(", ", parts);
    }
}
