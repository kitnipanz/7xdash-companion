package com.kooo.evcam.telemetry;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * What the live car signal screen ({@link CarSignalsActivity}) shows: every signal with its value,
 * when it last changed and how often, plus a log of every change, mark and send for saving. No
 * Android code, so it can be tested. One thread only.
 */
public final class SignalBoard {

    /** The log keeps this many lines; older ones go. */
    static final int LOG_KEEP = 2_000;

    /** One signal. */
    public static final class Entry {
        public final int id;
        public final String name;
        public final String owner;
        /** How it is read ({@link CarAmbientLab.Reader#FUNCTION} and so on). Only function ones can be sent. */
        public final int way;
        public String value;
        public String before;
        /** 0 = not changed since the screen opened. */
        public long changedAt;
        public int changes;

        public Entry(int id, String name, String owner, int way, String value) {
            this.id = id;
            this.name = name;
            this.owner = owner;
            this.way = way;
            this.value = value;
        }

        public String hex() {
            return String.format(Locale.US, "0x%08X", id);
        }

        public boolean sendable() {
            return way == CarAmbientLab.Reader.FUNCTION;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Deque<String> log = new ArrayDeque<>();
    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private int totalChanges;

    public void add(Entry e) {
        entries.add(e);
    }

    public int size() {
        return entries.size();
    }

    public Entry get(int i) {
        return entries.get(i);
    }

    public int totalChanges() {
        return totalChanges;
    }

    /** A new reading of entry {@code i}. True when it differs from the last one. */
    public boolean update(int i, String value, long nowMs, long wallMs, String situation) {
        Entry e = entries.get(i);
        if (Objects.equals(e.value, value)) {
            return false;
        }
        e.before = e.value;
        e.value = value;
        e.changedAt = nowMs;
        e.changes++;
        totalChanges++;
        line(wallMs, e.name + "\t" + e.hex() + "\t" + e.before + "\t" + value + "\t| " + situation);
        return true;
    }

    /** The car pushed a value for {@code id}. True when an entry has that id and it changed. */
    public boolean pushed(int id, int zone, String value, long nowMs, long wallMs, String situation) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id == id) {
                Entry e = entries.get(i);
                if (Objects.equals(e.value, value)) {
                    return false;
                }
                e.before = e.value;
                e.value = value;
                e.changedAt = nowMs;
                e.changes++;
                totalChanges++;
                line(wallMs, e.name + "\t" + e.hex() + "\tpushed zone " + zone + "\t" + e.before + "\t" + value
                        + "\t| " + situation);
                return true;
            }
        }
        return false;
    }

    /** A line the driver asked for, such as "pressed the button now". */
    public void mark(long wallMs, String note) {
        line(wallMs, "---- " + note + " ----");
    }

    /** What sending gave, for the log. */
    public void sent(long wallMs, Entry e, int value, String result) {
        line(wallMs, "SEND " + e.name + "\t" + e.hex() + "\t= " + value + "\t→ " + result);
    }

    /**
     * The entries to show: those matching {@code query} (any word of it, in the name or the id,
     * case ignored), only changed ones when {@code changedOnly}. Last changed first, then by name.
     */
    public List<Entry> rows(String query, boolean changedOnly) {
        String[] words = query == null ? new String[0] : query.trim().toUpperCase(Locale.US).split("\\s+");
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (changedOnly && e.changedAt == 0L) {
                continue;
            }
            if (matches(e, words)) {
                out.add(e);
            }
        }
        Collections.sort(out, (a, b) -> {
            if (a.changedAt != b.changedAt) {
                return Long.compare(b.changedAt, a.changedAt);
            }
            return a.name.compareTo(b.name);
        });
        return out;
    }

    static boolean matches(Entry e, String[] words) {
        String hex = e.hex();
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            if (!e.name.toUpperCase(Locale.US).contains(w) && !hex.contains(w)
                    && !hex.substring(2).contains(w.startsWith("0X") ? w.substring(2) : w)) {
                return false;
            }
        }
        return true;
    }

    /** The log, oldest first, with a header and every value now. */
    public String export(String header) {
        StringBuilder b = new StringBuilder(header).append("\n\n== Changes (").append(totalChanges).append(") ==\n");
        b.append("time\tname\tid\tbefore\tnow\t| gear, km/h, turn, hazard\n");
        for (String l : log) {
            b.append(l).append('\n');
        }
        b.append("\n== All signals now (").append(entries.size()).append(") ==\n");
        b.append("name\tid\tclass\treading\tvalue\tchanges\n");
        for (Entry e : entries) {
            b.append(e.name).append('\t').append(e.hex()).append('\t').append(e.owner).append('\t')
                    .append(CarAmbientLab.Reader.name(e.way)).append('\t').append(e.value).append('\t')
                    .append(e.changes).append('\n');
        }
        return b.toString();
    }

    private void line(long wallMs, String text) {
        log.addLast(time.format(new Date(wallMs)) + "\t" + text);
        while (log.size() > LOG_KEEP) {
            log.removeFirst();
        }
    }

    /**
     * Sending to the car from this screen is for a parked car only: a wrong number could change
     * how the car drives. Null when allowed, otherwise why not.
     */
    public static String sendBlock(String gear, Float speedKmh) {
        if (!"P".equals(gear)) {
            return "gear " + (gear == null ? "unknown" : gear);
        }
        if (speedKmh == null || speedKmh > 0.5f) {
            return speedKmh == null ? "speed unknown" : "moving";
        }
        return null;
    }

    /** A number typed by the driver: decimal, negative, or 0x hex. Null when not a number. */
    public static Integer parseValue(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        try {
            if (t.startsWith("0x") || t.startsWith("0X")) {
                return (int) Long.parseLong(t.substring(2), 16);
            }
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
