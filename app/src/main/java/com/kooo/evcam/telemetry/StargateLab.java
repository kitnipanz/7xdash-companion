package com.kooo.evcam.telemetry;

import android.Manifest;
import android.content.pm.ComponentInfo;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.BuildConfig;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.blackbox.BlackBox;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Experiment: can 7xDash drive the Stargate light curtain (the front light bar of the 7X AWD
 * Performance)? Everything here only looks, except the Read or send box on the screen, which
 * goes through {@link CarAmbientLab#sendAny} (parked only, after asking).
 *
 * <ul>
 *   <li><b>Find</b>: every ECARX function whose name matches the words, with what it reads now.</li>
 *   <li><b>Car API</b>: the methods of the car interface and of each manager it hands out,
 *   flagging the ones that take bytes, text or arrays (a picture would need one of those).</li>
 *   <li><b>Light apps</b>: installed apps that look like the car's light or light show apps, with
 *   their services, providers and receivers.</li>
 *   <li><b>Listen</b>: while you change things in the car's Light Studio, three things at once:
 *   the matching functions read over and over; every push the car sends for any function (any
 *   zone, and callbacks that carry something other than a number, printed raw); and system log
 *   lines about lights.</li>
 * </ul>
 * Everything shown is also written to a capture file ({@code 7xDash/stargate_logs} on the USB
 * drive, or the app's own folder) and kept for Copy all.
 */
public final class StargateLab {

    public enum Source { NAME, API, APP, CAR, PUSH, CALLBACK, LOG, NOTE }

    /** One line on the screen. */
    public static final class Heard {
        public final Source source;
        public final long at;
        public final String title;
        public final String detail;
        /** A function id the line is about (fills the Read or send box), or null. */
        public final Integer id;
        public final Integer zone;
        /** APP: the package, so its APK can be saved; else null. */
        public final String packageName;

        Heard(Source source, String title, String detail, Integer id, Integer zone) {
            this(source, title, detail, id, zone, null);
        }

        Heard(Source source, String title, String detail, Integer id, Integer zone, String packageName) {
            this.source = source;
            this.at = System.currentTimeMillis();
            this.title = title;
            this.detail = detail;
            this.id = id;
            this.zone = zone;
            this.packageName = packageName;
        }
    }

    public interface Listener {
        /** Main thread. */
        void heard(Heard h);

        /** Main thread. */
        void status(String line);
    }

    /** Words for Find and Listen when the box is empty. */
    public static final String DEFAULT_WORDS =
            "CURTAIN|STAR|GATE|ISD|PIXEL|LIGHT_?SHOW|DLS|DLP|LOGO|GRILLE|MATRIX|LIGHT_?LANG|SIGNATURE"
                    + "|PATTERN|CUSTOM|INTERACT|EXTERIOR|FRONT_?LIGHT|HEADLAMP|DRL|ANIMATION";

    private static final String TAG = "StargateLab";
    private static final String LOG_DIR = "7xDash/stargate_logs";
    private static final long SETTLE_MS = 3000L;
    private static final long PASS_GAP_MS = 300L;
    private static final int MAX_DETAIL = 1200;
    private static final int MAX_COPY = 400 * 1024;
    /** Log lines about lights. Kept wide: the point is to see what the car's light app says. */
    private static final Pattern LOG_WORDS = Pattern.compile(
            "(?i)curtain|stargate|star_?gate|\\bisd\\b|pixel|light_?show|lightshow|\\bdls\\b|logo_?light|"
                    + "light_?lang|signature|matrix|exterior_?light|ambien|lamp|light|pattern");
    /** App package names or labels that look like the car's light apps. */
    private static final Pattern APP_WORDS = Pattern.compile(
            "(?i)light|lamp|show|curtain|isd|stargate|ambien|dls|exterior|logo|zeekr|ecarx|geely");
    /** Method names worth a star in the Car API list. */
    private static final Pattern API_WORDS = Pattern.compile(
            "(?i)custom|byte|raw|data|array|pixel|frame|image|bitmap|matrix|show|light|lamp|pattern|curtain|isd");

    private static final Object LOCK = new Object();
    private static volatile StargateLab live;
    private static final StringBuilder COPY = new StringBuilder();
    private static Writer file;
    private static String fileName = "";

    private final Context context;
    private final Listener listener;
    private final Pattern words;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private CarAmbientLab.Reader pushReader;
    private java.lang.Process logcat;

    private StargateLab(Context context, Listener listener, Pattern words) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.words = words;
    }

    /** The words box as a pattern: a regex, or plain words split on spaces and commas. */
    public static Pattern words(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            t = DEFAULT_WORDS;
        }
        try {
            return Pattern.compile(t, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            StringBuilder alt = new StringBuilder();
            for (String w : t.split("[\\s,]+")) {
                if (!w.isEmpty()) {
                    alt.append(alt.length() == 0 ? "" : "|").append(Pattern.quote(w));
                }
            }
            return Pattern.compile(alt.length() == 0 ? Pattern.quote(t) : alt.toString(), Pattern.CASE_INSENSITIVE);
        }
    }

    // ================================================================= capture

    /** Where the capture goes; opened on first use. */
    public static String captureFile() {
        synchronized (LOCK) {
            return fileName;
        }
    }

    /** Everything captured so far, for Copy all. */
    public static String copyAll() {
        synchronized (LOCK) {
            return COPY.toString();
        }
    }

    public static void clearCopy() {
        synchronized (LOCK) {
            COPY.setLength(0);
        }
    }

    /** Closes the capture file; the next line opens a new one. */
    public static void closeCapture() {
        synchronized (LOCK) {
            if (file != null) {
                try {
                    file.close();
                } catch (IOException ignored) {
                    // closing anyway
                }
            }
            file = null;
            fileName = "";
        }
    }

    private static void capture(Context context, Heard h) {
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date(h.at)) + "  " + h.source
                + "  " + h.title + (h.detail == null || h.detail.isEmpty() ? "" : "\n    " + h.detail.replace("\n", "\n    "));
        synchronized (LOCK) {
            if (COPY.length() < MAX_COPY) {
                COPY.append(line).append('\n');
            }
            if (file == null) {
                open(context);
            }
            if (file != null) {
                try {
                    file.write(line);
                    file.write('\n');
                    file.flush();
                } catch (IOException e) {
                    AppLog.w(TAG, "capture write failed: " + e.getMessage());
                    file = null;
                }
            }
        }
    }

    /** Holding {@code LOCK}. USB drive first, then the app's own folder. */
    private static void open(Context context) {
        Date now = new Date();
        String name = "stargate-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now) + ".txt";
        List<File> dirs = new ArrayList<>();
        File usb = StorageHelper.getExternalSdCardRoot(context);
        if (usb != null) {
            dirs.add(new File(usb, LOG_DIR));
        }
        File own = context.getExternalFilesDir("stargate_logs");
        if (own != null) {
            dirs.add(own);
        }
        for (File dir : dirs) {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                continue;
            }
            File f = new File(dir, name);
            try {
                file = new FileWriter(f, true);
                fileName = f.getAbsolutePath();
                file.write("# 7xDash " + BuildConfig.VERSION_NAME + " Stargate lab capture, " + now + "\n");
                return;
            } catch (IOException e) {
                AppLog.w(TAG, "cannot open " + f + ": " + e.getMessage());
                file = null;
            }
        }
    }

    // ================================================================= one-shot looks (blocking, off the main thread)

    /** Find: every ECARX function whose name matches, read now. Blocking. */
    public static List<Heard> find(Context context, String wordsText) {
        Pattern p = words(wordsText);
        List<Heard> out = new ArrayList<>();
        CarAmbientLab.Scan scan = CarAmbientLab.scan(context);
        List<CarAmbientLab.Constant> hits = new ArrayList<>();
        for (CarAmbientLab.Constant k : scan.functions) {
            if (p.matcher(k.name).find() || p.matcher(k.owner).find()) {
                hits.add(k);
            }
        }
        String[] values = hits.isEmpty() ? new String[0] : CarAmbientLab.readAll(context, hits);
        out.add(note(context, "Find \"" + p.pattern() + "\": " + hits.size() + " of " + scan.functions.size()
                + " car functions match", scan.status));
        for (int i = 0; i < hits.size(); i++) {
            CarAmbientLab.Constant k = hits.get(i);
            String value = values == null ? "car function interface not available" : values[i];
            out.add(record(context, new Heard(Source.NAME, k.owner + "." + k.name,
                    k.hex() + " · reads " + value, k.value, null)));
        }
        return out;
    }

    /** Car API: the car interface and every manager it hands out, with their methods. Blocking. */
    public static List<Heard> inspectApi(Context context) {
        List<Heard> out = new ArrayList<>();
        Object car = EcarxSource.carForLab(context);
        if (car == null) {
            out.add(note(context, "Car API: this head unit has no ECARX car interface", ""));
            return out;
        }
        out.add(record(context, new Heard(Source.API, "Car: " + car.getClass().getName(), methods(car), null, null)));
        for (Method m : publicMethods(car)) {
            if (m.getParameterTypes().length != 0 || !m.getName().startsWith("get")
                    || m.getReturnType().isPrimitive() || m.getReturnType() == String.class) {
                continue;
            }
            Object manager;
            try {
                manager = m.invoke(car);
            } catch (Throwable t) {
                out.add(record(context, new Heard(Source.API, m.getName() + "()", "error " + t, null, null)));
                continue;
            }
            if (manager == null || manager instanceof Number || manager instanceof Boolean
                    || manager.getClass().isArray()) {
                continue;
            }
            out.add(record(context, new Heard(Source.API, m.getName() + "() → " + manager.getClass().getName(),
                    methods(manager), null, null)));
        }
        return out;
    }

    /** Light apps: installed packages that look like the car's light apps. Blocking. */
    public static List<Heard> inspectApps(Context context) {
        List<Heard> out = new ArrayList<>();
        PackageManager pm = context.getPackageManager();
        List<PackageInfo> all;
        try {
            all = pm.getInstalledPackages(0);
        } catch (Throwable t) {
            out.add(note(context, "Light apps: cannot list apps", String.valueOf(t)));
            return out;
        }
        int found = 0;
        for (PackageInfo base : all) {
            ApplicationInfo ai = base.applicationInfo;
            String label = ai == null ? "" : String.valueOf(pm.getApplicationLabel(ai));
            if (!APP_WORDS.matcher(base.packageName).find() && !APP_WORDS.matcher(label).find()) {
                continue;
            }
            found++;
            StringBuilder d = new StringBuilder();
            d.append(label).append(" · ").append(base.versionName)
                    .append((ai != null && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) ? " · system" : "");
            try {
                PackageInfo full = pm.getPackageInfo(base.packageName, PackageManager.GET_SERVICES
                        | PackageManager.GET_PROVIDERS | PackageManager.GET_RECEIVERS | PackageManager.GET_ACTIVITIES);
                components(d, "service", full.services);
                components(d, "provider", full.providers);
                components(d, "receiver", full.receivers);
                components(d, "activity", full.activities);
            } catch (Throwable t) {
                d.append("\ncomponents: ").append(t.getClass().getSimpleName());
            }
            out.add(record(context, new Heard(Source.APP, base.packageName, d.toString(), null, null, base.packageName)));
        }
        out.add(0, note(context, "Light apps: " + found + " of " + all.size() + " installed apps look like light apps",
                all.size() < 40 ? "Only a few apps are visible to 7xDash" : ""));
        return out;
    }

    /**
     * Copies an installed app's APK (and its split APKs) next to the capture, on the USB drive
     * when there is one, for a closer look on a computer. Any app can read these files. Blocking.
     */
    public static String saveApk(Context context, String packageName) {
        ApplicationInfo ai;
        try {
            ai = context.getPackageManager().getApplicationInfo(packageName, 0);
        } catch (Throwable t) {
            return "Save APK: " + packageName + " not found";
        }
        List<String> sources = new ArrayList<>();
        sources.add(ai.sourceDir);
        if (ai.splitSourceDirs != null) {
            java.util.Collections.addAll(sources, ai.splitSourceDirs);
        }
        File dir = apkDir(context);
        if (dir == null) {
            return "Save APK: no USB drive or app folder to save to";
        }
        long total = 0;
        StringBuilder names = new StringBuilder();
        for (String src : sources) {
            File from = new File(src);
            String name = packageName + (from.getName().equals("base.apk") || sources.size() == 1
                    ? ".apk" : "-" + from.getName());
            File to = new File(dir, name);
            try (java.io.InputStream in = new java.io.FileInputStream(from);
                 java.io.OutputStream o = new java.io.FileOutputStream(to)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    o.write(buf, 0, n);
                    total += n;
                }
            } catch (IOException e) {
                String line = "Save APK: cannot copy " + src + " (" + e.getMessage() + ")";
                capture(context, new Heard(Source.NOTE, line, "", null, null));
                return line;
            }
            names.append(names.length() == 0 ? "" : ", ").append(name);
        }
        String line = "Saved " + names + " (" + (total / 1024) + " KB) to " + dir.getAbsolutePath();
        capture(context, new Heard(Source.NOTE, line, "from " + ai.sourceDir, null, null));
        BlackBox.note("stargate-lab: " + line);
        return line;
    }

    /** {@code 7xDash/stargate_logs/apk} on the USB drive, else the app's own folder. */
    private static File apkDir(Context context) {
        List<File> dirs = new ArrayList<>();
        File usb = StorageHelper.getExternalSdCardRoot(context);
        if (usb != null) {
            dirs.add(new File(usb, LOG_DIR + "/apk"));
        }
        File own = context.getExternalFilesDir("stargate_logs/apk");
        if (own != null) {
            dirs.add(own);
        }
        for (File d : dirs) {
            if (d.isDirectory() || d.mkdirs()) {
                return d;
            }
        }
        return null;
    }

    private static void components(StringBuilder d, String kind, ComponentInfo[] list) {
        if (list == null) {
            return;
        }
        for (ComponentInfo c : list) {
            d.append('\n').append(kind).append(' ').append(c.name.replace(c.packageName, ""))
                    .append(c.exported ? " (exported)" : "");
            if (c instanceof android.content.pm.ProviderInfo) {
                d.append(" authority ").append(((android.content.pm.ProviderInfo) c).authority);
            }
        }
    }

    private static List<Method> publicMethods(Object target) {
        Map<String, Method> out = new java.util.TreeMap<>();
        Set<Class<?>> seen = new HashSet<>();
        List<Class<?>> todo = new ArrayList<>();
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            todo.add(c);
        }
        while (!todo.isEmpty()) {
            Class<?> c = todo.remove(0);
            if (!seen.add(c)) {
                continue;
            }
            for (Class<?> i : c.getInterfaces()) {
                todo.add(i);
            }
            if (!Modifier.isPublic(c.getModifiers()) && !c.isInterface()) {
                continue;
            }
            for (Method m : c.getMethods()) {
                if (m.getDeclaringClass() != Object.class) {
                    out.putIfAbsent(signature(m), m);
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    /** One method per line; the ones that could carry a picture first, with a star. */
    private static String methods(Object target) {
        List<String> star = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (Method m : publicMethods(target)) {
            String s = signature(m);
            boolean rich = false;
            for (Class<?> p : m.getParameterTypes()) {
                rich |= p.isArray() || p == String.class || p == Object.class || p == float.class;
            }
            if (rich || API_WORDS.matcher(m.getName()).find()) {
                star.add("★ " + s);
            } else {
                rest.add(s);
            }
        }
        StringBuilder b = new StringBuilder();
        for (String s : star) {
            b.append(s).append('\n');
        }
        for (String s : rest) {
            b.append(s).append('\n');
        }
        return b.toString().trim();
    }

    private static String signature(Method m) {
        StringBuilder b = new StringBuilder(m.getReturnType().getSimpleName()).append(' ').append(m.getName()).append('(');
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            b.append(i == 0 ? "" : ", ").append(ps[i].getSimpleName());
        }
        return b.append(')').toString();
    }

    private static Heard note(Context context, String title, String detail) {
        return record(context, new Heard(Source.NOTE, title, detail, null, null));
    }

    private static Heard record(Context context, Heard h) {
        capture(context, h);
        return h;
    }

    // ================================================================= listening

    public static void start(Context context, Listener listener, String wordsText) {
        stopAll();
        StargateLab lab = new StargateLab(context, listener, words(wordsText));
        lab.running = true;
        live = lab;
        BlackBox.note("stargate-lab: listening for " + lab.words.pattern());
        lab.post(new Heard(Source.NOTE, "Listening for \"" + lab.words.pattern() + "\"", "", null, null));
        new Thread(lab::watchCar, "stargate-lab-car").start();
        new Thread(lab::watchLog, "stargate-lab-log").start();
    }

    public static void stopAll() {
        StargateLab lab = live;
        live = null;
        if (lab != null) {
            lab.stop();
        }
    }

    public static boolean isListening() {
        return live != null;
    }

    private void stop() {
        running = false;
        CarAmbientLab.Reader r = pushReader;
        if (r != null) {
            r.unwatch();
        }
        java.lang.Process p = logcat;
        if (p != null) {
            p.destroy();
        }
        capture(context, new Heard(Source.NOTE, "Stopped listening", "", null, null));
        BlackBox.note("stargate-lab: stopped, capture " + captureFile());
    }

    private void post(Heard h) {
        capture(context, h);
        main.post(() -> {
            if (live == this || h.source == Source.NOTE) {
                listener.heard(h);
            }
        });
    }

    private void status(String line) {
        main.post(() -> {
            if (live == this) {
                listener.status(line);
            }
        });
    }

    /** Matching functions read over and over, and every push for any function. */
    private void watchCar() {
        status("Finding the car's functions…");
        CarAmbientLab.Scan scan = CarAmbientLab.scan(context);
        if (!scan.carReady || scan.functions.isEmpty()) {
            status("Car functions: not available (" + scan.status + ") · system log still listening");
            return;
        }
        Map<Integer, CarAmbientLab.Constant> byId = new HashMap<>();
        List<CarAmbientLab.Constant> mine = new ArrayList<>();
        int[] all = new int[scan.functions.size()];
        for (int i = 0; i < all.length; i++) {
            CarAmbientLab.Constant k = scan.functions.get(i);
            all[i] = k.value;
            byId.put(k.value, k);
            if (words.matcher(k.name).find() || words.matcher(k.owner).find()) {
                mine.add(k);
            }
        }

        // Pushes: every function, every zone; the first values the car sends are the baseline
        Map<String, String> pushed = new HashMap<>();
        Set<String> noisy = new HashSet<>();
        Set<String> settleChanged = new HashSet<>();
        long settleUntil = SystemClock.uptimeMillis() + SETTLE_MS;
        CarAmbientLab.Reader reader = CarAmbientLab.Reader.open(context);
        String pushState = "no push interface";
        if (reader != null) {
            pushReader = reader;
            pushState = reader.watch(all, new CarAmbientLab.Reader.Pushed() {
                @Override
                public void value(int id, int zone, Object value) {
                    if (!running) {
                        return;
                    }
                    String key = id + "/" + zone;
                    String now = describe(value);
                    String before;
                    synchronized (pushed) {
                        before = pushed.put(key, now);
                        if (SystemClock.uptimeMillis() < settleUntil) {
                            if (before != null && !before.equals(now) && !settleChanged.add(key)) {
                                noisy.add(key);
                            }
                            return;
                        }
                        if (noisy.contains(key) || now.equals(before)) {
                            return;
                        }
                    }
                    CarAmbientLab.Constant k = byId.get(id);
                    post(new Heard(Source.PUSH, k == null ? String.format(Locale.US, "0x%08X", id) : k.owner + "." + k.name,
                            String.format(Locale.US, "0x%08X zone 0x%X · was %s · now %s", id, zone, before, now),
                            id, zone == 0 ? null : zone));
                }

                @Override
                public void other(String method, Object[] args) {
                    if (!running) {
                        return;
                    }
                    StringBuilder d = new StringBuilder();
                    if (args != null) {
                        for (int i = 0; i < args.length; i++) {
                            d.append(i == 0 ? "" : ", ").append(describe(args[i]));
                        }
                    }
                    Integer id = args != null && args.length > 0 && args[0] instanceof Integer ? (Integer) args[0] : null;
                    CarAmbientLab.Constant k = id == null ? null : byId.get(id);
                    post(new Heard(Source.CALLBACK, method + (k == null ? "" : " · " + k.owner + "." + k.name),
                            clip(d.toString()), id, null));
                }
            });
        }

        String[] base = mine.isEmpty() ? new String[0] : CarAmbientLab.readAll(context, mine);
        boolean told = false;
        while (running) {
            if (!told && SystemClock.uptimeMillis() >= settleUntil) {
                told = true;
                int quiet;
                synchronized (pushed) {
                    quiet = noisy.size();
                }
                status("Listening: " + mine.size() + " matching of " + all.length + " functions read every "
                        + PASS_GAP_MS + " ms · pushes " + pushState + " (" + quiet + " change by themselves, ignored) · "
                        + logState());
            }
            sleep(PASS_GAP_MS);
            if (base == null || mine.isEmpty()) {
                continue;
            }
            String[] now = CarAmbientLab.readAll(context, mine);
            if (now == null || !running) {
                continue;
            }
            for (int i = 0; i < mine.size(); i++) {
                if (java.util.Objects.equals(base[i], now[i])) {
                    continue;
                }
                CarAmbientLab.Constant k = mine.get(i);
                if (SystemClock.uptimeMillis() >= settleUntil) {
                    post(new Heard(Source.CAR, k.owner + "." + k.name,
                            k.hex() + " · was " + base[i] + " · now " + now[i], k.value, null));
                }
                base[i] = now[i];
            }
        }
    }

    private String logState() {
        return context.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED
                ? "system log" : "system log: only 7xDash's own (READ_LOGS not granted)";
    }

    /** System log lines about lights, from any app but 7xDash. */
    private void watchLog() {
        BufferedReader in = null;
        String mine = " " + Process.myPid() + " ";
        try {
            logcat = Runtime.getRuntime().exec(new String[]{"logcat", "-v", "threadtime", "-T", "1"});
            in = new BufferedReader(new InputStreamReader(logcat.getInputStream()));
            String line;
            while (running && (line = in.readLine()) != null) {
                if (line.contains(mine) || !LOG_WORDS.matcher(line).find()) {
                    continue;
                }
                String tag = logTag(line);
                post(new Heard(Source.LOG, tag, clip(line), null, null));
            }
        } catch (IOException e) {
            if (running) {
                AppLog.w(TAG, "logcat failed: " + e.getMessage());
            }
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // done
                }
            }
        }
    }

    /** {@code threadtime}: date time pid tid level tag: message. */
    private static String logTag(String line) {
        String[] parts = line.trim().split("\\s+", 7);
        if (parts.length >= 6) {
            String tag = parts[5];
            return tag.endsWith(":") ? tag.substring(0, tag.length() - 1) : tag;
        }
        return "log";
    }

    // ================================================================= text

    /** A value as text; arrays in full (bytes as hex), so a picture sent as bytes can be seen. */
    static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof byte[]) {
            byte[] b = (byte[]) value;
            StringBuilder s = new StringBuilder("bytes[" + b.length + "] ");
            for (int i = 0; i < b.length && i < 256; i++) {
                s.append(String.format(Locale.US, "%02X", b[i]));
            }
            return s.append(b.length > 256 ? "…" : "").toString();
        }
        if (value.getClass().isArray()) {
            int n = Array.getLength(value);
            StringBuilder s = new StringBuilder(value.getClass().getComponentType().getSimpleName() + "[" + n + "] {");
            for (int i = 0; i < n && i < 64; i++) {
                s.append(i == 0 ? "" : ", ").append(describe(Array.get(value, i)));
            }
            return s.append(n > 64 ? ", …}" : "}").toString();
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof String) {
            return String.valueOf(value);
        }
        return value.getClass().getSimpleName() + " " + value;
    }

    private static String clip(String s) {
        return s.length() > MAX_DETAIL ? s.substring(0, MAX_DETAIL) + "…" : s;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
