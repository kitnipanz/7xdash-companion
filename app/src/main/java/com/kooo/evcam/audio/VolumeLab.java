package com.kooo.evcam.audio;

import android.content.Context;
import android.media.AudioManager;
import android.view.KeyEvent;
import android.view.View;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.blackbox.BlackBox;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Experiment: can 7xDash change the car's main volume (the one the knob turns)?
 *
 * <p>The knob does not move any ECARX car function (the Car lights lab's Watch saw nothing), so
 * this tries the four ways an app might reach the car's volume and reports what each one did:</p>
 * <ol>
 *   <li>{@link Way#CAR_GROUP}: {@code CarAudioManager.setGroupVolume} on the media group of
 *   zone 0. Usually needs a system permission; some head units do not check it.</li>
 *   <li>{@link Way#STREAM}: {@code AudioManager.adjustStreamVolume(STREAM_MUSIC)}. Ignored when
 *   the car runs Android with fixed volume.</li>
 *   <li>{@link Way#KEY}: a volume key sent through this screen's own window, which Android
 *   hands to the system as if the steering wheel button was pressed.</li>
 *   <li>{@link Way#ECARX}: volume methods found in the car's ECARX library, getter and setter
 *   pairs called on whatever object its factories hand out.</li>
 * </ol>
 *
 * <p>Nothing runs on its own; every read and change goes to the black box with the result.
 * Blocking calls (all but {@link #key}): run them off the main thread.</p>
 */
public final class VolumeLab {

    private static final String TAG = "VolumeLab";
    private static final String CAR = "android.car.Car";
    private static final String CAR_AUDIO = "android.car.media.CarAudioManager";
    private static final int ZONE = 0;
    private static final int USAGE_MEDIA = 1;
    private static final int FLAG_SHOW_UI = 1;
    private static final Pattern AUDIO_CLASS = Pattern.compile("(?i)audio|volume|sound");
    private static final Pattern FACTORY = Pattern.compile("^(create|get|new|from|obtain).*");

    public enum Way { CAR_GROUP, STREAM, KEY, ECARX }

    private VolumeLab() {
    }

    private static Object car;
    private static Object carAudio;

    // ================================================================= read / up / down

    /** What this way reads now, as one line. Blocking. */
    public static String read(Context context, Way way) {
        String line;
        switch (way) {
            case CAR_GROUP:
                line = readGroup(context);
                break;
            case STREAM:
            case KEY:
                line = readStream(context) + " · " + readGroup(context);
                break;
            default:
                line = readEcarx(context);
                break;
        }
        log(way, "read", line);
        return line;
    }

    /** One step up ({@code +1}) or down ({@code -1}), then what it reads after. Blocking, not for KEY. */
    public static String step(Context context, Way way, int direction) {
        String result;
        try {
            switch (way) {
                case CAR_GROUP:
                    result = stepGroup(context, direction);
                    break;
                case STREAM:
                    result = stepStream(context, direction);
                    break;
                default:
                    result = stepEcarx(context, direction);
                    break;
            }
        } catch (Throwable t) {
            result = "failed: " + root(t);
        }
        log(way, direction > 0 ? "up" : "down", result);
        return result;
    }

    /**
     * Presses a volume key in {@code view}'s window. Not handled by any view, it falls through to
     * the window, which passes it to the system's volume handling. Main thread.
     */
    public static String key(View view, int direction) {
        int code = direction > 0 ? KeyEvent.KEYCODE_VOLUME_UP : KeyEvent.KEYCODE_VOLUME_DOWN;
        long now = android.os.SystemClock.uptimeMillis();
        String result;
        try {
            View root = view.getRootView();
            boolean down = root.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
            boolean up = root.dispatchKeyEvent(new KeyEvent(now, now + 40, KeyEvent.ACTION_UP, code, 0));
            result = "key " + KeyEvent.keyCodeToString(code) + " sent, handled " + down + "/" + up;
        } catch (Throwable t) {
            result = "failed: " + root(t);
        }
        log(Way.KEY, direction > 0 ? "up" : "down", result);
        return result;
    }

    // ================================================================= 1. car volume group

    private static String readGroup(Context context) {
        try {
            Object audio = carAudio(context);
            int group = mediaGroup(audio);
            return "group " + group + " = " + groupCall(audio, "getGroupVolume", group)
                    + " (" + groupCall(audio, "getGroupMinVolume", group) + ".."
                    + groupCall(audio, "getGroupMaxVolume", group) + ")";
        } catch (Throwable t) {
            return "car group: " + root(t);
        }
    }

    private static String stepGroup(Context context, int direction) throws Exception {
        Object audio = carAudio(context);
        int group = mediaGroup(audio);
        int now = groupCall(audio, "getGroupVolume", group);
        int max = groupCall(audio, "getGroupMaxVolume", group);
        int min = groupCall(audio, "getGroupMinVolume", group);
        int target = Math.max(min, Math.min(max, now + direction));
        try {
            invoke(audio, "setGroupVolume", new Class<?>[]{int.class, int.class, int.class, int.class},
                    ZONE, group, target, FLAG_SHOW_UI);
        } catch (NoSuchMethodException e) {
            invoke(audio, "setGroupVolume", new Class<?>[]{int.class, int.class, int.class},
                    group, target, FLAG_SHOW_UI);
        }
        return "set group " + group + " " + now + " -> " + target + ", reads "
                + groupCall(audio, "getGroupVolume", group);
    }

    private static synchronized Object carAudio(Context context) throws Exception {
        if (carAudio != null) {
            return carAudio;
        }
        Class<?> carClass = Class.forName(CAR);
        Object created;
        try {
            created = carClass.getMethod("createCar", Context.class).invoke(null, context.getApplicationContext());
        } catch (NoSuchMethodException e) {
            created = carClass.getMethod("createCar", Context.class, android.os.Handler.class)
                    .invoke(null, context.getApplicationContext(), null);
        }
        if (created == null) {
            throw new IllegalStateException("createCar gave nothing");
        }
        Object manager;
        try {
            String service = (String) carClass.getField("AUDIO_SERVICE").get(null);
            manager = carClass.getMethod("getCarManager", String.class).invoke(created, service);
        } catch (NoSuchFieldException | NoSuchMethodException e) {
            manager = carClass.getMethod("getCarManager", Class.class).invoke(created, Class.forName(CAR_AUDIO));
        }
        if (manager == null) {
            throw new IllegalStateException("no CarAudioManager");
        }
        car = created;
        carAudio = manager;
        return manager;
    }

    private static int mediaGroup(Object audio) throws Exception {
        try {
            return (Integer) invoke(audio, "getVolumeGroupIdForUsage",
                    new Class<?>[]{int.class, int.class}, ZONE, USAGE_MEDIA);
        } catch (NoSuchMethodException e) {
            return (Integer) invoke(audio, "getVolumeGroupIdForUsage", new Class<?>[]{int.class}, USAGE_MEDIA);
        }
    }

    /** {@code name(zone, group)}, or {@code name(group)} on older cars. */
    private static int groupCall(Object audio, String name, int group) throws Exception {
        try {
            return (Integer) invoke(audio, name, new Class<?>[]{int.class, int.class}, ZONE, group);
        } catch (NoSuchMethodException e) {
            return (Integer) invoke(audio, name, new Class<?>[]{int.class}, group);
        }
    }

    // ================================================================= 2. Android media volume

    private static String readStream(Context context) {
        AudioManager am = context.getSystemService(AudioManager.class);
        if (am == null) {
            return "no AudioManager";
        }
        return "media " + am.getStreamVolume(AudioManager.STREAM_MUSIC) + "/"
                + am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                + (am.isVolumeFixed() ? ", fixed volume" : "");
    }

    private static String stepStream(Context context, int direction) {
        AudioManager am = context.getSystemService(AudioManager.class);
        if (am == null) {
            return "no AudioManager";
        }
        String before = readStream(context);
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                direction > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
        return before + " -> " + readStream(context);
    }

    // ================================================================= 4. ECARX library

    /** A getter and setter pair found on an ECARX object, e.g. getMediaVolume() / setMediaVolume(int). */
    private static final class Pair {
        final Object target;
        final Method get;
        final Method set;

        Pair(Object target, Method get, Method set) {
            this.target = target;
            this.get = get;
            this.set = set;
        }

        String label() {
            return target.getClass().getSimpleName() + "." + get.getName() + "/" + set.getName();
        }
    }

    /** Found once per process: the ECARX volume pairs, and what the scan saw. */
    private static List<Pair> pairs;
    private static final List<String> seen = new ArrayList<>();

    /** Lists the ECARX audio classes and their volume methods. Blocking; also feeds the black box. */
    public static synchronized List<String> scan(Context context) {
        if (pairs == null) {
            pairs = new ArrayList<>();
            seen.clear();
            findEcarx(context);
        }
        return new ArrayList<>(seen);
    }

    private static String readEcarx(Context context) {
        List<Pair> found = pairsFor(context);
        if (found.isEmpty()) {
            return "no ECARX volume getter/setter pair (" + seen.size() + " lines in the scan)";
        }
        StringBuilder sb = new StringBuilder();
        for (Pair p : found) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(p.label()).append(" = ").append(safeGet(p));
        }
        return sb.toString();
    }

    private static String stepEcarx(Context context, int direction) throws Exception {
        List<Pair> found = pairsFor(context);
        if (found.isEmpty()) {
            return "no ECARX volume getter/setter pair";
        }
        StringBuilder sb = new StringBuilder();
        for (Pair p : found) {
            Object now = p.get.invoke(p.target);
            if (!(now instanceof Integer)) {
                continue;
            }
            int target = (Integer) now + direction;
            Object r;
            try {
                r = p.set.invoke(p.target, target);
            } catch (InvocationTargetException e) {
                r = "failed: " + root(e);
            }
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(p.label()).append(' ').append(now).append(" -> ").append(target)
                    .append(r == null ? "" : " (" + r + ")").append(", reads ").append(safeGet(p));
        }
        return sb.toString();
    }

    private static synchronized List<Pair> pairsFor(Context context) {
        scan(context);
        return pairs;
    }

    private static String safeGet(Pair p) {
        try {
            return String.valueOf(p.get.invoke(p.target));
        } catch (Throwable t) {
            return "? (" + root(t) + ")";
        }
    }

    private static void findEcarx(Context context) {
        List<String> names = ecarxClasses();
        seen.add(names.size() + " audio/volume/sound classes in the ECARX library");
        Map<String, Object> objects = new LinkedHashMap<>();
        for (String name : names) {
            Class<?> c;
            try {
                c = Class.forName(name, false, VolumeLab.class.getClassLoader());
            } catch (Throwable t) {
                continue;
            }
            List<String> methods = volumeMethods(c);
            seen.add(name + (methods.isEmpty() ? "" : "  " + methods));
            // Static factories: create(Context), getInstance(), get(Context)… on any audio class
            for (Method m : c.getMethods()) {
                if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType().isPrimitive()
                        || !FACTORY.matcher(m.getName()).matches()) {
                    continue;
                }
                Object made = make(m, context);
                if (made != null) {
                    objects.put(name + "." + m.getName(), made);
                }
            }
        }
        // One level down: getters on those objects that hand out more audio objects
        for (Map.Entry<String, Object> e : new ArrayList<>(objects.entrySet())) {
            for (Method m : e.getValue().getClass().getMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || m.getReturnType().isPrimitive()
                        || m.getDeclaringClass() == Object.class
                        || !AUDIO_CLASS.matcher(m.getReturnType().getName()).find()
                        || !FACTORY.matcher(m.getName()).matches()) {
                    continue;
                }
                Object made = make(m, e.getValue(), context);
                if (made != null) {
                    objects.put(e.getKey() + "." + m.getName(), made);
                }
            }
        }
        Set<Object> done = new LinkedHashSet<>();
        for (Map.Entry<String, Object> e : objects.entrySet()) {
            Object o = e.getValue();
            if (!done.add(o)) {
                continue;
            }
            seen.add("object " + e.getKey() + " -> " + o.getClass().getName()
                    + "  " + volumeMethods(o.getClass()));
            for (Method get : o.getClass().getMethods()) {
                if (get.getParameterTypes().length != 0 || get.getReturnType() != int.class
                        || !get.getName().startsWith("get")
                        || !get.getName().toLowerCase(Locale.US).contains("volume")) {
                    continue;
                }
                try {
                    Method set = o.getClass().getMethod("set" + get.getName().substring(3), int.class);
                    pairs.add(new Pair(o, get, set));
                    seen.add("pair " + o.getClass().getSimpleName() + "." + get.getName() + "/" + set.getName());
                } catch (NoSuchMethodException ignored) {
                    // a getter only
                }
            }
        }
        for (String line : seen) {
            BlackBox.note("volume-lab: ecarx " + line);
            AppLog.i(TAG, "ecarx " + line);
        }
    }

    /** Calls a factory taking nothing or a Context; null when it takes anything else or fails. */
    private static Object make(Method m, Context context) {
        return make(m, null, context);
    }

    private static Object make(Method m, Object on, Context context) {
        Class<?>[] params = m.getParameterTypes();
        try {
            if (params.length == 0) {
                return m.invoke(on);
            }
            if (params.length == 1 && params[0] == Context.class) {
                return m.invoke(on, context.getApplicationContext());
            }
        } catch (Throwable t) {
            seen.add("factory " + m.getDeclaringClass().getSimpleName() + "." + m.getName() + " failed: " + root(t));
        }
        return null;
    }

    /** Public methods with "volume" in the name, as {@code name(params)}. */
    private static List<String> volumeMethods(Class<?> c) {
        List<String> out = new ArrayList<>();
        try {
            for (Method m : c.getMethods()) {
                if (!m.getName().toLowerCase(Locale.US).contains("volume")) {
                    continue;
                }
                StringBuilder sb = new StringBuilder(m.getName()).append('(');
                Class<?>[] p = m.getParameterTypes();
                for (int i = 0; i < p.length; i++) {
                    sb.append(i == 0 ? "" : ",").append(p[i].getSimpleName());
                }
                out.add(sb.append(')').toString());
            }
        } catch (Throwable ignored) {
            // a class that does not load its methods
        }
        return out;
    }

    /** Classes in the ECARX jars whose names sound like audio. */
    @SuppressWarnings("deprecation")
    private static List<String> ecarxClasses() {
        List<String> out = new ArrayList<>();
        Set<String> jars = new LinkedHashSet<>();
        for (String env : new String[]{"BOOTCLASSPATH", "SYSTEMSERVERCLASSPATH"}) {
            String path = System.getenv(env);
            if (path == null) {
                continue;
            }
            for (String jar : path.split(":")) {
                if (jar.toLowerCase(Locale.US).contains("ecarx")) {
                    jars.add(jar);
                }
            }
        }
        for (String jar : jars) {
            try {
                dalvik.system.DexFile dex = new dalvik.system.DexFile(jar);
                Enumeration<String> names = dex.entries();
                while (names.hasMoreElements()) {
                    String n = names.nextElement();
                    String simple = n.substring(n.lastIndexOf('.') + 1);
                    if (n.contains("ecarx") && AUDIO_CLASS.matcher(simple).find()
                            && !n.contains("$Stub") && !n.contains("$Proxy")) {
                        out.add(n);
                    }
                }
                dex.close();
            } catch (Throwable t) {
                seen.add("could not list " + jar + ": " + root(t));
            }
        }
        return out;
    }

    // ================================================================= helpers

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = target.getClass().getMethod(name, types);
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause instanceof Exception ? (Exception) cause : e;
        }
    }

    static String root(Throwable t) {
        Throwable r = t;
        while ((r instanceof InvocationTargetException || r.getCause() != null) && r.getCause() != null) {
            r = r.getCause();
        }
        String m = r.getMessage();
        if (m != null && m.length() > 120) {
            m = m.substring(0, 120) + "…";
        }
        return r.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    private static void log(Way way, String what, String result) {
        String line = "volume-lab: " + way.name().toLowerCase(Locale.US) + " " + what + ": " + result;
        BlackBox.note(line);
        AppLog.i(TAG, line);
    }
}
