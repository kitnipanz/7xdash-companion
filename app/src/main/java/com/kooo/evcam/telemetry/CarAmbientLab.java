package com.kooo.evcam.telemetry;

import android.content.Context;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.blackbox.BlackBox;

import java.lang.reflect.Field;
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
 * Experiment: can 7xDash set the car's own ambient light through the ECARX car interface?
 *
 * <p>Nobody has written down which ECARX function numbers drive the ambient light on the 7X,
 * so this finds out on the car: it lists every {@code static final int} in the ECARX library
 * whose name sounds like ambient light ({@link #looksAmbient}), asks the car whether each is
 * supported and what it reads now, and sends a value only when the person asks, for one of
 * those names ({@link #set}). Nothing here runs on its own; every read and send goes to the
 * black box, so a working number can be told apart from a quiet one later.</p>
 *
 * <p>Blocking calls: run them off the main thread.</p>
 */
public final class CarAmbientLab {

    private static final String TAG = "CarAmbientLab";

    /** Words in ECARX constant names that point at the interior ambient light. */
    static final Pattern AMBIENT = Pattern.compile(
            "AMBIEN|AMBL|ATMOS|ATMO_|MOOD_?LIGHT|INTERIOR_?LIGHT|INNER_?LIGHT|RHYTHM|BREATH_?LIGHT|LIGHT_?COLOU?R|AMB_LIGHT");

    /** Where the ECARX classes live; the jar is found on the boot class path. */
    static final String PACKAGE = "com.ecarx.xui.adaptapi";

    /** Classes to look in when the jar cannot be listed. */
    private static final String[] FALLBACK_CLASSES = {
            "com.ecarx.xui.adaptapi.car.base.ICarFunction",
            "com.ecarx.xui.adaptapi.car.vehicle.IVehicle",
            "com.ecarx.xui.adaptapi.car.vehicle.IAmbienceLight",
            "com.ecarx.xui.adaptapi.car.vehicle.ILight",
            "com.ecarx.xui.adaptapi.car.hvac.IHvac",
            "com.ecarx.xui.adaptapi.car.sensor.ISensor",
    };

    /** One named number in the ECARX library. */
    public static final class Constant {
        public final String owner;
        public final String name;
        public final int value;
        /** Read back from the car: supported state and current value as text; null before {@link #read}. */
        public String supported;
        public String current;

        Constant(String owner, String name, int value) {
            this.owner = owner;
            this.name = name;
            this.value = value;
        }

        public String hex() {
            return String.format(Locale.US, "0x%08X", value);
        }

        /** Looks like a function number (they are 0x2xxxxxxx-style ids), not a small option value. */
        public boolean looksLikeFunction() {
            return (value & 0xFFFF0000) != 0;
        }
    }

    /** What a scan found. */
    public static final class Scan {
        public final String status;
        public final List<Constant> found;
        /** Every other int constant of the same classes, for picking values to send. */
        public final List<Constant> neighbours;
        public final boolean carReady;

        Scan(String status, List<Constant> found, List<Constant> neighbours, boolean carReady) {
            this.status = status;
            this.found = found;
            this.neighbours = neighbours;
            this.carReady = carReady;
        }
    }

    private CarAmbientLab() {
    }

    static boolean looksAmbient(String name) {
        return name != null && AMBIENT.matcher(name.toUpperCase(Locale.US)).find();
    }

    /**
     * Values that likely go with {@code function}: constants in the same class sharing the
     * longest name start (for example {@code AMBIENCE_LIGHT_MODE_*} for {@code AMBIENCE_LIGHT_MODE}).
     */
    public static List<Constant> optionsFor(Constant function, List<Constant> pool) {
        List<Constant> out = new ArrayList<>();
        String stem = stem(function.name);
        for (Constant c : pool) {
            if (c != function && c.owner.equals(function.owner) && !c.looksLikeFunction()
                    && stem.length() >= 4 && c.name.startsWith(stem)) {
                out.add(c);
            }
        }
        return out;
    }

    /** Name without a leading {@code FUNC_} / {@code SETTING_FUNC_} / {@code VEHICLE_FUNC_} style prefix. */
    static String stem(String name) {
        String n = name;
        for (String prefix : new String[]{"SETTING_FUNC_", "VEHICLE_FUNC_", "FUNC_", "SETTING_"}) {
            if (n.startsWith(prefix)) {
                n = n.substring(prefix.length());
                break;
            }
        }
        return n;
    }

    /** Lists the constants and reads each from the car. */
    public static Scan scan(Context context) {
        Set<String> classes = new LinkedHashSet<>(listClasses());
        String listed = classes.isEmpty() ? "jar not listed, tried known classes" : classes.size() + " ECARX classes";
        for (String fallback : FALLBACK_CLASSES) {
            classes.add(fallback);
        }
        List<Constant> found = new ArrayList<>();
        List<Constant> neighbours = new ArrayList<>();
        int loaded = 0;
        for (String name : classes) {
            Class<?> c;
            try {
                c = Class.forName(name, false, CarAmbientLab.class.getClassLoader());
            } catch (Throwable t) {
                continue;
            }
            loaded++;
            boolean any = false;
            List<Constant> mine = new ArrayList<>();
            for (Field f : fields(c)) {
                int mod = f.getModifiers();
                if (!Modifier.isStatic(mod) || f.getType() != int.class) {
                    continue;
                }
                try {
                    Constant k = new Constant(c.getSimpleName(), f.getName(), f.getInt(null));
                    mine.add(k);
                    if (looksAmbient(f.getName())) {
                        found.add(k);
                        any = true;
                    }
                } catch (Throwable ignored) {
                    // unreadable field
                }
            }
            if (any) {
                neighbours.addAll(mine);
            }
        }
        if (loaded == 0) {
            return new Scan("This head unit has no ECARX car interface", found, neighbours, false);
        }
        Object car = EcarxSource.carForLab(context);
        Object function = car == null ? null : invoke(car, "getICarFunction");
        String status = listed + ", " + loaded + " opened, " + found.size() + " ambient-light names";
        if (function == null) {
            status += " · car function interface not available";
        } else {
            for (Constant k : found) {
                if (k.looksLikeFunction()) {
                    read(function, k);
                }
            }
        }
        StringBuilder log = new StringBuilder("Car ambient lab scan: ").append(status);
        for (Constant k : found) {
            log.append("\n  ").append(k.owner).append('.').append(k.name).append(' ').append(k.hex())
                    .append(" supported=").append(k.supported).append(" value=").append(k.current);
        }
        BlackBox.note(log.toString());
        AppLog.i(TAG, log.toString());
        return new Scan(status, found, neighbours, function != null);
    }

    /** Reads one function number back into {@code k}. */
    public static void refresh(Context context, Constant k) {
        Object car = EcarxSource.carForLab(context);
        Object function = car == null ? null : invoke(car, "getICarFunction");
        if (function != null) {
            read(function, k);
        }
    }

    /**
     * Sends {@code value} to function {@code k}, only for an ambient-light name. Returns a line
     * saying what happened and what the car reads after.
     */
    public static String set(Context context, Constant k, int value) {
        if (!looksAmbient(k.name)) {
            return "Refused: not an ambient light function";
        }
        Object car = EcarxSource.carForLab(context);
        Object function = car == null ? null : invoke(car, "getICarFunction");
        if (function == null) {
            return "No car function interface";
        }
        String result;
        Method setter = EcarxSource.findMethod(function, "setFunctionValue", int.class, int.class);
        try {
            if (setter != null) {
                result = String.valueOf(setter.invoke(function, k.value, value));
            } else {
                Method zoned = EcarxSource.findMethod(function, "setFunctionValue", int.class, int.class, int.class);
                if (zoned == null) {
                    return "No setFunctionValue on this car";
                }
                // Zone 0: "all" / not zoned on ECARX
                result = String.valueOf(zoned.invoke(function, k.value, 0, value));
            }
        } catch (Throwable t) {
            result = "threw " + rootMessage(t);
        }
        try {
            Thread.sleep(400);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        read(function, k);
        String line = "set " + k.name + " (" + k.hex() + ") = " + value + " → " + result + " · reads " + k.current;
        BlackBox.note("Car ambient lab: " + line);
        AppLog.i(TAG, line);
        return line;
    }

    private static void read(Object function, Constant k) {
        Method supported = EcarxSource.findMethod(function, "isFunctionSupported", int.class);
        Method get = EcarxSource.findMethod(function, "getFunctionValue", int.class);
        k.supported = supported == null ? "?" : safe(() -> supported.invoke(function, k.value));
        k.current = get == null ? "?" : safe(() -> get.invoke(function, k.value));
    }

    // ================================================================= finding the classes

    /** Class names in the ECARX jars on the boot class path; empty when they cannot be listed. */
    @SuppressWarnings("deprecation")
    private static List<String> listClasses() {
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
                    if (n.startsWith(PACKAGE) && !n.contains("$Stub") && !n.contains("$Proxy")) {
                        out.add(n);
                    }
                }
                dex.close();
            } catch (Throwable t) {
                AppLog.w(TAG, "could not list " + jar + ": " + rootMessage(t));
            }
        }
        return out;
    }

    private static List<Field> fields(Class<?> c) {
        Map<String, Field> out = new LinkedHashMap<>();
        try {
            for (Field f : c.getFields()) {
                out.put(f.getName(), f);
            }
            for (Field f : c.getDeclaredFields()) {
                f.setAccessible(true);
                out.putIfAbsent(f.getName(), f);
            }
        } catch (Throwable ignored) {
            // some classes refuse reflection
        }
        return new ArrayList<>(out.values());
    }

    private interface Call {
        Object run() throws Exception;
    }

    private static String safe(Call call) {
        try {
            Object r = call.run();
            return String.valueOf(r);
        } catch (Throwable t) {
            return "error " + rootMessage(t);
        }
    }

    private static Object invoke(Object target, String name) {
        Method m = EcarxSource.findMethod(target, name);
        if (m == null) {
            return null;
        }
        try {
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + (root.getMessage() == null ? "" : ": " + root.getMessage());
    }
}
