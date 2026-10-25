package com.kooo.evcam.update;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.StorageHelper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * When was 7xDash installed and updated, and on which system build. The car sometimes removes the
 * app after a week or two; matching the next fresh install to a system build change tells whether
 * an OTA did it.
 *
 * <p>One line per change: a new install time (removed and installed again), a new update time, or a
 * new system build. Kept in app storage and on the USB stick ({@code <USB>/7xDash/history/}); the
 * stick's copy outlives an uninstall, so the line from before the removal is still there. The two
 * copies are merged at every start and every Diagnostics report.</p>
 */
public final class InstallHistory {

    private static final String TAG = "InstallHistory";
    private static final String USB_DIR = "7xDash/history";
    private static final String FILE = "install.txt";
    /** Lines kept. One a week for a year and then some. */
    static final int MAX_LINES = 100;

    private InstallHistory() {
    }

    /** What the app saw at one start. Times are epoch millis; 0 when unknown. */
    static final class Entry {
        long seen;
        long installed;
        long updated;
        String version = "";
        String build = "";
        String incremental = "";
        long buildTime;
        String fingerprint = "";

        /** Same install and same system: nothing new to write. */
        boolean sameAs(Entry other) {
            return other != null && installed == other.installed && updated == other.updated
                    && fingerprint.equals(other.fingerprint);
        }
    }

    /** Application start: merge both copies and add a line if anything changed. Own thread. */
    public static void recordAsync(Context context) {
        Context app = context.getApplicationContext();
        Thread t = new Thread(() -> sync(app), "install-history");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Merge app storage and USB, add the current line if new, write both back. Returns the lines. */
    public static synchronized List<String> sync(Context context) {
        File local = new File(context.getFilesDir(), FILE);
        File usb = usbFile(context);
        List<String> lines = merge(read(local), read(usb), MAX_LINES);
        Entry now = current(context);
        if (now != null) {
            Entry last = lines.isEmpty() ? null : parse(lines.get(lines.size() - 1));
            if (!now.sameAs(last)) {
                lines.add(format(now));
                lines = merge(lines, Collections.emptyList(), MAX_LINES);
                AppLog.i(TAG, "new line: " + format(now));
            }
        }
        write(local, lines);
        if (usb != null) {
            File dir = usb.getParentFile();
            if (dir != null && (dir.isDirectory() || dir.mkdirs())) {
                write(usb, lines);
            }
        }
        return lines;
    }

    /** Diagnostics section: the history, newest last, with what changed on each line. */
    public static void appendTo(StringBuilder sb, Context context) {
        sb.append("## Install history (install / update / system build)").append('\n');
        List<String> lines;
        try {
            lines = sync(context);
        } catch (RuntimeException e) {
            sb.append("!! failed: ").append(e).append('\n').append('\n');
            return;
        }
        sb.append("Copy on USB: ").append(usbFile(context) != null ? "yes" : "no stick").append('\n');
        sb.append(describe(lines));
        sb.append('\n');
    }

    // ---- pure logic (InstallHistoryTest)

    static String format(Entry e) {
        return "seen=" + e.seen
                + "\tinstalled=" + e.installed
                + "\tupdated=" + e.updated
                + "\tversion=" + clean(e.version)
                + "\tbuild=" + clean(e.build)
                + "\tincremental=" + clean(e.incremental)
                + "\tbuildTime=" + e.buildTime
                + "\tfingerprint=" + clean(e.fingerprint);
    }

    /** Null when the line is not one of ours. */
    static Entry parse(String line) {
        if (line == null || !line.startsWith("seen=")) {
            return null;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String part : line.split("\t")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                fields.put(part.substring(0, eq), part.substring(eq + 1));
            }
        }
        Entry e = new Entry();
        e.seen = number(fields.get("seen"));
        e.installed = number(fields.get("installed"));
        e.updated = number(fields.get("updated"));
        e.version = text(fields.get("version"));
        e.build = text(fields.get("build"));
        e.incremental = text(fields.get("incremental"));
        e.buildTime = number(fields.get("buildTime"));
        e.fingerprint = text(fields.get("fingerprint"));
        return e;
    }

    /** Both copies, each line once, oldest first, at most {@code max} (the newest kept). */
    static List<String> merge(List<String> a, List<String> b, int max) {
        Map<String, Entry> byLine = new LinkedHashMap<>();
        for (List<String> source : java.util.Arrays.asList(a, b)) {
            for (String line : source) {
                Entry e = parse(line);
                if (e != null) {
                    byLine.put(line, e);
                }
            }
        }
        List<Map.Entry<String, Entry>> sorted = new ArrayList<>(byLine.entrySet());
        sorted.sort((x, y) -> Long.compare(x.getValue().seen, y.getValue().seen));
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Entry> entry : sorted) {
            out.add(entry.getKey());
        }
        return out.size() > max ? new ArrayList<>(out.subList(out.size() - max, out.size())) : out;
    }

    /** Human lines: each start with what changed against the line before. */
    static String describe(List<String> lines) {
        if (lines.isEmpty()) {
            return "(none yet)\n";
        }
        SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        StringBuilder sb = new StringBuilder();
        Entry before = null;
        for (String line : lines) {
            Entry e = parse(line);
            if (e == null) {
                continue;
            }
            sb.append(when(time, e.seen)).append("  ").append(change(before, e)).append('\n');
            sb.append("    app ").append(e.version)
                    .append(", installed ").append(when(time, e.installed))
                    .append(", updated ").append(when(time, e.updated)).append('\n');
            sb.append("    system ").append(e.build).append(" (").append(e.incremental)
                    .append(", built ").append(when(time, e.buildTime)).append(")").append('\n');
            before = e;
        }
        return sb.toString();
    }

    /** What is new on this line. A new install time means the app was removed and put back. */
    static String change(Entry before, Entry now) {
        if (before == null) {
            return "first line";
        }
        List<String> what = new ArrayList<>();
        if (now.installed != before.installed) {
            what.add("REINSTALLED (removed since the line before)");
        } else if (now.updated != before.updated) {
            what.add("app updated");
        }
        if (!now.fingerprint.equals(before.fingerprint)) {
            what.add("SYSTEM CHANGED (" + before.build + " -> " + now.build + ")");
        }
        return what.isEmpty() ? "no change" : String.join(", ", what);
    }

    // ---- Android and files

    private static Entry current(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            Entry e = new Entry();
            e.seen = System.currentTimeMillis();
            e.installed = info.firstInstallTime;
            e.updated = info.lastUpdateTime;
            e.version = info.versionName + " (" + info.getLongVersionCode() + ")";
            e.build = Build.DISPLAY;
            e.incremental = Build.VERSION.INCREMENTAL;
            e.buildTime = Build.TIME;
            e.fingerprint = Build.FINGERPRINT;
            return e;
        } catch (Exception ex) {
            AppLog.w(TAG, "package info: " + ex);
            return null;
        }
    }

    private static File usbFile(Context context) {
        File root = StorageHelper.getExternalSdCardRoot(context);
        return root == null ? null : new File(new File(root, USB_DIR), FILE);
    }

    private static List<String> read(File file) {
        if (file == null || !file.isFile()) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            AppLog.w(TAG, "read " + file + ": " + e);
            return new ArrayList<>();
        }
    }

    private static void write(File file, List<String> lines) {
        File tmp = new File(file.getPath() + ".tmp");
        try {
            Files.write(tmp.toPath(), lines, StandardCharsets.UTF_8);
            if (!tmp.renameTo(file)) {
                Files.copy(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception e) {
            AppLog.w(TAG, "write " + file + ": " + e);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static String when(SimpleDateFormat format, long millis) {
        return millis <= 0 ? "?" : format.format(new Date(millis));
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }

    private static long number(String s) {
        if (s == null) {
            return 0L;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
