package com.kooo.evcam.telemetry;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.blackbox.BlackBox;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The car's settings service ({@code setting_zeekr}), a second way into car signals next to the
 * ECARX interface ({@link CarAmbientLab}). Read only: nothing is ever written.
 *
 * <p>Two jobs:</p>
 * <ul>
 *   <li><b>Blind spot lamp</b>: {@code ID_SETTING_FUNC_LCMA_INDCN_DATA} (lane change merge aid
 *   indicator), one property with area {@link #AREA_LEFT} for the left mirror lamp and
 *   {@link #AREA_RIGHT} for the right one. {@link BlindSpotWatch} reads it through
 *   {@link #lcmaSides()} and counts it like the RCTA flags.</li>
 *   <li><b>Capture</b>: while a {@link BlindSpotLog} session is open (Report blind-spot signals on),
 *   the full property list with every area's value goes to the log once, then every value the
 *   service pushes that changed. Blind-spot-looking names are always written; others at most once
 *   per {@link #OTHER_EVERY_MS} each, since speed and the like are pushed all the time.</li>
 * </ul>
 *
 * <h3>How it connects</h3>
 * <p>{@code android.car.Car.createCar(Context, Handler, long, CarServiceLifecycleListener)}: the
 * one with a lifecycle listener, because the car library kills the whole process when the car
 * service restarts if no listener is given. Then {@code getCarManager("setting_zeekr")}, whose
 * private field {@code mMgr} is a property manager: {@code getPropertyList}, {@code getProperty},
 * {@code registerCallback}. Property ids are taken from the manager's {@code public static int}
 * constants by name, never hard-coded. All by reflection; a car without any of it just reads null.</p>
 *
 * <p>Idea from dts88/zeekr-shortcut-car PR #174 notes (property names and areas, untested on our car).</p>
 */
public final class SettingZeekrWatch {

    private static final String TAG = "SettingZeekrWatch";
    private static final String CAR_CLASS = "android.car.Car";
    private static final String LIFECYCLE_CLASS = "android.car.Car$CarServiceLifecycleListener";
    static final String SERVICE = "setting_zeekr";
    static final String LCMA = "ID_SETTING_FUNC_LCMA_INDCN_DATA";
    public static final int AREA_LEFT = 0x1;
    public static final int AREA_RIGHT = 0x4;
    /** A non-blind-spot property is written to the log at most this often. */
    static final long OTHER_EVERY_MS = 1_000L;
    /** Names written on every change: blind spot, cross traffic, door open warning, lane change. */
    static final Pattern WATCHED = Pattern.compile(
            "LCMA|BSD|BSW|BSI|BLIND|BLIS|LCA|LANE_CHANGE|DOW|DOOROPENWARN|DOOR_OPEN_WARN|RCTA|RCTB|RCW|CROSS|SIDE_WARN|APPROACH|INDCN",
            Pattern.CASE_INSENSITIVE);

    private static SettingZeekrWatch installed;

    private final Context app;
    private final HandlerThread thread = new HandlerThread("setting-zeekr-watch");
    private final Handler work;

    // Worker thread, except where volatile
    private Object car;
    private volatile Object base;
    private volatile Method getProperty;
    private Method unregister;
    private volatile int lcmaId = -1;
    private volatile Class<?> lcmaType = Integer.class;
    /** id → name, from the manager's constants. */
    private final Map<Integer, String> names = new HashMap<>();
    /** id → config, from getPropertyList. */
    private Map<Integer, Object> configs = new HashMap<>();
    /** "id/area" → last value text, for change detection. */
    private final Map<String, String> last = new HashMap<>();
    /** "id/area" → when it was last written to the log. */
    private final Map<String, Long> loggedAt = new HashMap<>();
    /** The log session last dumped into, so a new session gets the full list again. */
    private BlindSpotLog dumpedInto;
    private volatile Integer lastLcma;
    private volatile Method idGetter;
    private volatile Method areaGetter;
    private volatile Method valueGetter;
    private volatile String status = "not started";

    private SettingZeekrWatch(Context context) {
        app = context.getApplicationContext();
        thread.start();
        work = new Handler(thread.getLooper());
    }

    /** Application start. Repeat calls do nothing. */
    public static void install(Context context) {
        if (installed != null) {
            return;
        }
        installed = new SettingZeekrWatch(context);
        installed.work.post(installed::connect);
    }

    /** Connection state, for the log header. */
    public static String status() {
        SettingZeekrWatch w = installed;
        return w == null ? "not installed" : w.status;
    }

    /**
     * LCMA blind spot lamp now: bits {@link VehicleState#TURN_LEFT} / {@link VehicleState#TURN_RIGHT}
     * for each side whose lamp reads a warning ({@link BlindSpotWatch#builtInWarning}), 0 for none,
     * null when the property cannot be read. Reads fresh; any thread.
     */
    public static Integer lcmaSides() {
        SettingZeekrWatch w = installed;
        return w == null ? null : w.readLcma();
    }

    /** A new log session started: write the full property list into it (on the worker thread). */
    public static void dumpInto(BlindSpotLog log) {
        SettingZeekrWatch w = installed;
        if (w != null && log != null) {
            w.work.post(() -> w.dump(log));
        }
    }

    // ================================================================= connect

    private void connect() {
        Class<?> carClass;
        Class<?> lifecycle;
        Method create;
        try {
            carClass = Class.forName(CAR_CLASS, true, app.getClassLoader());
            lifecycle = Class.forName(LIFECYCLE_CLASS, true, carClass.getClassLoader());
            create = carClass.getMethod("createCar", Context.class, Handler.class, long.class, lifecycle);
        } catch (Throwable t) {
            note("unavailable: no createCar with a lifecycle listener (" + describe(t) + ")");
            return;
        }
        InvocationHandler calls = (proxy, method, args) -> {
            switch (method.getName()) {
                case "onLifecycleChanged":
                    boolean ready = args != null && args.length > 1 && Boolean.TRUE.equals(args[1]);
                    work.post(() -> onLifecycle(ready));
                    return null;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return args != null && proxy == args[0];
                case "toString":
                    return TAG + ".lifecycle";
                default:
                    return null;
            }
        };
        try {
            Object listener = Proxy.newProxyInstance(lifecycle.getClassLoader(), new Class<?>[]{lifecycle}, calls);
            car = create.invoke(null, app, work, 0L, listener);
            if (car == null) {
                note("failed: createCar returned null");
            }
        } catch (Throwable t) {
            note("failed: createCar " + describe(t));
        }
    }

    private void onLifecycle(boolean ready) {
        if (car == null) {
            return;
        }
        detach();
        if (!ready) {
            note("car service disconnected; the car library reconnects by itself");
            return;
        }
        attach();
    }

    private void attach() {
        try {
            Object manager = car.getClass().getMethod("getCarManager", String.class).invoke(car, SERVICE);
            if (manager == null) {
                note("unavailable: no " + SERVICE + " manager");
                return;
            }
            Field field = field(manager.getClass(), "mMgr");
            if (field == null) {
                note("unavailable: no mMgr in " + manager.getClass().getName());
                return;
            }
            field.setAccessible(true);
            Object b = field.get(manager);
            if (b == null) {
                note("failed: mMgr is null");
                return;
            }
            names.clear();
            for (Field f : manager.getClass().getFields()) {
                if (Modifier.isStatic(f.getModifiers()) && f.getType() == int.class) {
                    try {
                        names.putIfAbsent(f.getInt(null), f.getName());
                    } catch (Throwable ignored) {
                        // skip
                    }
                }
            }
            configs = configs(b);
            int lcma = constant(manager.getClass(), LCMA);
            Object lcmaConfig = configs.get(lcma);
            lcmaType = type(lcmaConfig);
            getProperty = b.getClass().getMethod("getProperty", Class.class, int.class, int.class);
            unregister = b.getClass().getMethod("unregisterCallback");
            Method register = oneArgument(b.getClass(), "registerCallback");
            base = b;
            lcmaId = lcmaConfig != null ? lcma : -1;
            if (register != null && register.getParameterTypes()[0].isInterface()) {
                Class<?> cb = register.getParameterTypes()[0];
                register.invoke(b, Proxy.newProxyInstance(cb.getClassLoader(), new Class<?>[]{cb}, events));
            }
            note("connected: " + configs.size() + " properties, " + names.size() + " names, LCMA "
                    + (lcmaId < 0 ? "not listed" : hex(lcmaId) + " areas " + Arrays.toString(areas(lcmaConfig)))
                    + ", callback " + (register != null) + "; LCMA now " + readLcma());
            BlindSpotLog log = BlindSpotLog.current();
            if (log != null) {
                dump(log);
            }
        } catch (Throwable t) {
            detach();
            note("failed: attach " + describe(t));
        }
    }

    private void detach() {
        Object b = base;
        Method u = unregister;
        base = null;
        getProperty = null;
        unregister = null;
        lcmaId = -1;
        last.clear();
        dumpedInto = null;
        if (b != null && u != null) {
            try {
                u.invoke(b);
            } catch (Throwable ignored) {
                // car service already gone
            }
        }
    }

    // ================================================================= read

    private Integer readLcma() {
        int id = lcmaId;
        if (id < 0) {
            return null;
        }
        Integer left = intValue(read(id, AREA_LEFT, lcmaType));
        Integer right = intValue(read(id, AREA_RIGHT, lcmaType));
        if (left == null && right == null) {
            return null;
        }
        int out = 0;
        if (BlindSpotWatch.builtInWarning(left)) {
            out |= VehicleState.TURN_LEFT;
        }
        if (BlindSpotWatch.builtInWarning(right)) {
            out |= VehicleState.TURN_RIGHT;
        }
        if (!Objects.equals(lastLcma, out) || !Objects.equals(lastLcmaRaw, left + "/" + right)) {
            lastLcmaRaw = left + "/" + right;
            BlackBox.note("LCMA blind spot lamp: left " + left + ", right " + right);
            lastLcma = out;
        }
        return out;
    }

    private volatile String lastLcmaRaw;

    /** The value object's getValue(), or null. */
    private Object read(int id, int area, Class<?> type) {
        Object b = base;
        Method get = getProperty;
        if (b == null || get == null) {
            return null;
        }
        try {
            Object value = get.invoke(b, type, id, area);
            return value == null ? null : call(value, getter(value, 2));
        } catch (Throwable t) {
            return null;
        }
    }

    // ================================================================= capture

    private final InvocationHandler events = (proxy, method, args) -> {
        switch (method.getName()) {
            case "onChangeEvent":
                if (args != null && args.length == 1 && args[0] != null && BlindSpotLog.current() != null) {
                    Object v = args[0];
                    long at = System.currentTimeMillis();
                    deliver(v, at);
                }
                return null;
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return args != null && proxy == args[0];
            case "toString":
                return TAG + ".events";
            default:
                return null;
        }
    };

    /** From the library's thread: hand over to the worker thread. */
    private void deliver(Object value, long atMs) {
        if (Looper.myLooper() == work.getLooper()) {
            onEvent(value, atMs);
        } else {
            work.post(() -> onEvent(value, atMs));
        }
    }

    private void onEvent(Object value, long atMs) {
        BlindSpotLog log = BlindSpotLog.current();
        if (log == null) {
            return;
        }
        int id = intOf(value, getter(value, 0));
        int area = intOf(value, getter(value, 1));
        String now = text(call(value, getter(value, 2)));
        String key = id + "/" + area;
        String was = last.get(key);
        if (last.containsKey(key) && Objects.equals(was, now)) {
            return;
        }
        last.put(key, now);
        String name = names.containsKey(id) ? names.get(id) : "?";
        boolean watched = WATCHED.matcher(name).find();
        long t = SystemClock.elapsedRealtime();
        Long prev = loggedAt.get(key);
        if (!watched && prev != null && t - prev < OTHER_EVERY_MS) {
            return;
        }
        loggedAt.put(key, t);
        String line = "[setting] " + name + " " + hex(id) + " area " + hex(area) + ": " + was + " → " + now;
        log.line(line + "  | " + BlindSpotLog.context(Telemetry.get().latest()));
        if (watched) {
            AppLog.i(TAG, line);
        }
    }

    /** Full list: every property, its areas, and each area's value now. */
    private void dump(BlindSpotLog log) {
        if (log == dumpedInto || base == null) {
            return;
        }
        dumpedInto = log;
        List<Integer> ids = new ArrayList<>(configs.keySet());
        java.util.Collections.sort(ids);
        log.section("Settings service " + SERVICE + " (" + ids.size() + " properties) — " + status);
        for (int id : ids) {
            Object config = configs.get(id);
            Class<?> type = type(config);
            int[] areas = areas(config);
            if (areas.length == 0) {
                areas = new int[]{0};
            }
            StringBuilder sb = new StringBuilder();
            String name = names.containsKey(id) ? names.get(id) : "?";
            sb.append(WATCHED.matcher(name).find() ? "* " : "  ")
                    .append(name).append(' ').append(hex(id)).append(' ').append(type.getSimpleName()).append(" =");
            for (int area : areas) {
                String v = text(read(id, area, type));
                sb.append(' ').append(hex(area)).append(':').append(v);
                last.put(id + "/" + area, v);
            }
            log.raw(sb.toString());
        }
        log.raw("(* = blind-spot-looking name, every change logged; others at most once a second)");
        log.section("Changes (ECARX lines and [setting] lines mixed, by time)");
    }

    private void note(String detail) {
        status = detail;
        AppLog.i(TAG, detail);
        BlackBox.note("setting_zeekr: " + detail);
    }

    // ================================================================= reflection helpers

    private Method getter(Object value, int which) {
        Method m = which == 0 ? idGetter : which == 1 ? areaGetter : valueGetter;
        if (m != null && m.getDeclaringClass().isInstance(value)) {
            return m;
        }
        try {
            m = value.getClass().getMethod(which == 0 ? "getPropertyId" : which == 1 ? "getAreaId" : "getValue");
        } catch (NoSuchMethodException e) {
            return null;
        }
        if (which == 0) {
            idGetter = m;
        } else if (which == 1) {
            areaGetter = m;
        } else {
            valueGetter = m;
        }
        return m;
    }

    private static Object call(Object target, Method m) {
        if (m == null) {
            return null;
        }
        try {
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int intOf(Object target, Method m) {
        Object v = call(target, m);
        return v instanceof Integer ? (Integer) v : -1;
    }

    static Integer intValue(Object v) {
        if (v instanceof Integer) {
            return (Integer) v;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v instanceof Boolean) {
            return (Boolean) v ? 1 : 0;
        }
        return null;
    }

    static String text(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof int[]) {
            return Arrays.toString((int[]) v);
        }
        if (v instanceof float[]) {
            return Arrays.toString((float[]) v);
        }
        if (v instanceof long[]) {
            return Arrays.toString((long[]) v);
        }
        if (v instanceof byte[]) {
            return Arrays.toString((byte[]) v);
        }
        if (v instanceof Object[]) {
            return Arrays.deepToString((Object[]) v);
        }
        return String.valueOf(v);
    }

    static String hex(int v) {
        return "0x" + Integer.toHexString(v).toUpperCase(java.util.Locale.US);
    }

    private static int constant(Class<?> manager, String name) {
        try {
            Field f = manager.getField(name);
            return f.getType() == int.class ? f.getInt(null) : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static Map<Integer, Object> configs(Object b) throws Exception {
        Map<Integer, Object> out = new HashMap<>();
        Object list = b.getClass().getMethod("getPropertyList").invoke(b);
        if (list instanceof List) {
            for (Object config : (List<?>) list) {
                if (config == null) {
                    continue;
                }
                Object id = config.getClass().getMethod("getPropertyId").invoke(config);
                if (id instanceof Integer) {
                    out.put((Integer) id, config);
                }
            }
        }
        return out;
    }

    private static int[] areas(Object config) {
        if (config == null) {
            return new int[0];
        }
        try {
            Object a = config.getClass().getMethod("getAreaIds").invoke(config);
            return a instanceof int[] ? (int[]) a : new int[0];
        } catch (Throwable t) {
            return new int[0];
        }
    }

    private static Class<?> type(Object config) {
        if (config == null) {
            return Integer.class;
        }
        try {
            Object t = config.getClass().getMethod("getPropertyType").invoke(config);
            return t instanceof Class ? (Class<?>) t : Integer.class;
        } catch (Throwable e) {
            return Integer.class;
        }
    }

    private static Field field(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                return k.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // look in the parent
            }
        }
        return null;
    }

    private static Method oneArgument(Class<?> c, String name) {
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == 1) {
                return m;
            }
        }
        return null;
    }

    static String describe(Throwable t) {
        Throwable e = t;
        while (e instanceof InvocationTargetException && e.getCause() != null) {
            e = e.getCause();
        }
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }
}
