package com.kooo.evcam.blackbox;

import android.content.Context;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.StorageHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Memory over days, to see a leak. Every {@link #SAMPLE_MS} one line: Java heap, native heap,
 * PSS and graphics, RSS, threads, open files. Plus a line whenever the system asks for memory
 * back ({@code onTrimMemory}).
 *
 * <p>Kept in app storage (about a week) and appended to {@code <USB>/7xDash/history/memory.txt}
 * for pulling off the stick. Diagnostics shows each process's growth per hour: a number that
 * only goes up over many hours, never down, is a leak. The first {@link #WARMUP_MS} of a process
 * is left out of the rate (cameras, caches and the encoder settle then).</p>
 */
public final class MemoryLog {

    private static final String TAG = "MemoryLog";
    static final long SAMPLE_MS = 5 * 60 * 1000L;
    private static final long FIRST_MS = 60 * 1000L;
    static final long WARMUP_MS = 30 * 60 * 1000L;
    /** A rate is only worth reading after this long. */
    static final long MIN_SPAN_MS = 2 * 60 * 60 * 1000L;
    /** About a week at one line every five minutes. */
    static final int MAX_LINES = 2100;
    private static final String FILE = "memory.txt";
    private static final String USB_DIR = "7xDash/history";
    private static final long KB = 1024L;

    /** Per hour, past warm-up: above these the section says GROWING. */
    static final Map<String, Double> GROWING_PER_HOUR = new LinkedHashMap<>();

    static {
        GROWING_PER_HOUR.put("java", 4.0 * KB);     // KB
        GROWING_PER_HOUR.put("native", 8.0 * KB);   // KB
        GROWING_PER_HOUR.put("pss", 16.0 * KB);     // KB
        GROWING_PER_HOUR.put("gfx", 8.0 * KB);      // KB
        GROWING_PER_HOUR.put("threads", 2.0);
        GROWING_PER_HOUR.put("fds", 5.0);
    }

    private static Context app;
    private static Handler worker;
    private static final long processStart = SystemClock.elapsedRealtime();

    private static final Runnable tick = new Runnable() {
        @Override
        public void run() {
            record("sample");
            worker.postDelayed(this, SAMPLE_MS);
        }
    };

    private MemoryLog() {
    }

    /** Application start. */
    public static synchronized void start(Context context) {
        if (app != null) {
            return;
        }
        app = context.getApplicationContext();
        HandlerThread thread = new HandlerThread("memory-log", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.postDelayed(tick, FIRST_MS);
    }

    /** {@code onTrimMemory}: the system wants memory back. One line with the level. */
    public static void trim(int level) {
        Handler h = worker;
        if (h != null) {
            h.post(() -> record("trim:" + level));
        }
    }

    /** Diagnostics section. Takes a fresh sample first. */
    public static void appendTo(StringBuilder sb, Context context) {
        sb.append("## Memory over time (leak check)").append('\n');
        List<String> lines;
        try {
            record("report");
            lines = read(new File(context.getFilesDir(), FILE));
        } catch (RuntimeException e) {
            sb.append("!! failed: ").append(e).append('\n').append('\n');
            return;
        }
        sb.append("One line every ").append(SAMPLE_MS / 60000).append(" min, ").append(lines.size())
                .append(" kept. Also on USB: 7xDash/history/").append(FILE).append('\n');
        sb.append(summarize(lines, Process.myPid()));
        sb.append("Last 12 lines:").append('\n');
        for (int i = Math.max(0, lines.size() - 12); i < lines.size(); i++) {
            sb.append("  ").append(lines.get(i)).append('\n');
        }
        sb.append('\n');
    }

    // ---- sampling (worker thread)

    private static synchronized void record(String event) {
        Context ctx = app;
        if (ctx == null) {
            return;
        }
        String line;
        try {
            line = format(sample(event));
        } catch (RuntimeException e) {
            AppLog.w(TAG, "sample: " + e);
            return;
        }
        File local = new File(ctx.getFilesDir(), FILE);
        append(local, line);
        trimFile(local);
        File root = StorageHelper.getExternalSdCardRoot(ctx);
        if (root != null) {
            File dir = new File(root, USB_DIR);
            if (dir.isDirectory() || dir.mkdirs()) {
                File usb = new File(dir, FILE);
                append(usb, line);
                trimFile(usb);
            }
        }
    }

    private static Sample sample(String event) {
        Sample s = new Sample();
        s.time = System.currentTimeMillis();
        s.pid = Process.myPid();
        s.up = SystemClock.elapsedRealtime() - processStart;
        Runtime rt = Runtime.getRuntime();
        s.java = (rt.totalMemory() - rt.freeMemory()) / KB;
        s.javaMax = rt.maxMemory() / KB;
        s.nativeKb = Debug.getNativeHeapAllocatedSize() / KB;
        Debug.MemoryInfo info = new Debug.MemoryInfo();
        Debug.getMemoryInfo(info);
        s.pss = info.getTotalPss();
        s.gfx = number(info.getMemoryStat("summary.graphics"));
        Map<String, String> status = procStatus();
        s.rss = number(firstWord(status.get("VmRSS")));
        s.threads = number(status.get("Threads"));
        String[] fds = new File("/proc/self/fd").list();
        s.fds = fds == null ? -1 : fds.length;
        s.event = event;
        return s;
    }

    private static Map<String, String> procStatus() {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(new File("/proc/self/status").toPath(), StandardCharsets.UTF_8)) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    out.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void append(File file, String line) {
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            AppLog.w(TAG, "append " + file + ": " + e);
        }
    }

    /** Cut back to {@link #MAX_LINES} once it is 10% over, so the file is not rewritten every time. */
    private static void trimFile(File file) {
        List<String> lines = read(file);
        if (lines.size() <= MAX_LINES + MAX_LINES / 10) {
            return;
        }
        File tmp = new File(file.getPath() + ".tmp");
        try {
            Files.write(tmp.toPath(), lines.subList(lines.size() - MAX_LINES, lines.size()), StandardCharsets.UTF_8);
            if (!tmp.renameTo(file)) {
                Files.copy(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception e) {
            AppLog.w(TAG, "trim " + file + ": " + e);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static List<String> read(File file) {
        if (!file.isFile()) {
            return new ArrayList<>();
        }
        try {
            return new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    // ---- pure logic (MemoryLogTest)

    /** One line. Sizes in KB. */
    static final class Sample {
        long time;
        long pid;
        long up;
        long java;
        long javaMax;
        long nativeKb;
        long pss;
        long gfx;
        long rss;
        long threads;
        long fds;
        String event = "sample";

        long value(String key) {
            switch (key) {
                case "java":
                    return java;
                case "native":
                    return nativeKb;
                case "pss":
                    return pss;
                case "gfx":
                    return gfx;
                case "threads":
                    return threads;
                case "fds":
                    return fds;
                default:
                    return 0;
            }
        }
    }

    static String format(Sample s) {
        return "t=" + s.time + " pid=" + s.pid + " up=" + s.up
                + " java=" + s.java + " javaMax=" + s.javaMax + " native=" + s.nativeKb
                + " pss=" + s.pss + " gfx=" + s.gfx + " rss=" + s.rss
                + " threads=" + s.threads + " fds=" + s.fds + " ev=" + s.event;
    }

    /** Null when the line is not one of ours. */
    static Sample parse(String line) {
        if (line == null || !line.startsWith("t=")) {
            return null;
        }
        Map<String, String> f = new LinkedHashMap<>();
        for (String part : line.trim().split(" ")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                f.put(part.substring(0, eq), part.substring(eq + 1));
            }
        }
        Sample s = new Sample();
        s.time = number(f.get("t"));
        s.pid = number(f.get("pid"));
        s.up = number(f.get("up"));
        s.java = number(f.get("java"));
        s.javaMax = number(f.get("javaMax"));
        s.nativeKb = number(f.get("native"));
        s.pss = number(f.get("pss"));
        s.gfx = number(f.get("gfx"));
        s.rss = number(f.get("rss"));
        s.threads = number(f.get("threads"));
        s.fds = number(f.get("fds"));
        s.event = f.containsKey("ev") ? f.get("ev") : "sample";
        return s;
    }

    /**
     * Least-squares growth per hour of {@code key}, over samples past warm-up. NaN when there is
     * not {@link #MIN_SPAN_MS} of them yet.
     */
    static double perHour(List<Sample> process, String key) {
        List<Sample> use = new ArrayList<>();
        for (Sample s : process) {
            if (s.up >= WARMUP_MS && s.value(key) >= 0) {
                use.add(s);
            }
        }
        if (use.size() < 3 || use.get(use.size() - 1).up - use.get(0).up < MIN_SPAN_MS) {
            return Double.NaN;
        }
        double n = use.size();
        double sx = 0;
        double sy = 0;
        for (Sample s : use) {
            sx += s.up;
            sy += s.value(key);
        }
        double mx = sx / n;
        double my = sy / n;
        double num = 0;
        double den = 0;
        for (Sample s : use) {
            double dx = s.up - mx;
            num += dx * (s.value(key) - my);
            den += dx * dx;
        }
        return den == 0 ? Double.NaN : num / den * 3_600_000d;
    }

    /** Lines grouped by process, oldest first. A new pid is a restart (or the system killed us). */
    static Map<Long, List<Sample>> byProcess(List<String> lines) {
        Map<Long, List<Sample>> out = new LinkedHashMap<>();
        for (String line : lines) {
            Sample s = parse(line);
            if (s != null) {
                List<Sample> list = out.get(s.pid);
                if (list == null) {
                    list = new ArrayList<>();
                    out.put(s.pid, list);
                }
                list.add(s);
            }
        }
        return out;
    }

    /** One block per process: how long it ran, start → peak → last, growth per hour, verdict. */
    static String summarize(List<String> lines, long currentPid) {
        Map<Long, List<Sample>> processes = byProcess(lines);
        if (processes.isEmpty()) {
            return "(no samples yet: the first one is a minute after start)\n";
        }
        SimpleDateFormat time = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Long, List<Sample>> entry : processes.entrySet()) {
            List<Sample> list = entry.getValue();
            Sample first = list.get(0);
            Sample last = list.get(list.size() - 1);
            int trims = 0;
            for (Sample s : list) {
                if (s.event.startsWith("trim")) {
                    trims++;
                }
            }
            sb.append("pid ").append(entry.getKey())
                    .append(entry.getKey() == currentPid ? " (this run)" : "")
                    .append("  ").append(time.format(new Date(first.time))).append(" → ")
                    .append(time.format(new Date(last.time)))
                    .append("  ran ").append(hours(last.up)).append(" h, ").append(list.size()).append(" lines")
                    .append(trims > 0 ? ", " + trims + " trim requests" : "").append('\n');
            List<String> growing = new ArrayList<>();
            for (Map.Entry<String, Double> limit : GROWING_PER_HOUR.entrySet()) {
                String key = limit.getKey();
                long peak = Long.MIN_VALUE;
                for (Sample s : list) {
                    peak = Math.max(peak, s.value(key));
                }
                double rate = perHour(list, key);
                boolean size = !"threads".equals(key) && !"fds".equals(key);
                sb.append("    ").append(key).append(": ")
                        .append(show(first.value(key), size)).append(" → peak ").append(show(peak, size))
                        .append(" → ").append(show(last.value(key), size))
                        .append(", ").append(Double.isNaN(rate) ? "rate needs 2.5 h"
                                : (size ? String.format(Locale.US, "%+.1f MB/h", rate / KB)
                                : String.format(Locale.US, "%+.1f /h", rate)))
                        .append('\n');
                if (!Double.isNaN(rate) && rate > limit.getValue()) {
                    growing.add(key);
                }
            }
            sb.append("    verdict: ").append(growing.isEmpty()
                    ? (Double.isNaN(perHour(list, "pss")) ? "too short to tell" : "steady")
                    : "GROWING " + String.join(", ", growing)).append('\n');
        }
        return sb.toString();
    }

    private static String show(long value, boolean size) {
        if (value < 0) {
            return "?";
        }
        return size ? String.format(Locale.US, "%.1fMB", value / (double) KB) : Long.toString(value);
    }

    private static String hours(long ms) {
        return String.format(Locale.US, "%.1f", ms / 3_600_000d);
    }

    private static String firstWord(String s) {
        if (s == null) {
            return null;
        }
        int space = s.indexOf(' ');
        return space < 0 ? s : s.substring(0, space);
    }

    static long number(String s) {
        if (s == null) {
            return -1L;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}
