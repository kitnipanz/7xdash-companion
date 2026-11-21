package com.kooo.evcam.telemetry;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;

import com.kooo.evcam.R;
import com.kooo.evcam.blackbox.BlackBox;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * One-shot check: which ways into the car's data work for this install of 7xDash?
 *
 * <p>Why it exists: the same APK reads the car through ECARX when installed with the App Lab
 * installer, but not when installed from the Zeekr Store. This tries every way we know, each with a
 * time limit, and says what happened, so one run per install shows where the Store install is
 * stopped and which other way is left:</p>
 * <ol>
 *   <li><b>ECARX library</b> ({@link EcarxSource}): is the class visible, does {@code Car.create}
 *   answer, do the function and sensor managers answer a read.</li>
 *   <li><b>android.car</b> {@code CarPropertyManager}: the standard Android Automotive values
 *   (speed, gear, battery …), each read once.</li>
 *   <li><b>android.car</b> {@code setting_zeekr}: Zeekr's own manager ({@link SettingZeekrWatch}).</li>
 *   <li><b>Zeekr and ECARX packages</b>: which are installed, what services they export, and which
 *   of the permissions they define an app could ask for.</li>
 * </ol>
 *
 * <p>Read only: nothing is ever written to the car. Blocking (up to about half a minute when
 * calls hang): run it off the main thread. The summary lines also go to the black box.</p>
 */
public final class CarAccessCheck {

    /** One call that does not answer in this time counts as hanging. */
    static final long TIMEOUT_MS = 8_000L;

    /** Car permissions the manifest declares; the dangerous ones among them are asked at run time. */
    public static final String[] DECLARED_CAR_PERMISSIONS = {
            "android.car.permission.CAR_SPEED",
            "android.car.permission.CAR_ENERGY",
            "android.car.permission.CAR_POWERTRAIN",
            "android.car.permission.CAR_EXTERIOR_ENVIRONMENT",
            "android.car.permission.CAR_ENERGY_PORTS",
            "android.car.permission.CAR_INFO",
    };

    /** Further car permissions looked at (not declared): what the device says they are. */
    private static final String[] OTHER_CAR_PERMISSIONS = {
            "android.car.permission.CAR_MILEAGE",
            "android.car.permission.CAR_EXTERIOR_LIGHTS",
            "android.car.permission.CONTROL_CAR_DOORS",
            "android.car.permission.CONTROL_CAR_SEATS",
            "android.car.permission.READ_CAR_STEERING",
            "android.car.permission.CAR_DYNAMICS_STATE",
    };

    /**
     * Standard property names (constants of {@code android.car.VehiclePropertyIds}), each read
     * once. Ids are looked up by name, so a name this head unit does not know is just skipped.
     */
    static final String[] STANDARD_PROPERTIES = {
            "PERF_VEHICLE_SPEED", "GEAR_SELECTION", "CURRENT_GEAR", "IGNITION_STATE", "PARKING_BRAKE_ON",
            "EV_BATTERY_LEVEL", "RANGE_REMAINING", "EV_CHARGE_PORT_OPEN", "ENV_OUTSIDE_TEMPERATURE",
            "NIGHT_MODE", "PERF_ODOMETER", "PERF_STEERING_ANGLE", "TURN_SIGNAL_STATE", "HEADLIGHTS_STATE",
            "HIGH_BEAM_LIGHTS_STATE", "HAZARD_LIGHTS_STATE", "DOOR_POS", "SEAT_BELT_BUCKLED",
            "SEAT_OCCUPANCY", "INFO_MAKE", "INFO_MODEL",
    };

    /** At most this many lines for services and permissions of the Zeekr / ECARX packages. */
    private static final int MAX_PACKAGE_LINES = 40;

    public enum State { OK, FAIL, WARN, INFO, SECTION }

    /** One line of the result. */
    public static final class Line {
        public final State state;
        public final String label;
        public final String detail;

        public Line(State state, String label, String detail) {
            this.state = state;
            this.label = label;
            this.detail = detail == null ? "" : detail;
        }

        @Override
        public String toString() {
            String mark;
            switch (state) {
                case OK:
                    mark = "[OK]   ";
                    break;
                case FAIL:
                    mark = "[FAIL] ";
                    break;
                case WARN:
                    mark = "[WARN] ";
                    break;
                case SECTION:
                    return "\n== " + label + " ==";
                default:
                    mark = "[INFO] ";
                    break;
            }
            return mark + label + (detail.isEmpty() ? "" : " — " + detail);
        }
    }

    private CarAccessCheck() {
    }

    /** Runs every check. Blocking; not on the main thread. Summary lines first. */
    public static List<Line> run(Context context) {
        Context app = context.getApplicationContext();
        List<Line> summary = new ArrayList<>();
        List<Line> body = new ArrayList<>();
        install(app, body);
        ecarx(app, body, summary);
        androidCar(app, body, summary);
        packages(app, body, summary);
        permissions(app, body);

        List<Line> out = new ArrayList<>();
        out.add(new Line(State.SECTION, app.getString(R.string.cac_sec_summary), null));
        out.addAll(summary);
        out.addAll(body);
        for (Line l : summary) {
            BlackBox.note("Car access check: " + l);
        }
        return out;
    }

    /** Everything as plain text, for Copy all. */
    public static String text(List<Line> lines) {
        StringBuilder sb = new StringBuilder("7xDash car access check, ")
                .append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new java.util.Date()))
                .append('\n');
        for (Line l : lines) {
            sb.append(l).append('\n');
        }
        return sb.toString();
    }

    // ================================================================= this install

    private static void install(Context app, List<Line> out) {
        out.add(new Line(State.SECTION, app.getString(R.string.cac_sec_install), null));
        PackageManager pm = app.getPackageManager();
        String pkg = app.getPackageName();
        try {
            PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
            out.add(new Line(State.INFO, "package", pkg + " " + info.versionName + " (" + info.getLongVersionCode() + ")"));
            out.add(new Line(State.INFO, "signer", signer(info)));
            ApplicationInfo ai = info.applicationInfo;
            if (ai != null) {
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                out.add(new Line(State.INFO, "app", (system ? "system app" : "normal app")
                        + ", target SDK " + ai.targetSdkVersion + ", dir " + ai.sourceDir));
            }
        } catch (Throwable t) {
            out.add(new Line(State.WARN, "package", describe(t)));
        }
        out.add(new Line(State.INFO, "installer", installer(pm, pkg)));
        out.add(new Line(State.INFO, "process", "uid " + Process.myUid() + ", " + Process.myUserHandle()
                + ", pid " + Process.myPid()));
        out.add(new Line(State.INFO, "head unit", Build.MANUFACTURER + " " + Build.MODEL + ", Android "
                + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + "), " + Build.DISPLAY));
    }

    private static String installer(PackageManager pm, String pkg) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                InstallSourceInfo s = pm.getInstallSourceInfo(pkg);
                return "installed by " + s.getInstallingPackageName() + ", started by " + s.getInitiatingPackageName();
            }
            @SuppressWarnings("deprecation")
            String by = pm.getInstallerPackageName(pkg);
            return "installed by " + by;
        } catch (Throwable t) {
            return describe(t);
        }
    }

    private static String signer(PackageInfo info) {
        try {
            Signature[] signers = info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
            if (signers == null || signers.length == 0) {
                return "?";
            }
            byte[] d = MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray());
            StringBuilder sb = new StringBuilder("SHA-256 ");
            for (int i = 0; i < 8; i++) {
                sb.append(String.format(Locale.US, "%02X", d[i]));
            }
            return sb.append('…').toString();
        } catch (Throwable t) {
            return describe(t);
        }
    }

    // ================================================================= 1. ECARX

    private static void ecarx(Context app, List<Line> out, List<Line> summary) {
        out.add(new Line(State.SECTION, app.getString(R.string.cac_sec_ecarx), null));

        StringBuilder boot = new StringBuilder();
        String env = System.getenv("BOOTCLASSPATH");
        if (env != null) {
            for (String part : env.split(":")) {
                String lower = part.toLowerCase(Locale.US);
                if (lower.contains("ecarx") || lower.contains("zeekr")) {
                    boot.append(boot.length() == 0 ? "" : ", ").append(part);
                }
            }
        }
        out.add(new Line(boot.length() > 0 ? State.OK : State.WARN, "BOOTCLASSPATH",
                env == null ? "not set" : boot.length() > 0 ? boot.toString() : "no ECARX or Zeekr jar"));

        Telemetry.CarLink running = Telemetry.get().carLink();
        String runningDetail = Telemetry.get().carDetail();
        out.add(new Line(running == Telemetry.CarLink.CONNECTED ? State.OK : State.INFO,
                "7xDash vehicle signals now", running + (runningDetail == null ? "" : ": " + runningDetail)));

        Class<?> carClass;
        try {
            carClass = Class.forName(EcarxSource.CAR_CLASS);
            ClassLoader loader = carClass.getClassLoader();
            out.add(new Line(State.OK, "class " + EcarxSource.CAR_CLASS,
                    "found, loader " + (loader == null ? "boot" : loader.getClass().getSimpleName())));
        } catch (Throwable t) {
            out.add(new Line(State.FAIL, "class " + EcarxSource.CAR_CLASS, describe(t)));
            summary.add(new Line(State.FAIL, app.getString(R.string.cac_v_ecarx_no_class), describe(t)));
            return;
        }
        Method create = null;
        for (Method m : carClass.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals("create") && p.length == 1 && p[0] == Context.class) {
                create = m;
                break;
            }
        }
        if (create == null) {
            out.add(new Line(State.FAIL, "Car.create(Context)", "no such method"));
            summary.add(new Line(State.FAIL, app.getString(R.string.cac_v_ecarx_no_class), "no Car.create(Context)"));
            return;
        }

        HandlerThread thread = new HandlerThread("car-access-ecarx");
        thread.start();
        Handler h = new Handler(thread.getLooper());
        try {
            final Method createCar = create;
            Outcome made = timed(h, () -> createCar.invoke(null, app));
            if (made.timedOut) {
                out.add(new Line(State.FAIL, "Car.create", "no answer within " + seconds(TIMEOUT_MS)));
                summary.add(new Line(State.FAIL, app.getString(R.string.cac_v_ecarx_hang, TIMEOUT_MS / 1000), null));
                return;
            }
            if (made.error != null) {
                out.add(new Line(State.FAIL, "Car.create", describe(made.error) + " (" + made.ms + " ms)"));
                summary.add(new Line(State.FAIL, app.getString(R.string.cac_v_ecarx_error), describe(made.error)));
                return;
            }
            Object car = made.value;
            if (car == null) {
                out.add(new Line(State.FAIL, "Car.create", "returned null (" + made.ms + " ms)"));
                summary.add(new Line(State.FAIL, app.getString(R.string.cac_v_ecarx_null), null));
                return;
            }
            out.add(new Line(State.OK, "Car.create", car.getClass().getName() + " (" + made.ms + " ms)"));

            Object function = manager(h, car, "getICarFunction", out);
            Object sensor = manager(h, car, "getSensorManager", out);
            int answered = 0;
            int asked = 0;
            if (function != null) {
                asked += 2;
                answered += read(h, out, function, "isFunctionSupported", Signal.IGNITION) ? 1 : 0;
                answered += read(h, out, function, "getFunctionValue", Signal.IGNITION) ? 1 : 0;
            }
            if (sensor != null) {
                asked += 3;
                answered += read(h, out, sensor, "getSensorEvent", Signal.GEAR) ? 1 : 0;
                answered += read(h, out, sensor, "getSensorLatestValue", Signal.SPEED) ? 1 : 0;
                answered += read(h, out, sensor, "getSensorLatestValue", Signal.BATTERY) ? 1 : 0;
            }
            if (answered > 0) {
                summary.add(new Line(State.OK, app.getString(R.string.cac_v_ecarx_ok, answered, asked), null));
            } else {
                summary.add(new Line(State.FAIL, app.getString(R.string.cac_v_ecarx_silent),
                        "function=" + (function != null) + ", sensor=" + (sensor != null)));
            }
        } finally {
            thread.quitSafely();
        }
    }

    private static Object manager(Handler h, Object car, String name, List<Line> out) {
        Method m = EcarxSource.findMethod(car, name);
        if (m == null) {
            out.add(new Line(State.FAIL, name, "no such method"));
            return null;
        }
        Outcome o = timed(h, () -> m.invoke(car));
        if (o.timedOut) {
            out.add(new Line(State.FAIL, name, "no answer within " + seconds(TIMEOUT_MS)));
            return null;
        }
        if (o.error != null || o.value == null) {
            out.add(new Line(State.FAIL, name, o.error != null ? describe(o.error) : "null"));
            return null;
        }
        out.add(new Line(State.OK, name, o.value.getClass().getName()));
        return o.value;
    }

    /** One ECARX read of a signal; true when it answered (any value, placeholders too). */
    private static boolean read(Handler h, List<Line> out, Object target, String method, Signal signal) {
        String label = method + "(" + signal.name() + " " + hex(signal.id) + ")";
        Method m = EcarxSource.findMethod(target, method, int.class);
        if (m == null) {
            out.add(new Line(State.FAIL, label, "no such method"));
            return false;
        }
        Outcome o = timed(h, () -> m.invoke(target, signal.id));
        if (o.timedOut) {
            out.add(new Line(State.FAIL, label, "no answer within " + seconds(TIMEOUT_MS)));
            return false;
        }
        if (o.error != null) {
            out.add(new Line(State.FAIL, label, describe(o.error)));
            return false;
        }
        out.add(new Line(o.value == null ? State.WARN : State.OK, label, value(o.value)));
        return o.value != null;
    }

    // ================================================================= 2–3. android.car

    private static void androidCar(Context app, List<Line> out, List<Line> summary) {
        out.add(new Line(State.SECTION, app.getString(R.string.cac_sec_android_car), null));
        out.add(new Line(State.INFO, "7xDash setting_zeekr now", SettingZeekrWatch.status()));

        Class<?> carClass;
        Class<?> lifecycle;
        Method create;
        try {
            carClass = Class.forName("android.car.Car", true, app.getClassLoader());
            lifecycle = Class.forName("android.car.Car$CarServiceLifecycleListener", true, carClass.getClassLoader());
            create = carClass.getMethod("createCar", Context.class, Handler.class, long.class, lifecycle);
            out.add(new Line(State.OK, "class android.car.Car", "found"));
        } catch (Throwable t) {
            out.add(new Line(State.FAIL, "class android.car.Car", describe(t)));
            summary.add(new Line(State.FAIL, app.getString(R.string.cac_v_car_none), describe(t)));
            return;
        }

        HandlerThread thread = new HandlerThread("car-access-android-car");
        thread.start();
        Handler h = new Handler(thread.getLooper());
        CountDownLatch ready = new CountDownLatch(1);
        // The listener version: without one the car library kills the whole process when the car service restarts
        InvocationHandler calls = (proxy, method, args) -> {
            switch (method.getName()) {
                case "onLifecycleChanged":
                    if (args != null && args.length > 1 && Boolean.TRUE.equals(args[1])) {
                        ready.countDown();
                    }
                    return null;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return args != null && proxy == args[0];
                case "toString":
                    return "CarAccessCheck.lifecycle";
                default:
                    return null;
            }
        };
        Object car = null;
        try {
            Object listener = Proxy.newProxyInstance(lifecycle.getClassLoader(), new Class<?>[]{lifecycle}, calls);
            long start = SystemClock.elapsedRealtime();
            Outcome made = timed(h, () -> create.invoke(null, app, h, 0L, listener));
            if (made.timedOut || made.error != null || made.value == null) {
                String why = made.timedOut ? "no answer within " + seconds(TIMEOUT_MS)
                        : made.error != null ? describe(made.error) : "returned null";
                out.add(new Line(State.FAIL, "Car.createCar", why));
                summary.add(new Line(State.FAIL, app.getString(R.string.cac_v_car_fail), why));
                return;
            }
            car = made.value;
            boolean connected = await(ready);
            long ms = SystemClock.elapsedRealtime() - start;
            if (!connected) {
                out.add(new Line(State.FAIL, "Car.createCar", "car service did not get ready within " + seconds(TIMEOUT_MS)));
                summary.add(new Line(State.FAIL, app.getString(R.string.cac_v_car_fail), "car service not ready"));
                return;
            }
            out.add(new Line(State.OK, "Car.createCar", "car service ready (" + ms + " ms)"));

            properties(app, h, car, out, summary);
            settingZeekr(app, h, car, out, summary);
        } catch (Throwable t) {
            out.add(new Line(State.FAIL, "android.car", describe(t)));
            summary.add(new Line(State.FAIL, app.getString(R.string.cac_v_car_fail), describe(t)));
        } finally {
            Object c = car;
            if (c != null) {
                h.post(() -> {
                    try {
                        c.getClass().getMethod("disconnect").invoke(c);
                    } catch (Throwable ignored) {
                        // already gone
                    }
                });
            }
            thread.quitSafely();
        }
    }

    private static Object carManager(Handler h, Object car, String service) {
        Outcome o = timed(h, () -> car.getClass().getMethod("getCarManager", String.class).invoke(car, service));
        if (o.timedOut) {
            throw new IllegalStateException("getCarManager(" + service + ") no answer within " + seconds(TIMEOUT_MS));
        }
        if (o.error != null) {
            throw new IllegalStateException("getCarManager(" + service + ") " + describe(o.error));
        }
        return o.value;
    }

    /** 2. CarPropertyManager: the property list, then each standard value once. */
    private static void properties(Context app, Handler h, Object car, List<Line> out, List<Line> summary) {
        Object pm;
        try {
            pm = carManager(h, car, "property");
        } catch (Throwable t) {
            out.add(new Line(State.FAIL, "CarPropertyManager", t.getMessage()));
            summary.add(new Line(State.FAIL, app.getString(R.string.cac_v_props, 0, 0), t.getMessage()));
            return;
        }
        if (pm == null) {
            out.add(new Line(State.FAIL, "CarPropertyManager", "getCarManager(property) returned null"));
            summary.add(new Line(State.FAIL, app.getString(R.string.cac_v_props, 0, 0), "no property manager"));
            return;
        }
        Class<?> ids = null;
        try {
            ids = Class.forName("android.car.VehiclePropertyIds", true, car.getClass().getClassLoader());
        } catch (Throwable ignored) {
            // names stay as numbers
        }
        Class<?> names = ids;
        Outcome list = timed(h, () -> pm.getClass().getMethod("getPropertyList").invoke(pm));
        if (list.value instanceof List) {
            List<?> configs = (List<?>) list.value;
            StringBuilder sb = new StringBuilder();
            for (Object config : configs) {
                if (sb.length() > 1500) {
                    sb.append(", …");
                    break;
                }
                try {
                    int id = (Integer) config.getClass().getMethod("getPropertyId").invoke(config);
                    sb.append(sb.length() == 0 ? "" : ", ").append(propertyName(names, id));
                } catch (Throwable ignored) {
                    // skip
                }
            }
            out.add(new Line(configs.isEmpty() ? State.WARN : State.OK,
                    "getPropertyList: " + configs.size() + " properties this app may see", sb.toString()));
        } else {
            out.add(new Line(State.FAIL, "getPropertyList",
                    list.timedOut ? "no answer within " + seconds(TIMEOUT_MS) : describe(list.error)));
        }

        int readable = 0;
        int tried = 0;
        StringBuilder readableNames = new StringBuilder();
        for (String name : STANDARD_PROPERTIES) {
            Integer id = constant(ids, name);
            if (id == null) {
                continue;
            }
            tried++;
            Outcome config = timed(h, () -> pm.getClass().getMethod("getCarPropertyConfig", int.class).invoke(pm, id));
            if (config.timedOut) {
                out.add(new Line(State.FAIL, name, "no answer within " + seconds(TIMEOUT_MS)));
                break;
            }
            if (config.error != null) {
                out.add(new Line(State.FAIL, name, describe(config.error)));
                continue;
            }
            if (config.value == null) {
                out.add(new Line(State.WARN, name, "not listed (the car does not have it, or this app lacks its permission)"));
                continue;
            }
            int[] areas = areas(config.value);
            int area = areas.length > 0 ? areas[0] : 0;
            Outcome v = timed(h, () -> pm.getClass().getMethod("getProperty", int.class, int.class).invoke(pm, id, area));
            if (v.timedOut) {
                out.add(new Line(State.FAIL, name, "no answer within " + seconds(TIMEOUT_MS)));
                break;
            }
            if (v.error != null) {
                out.add(new Line(State.FAIL, name, describe(v.error)));
                continue;
            }
            String text = propertyValue(v.value);
            boolean ok = v.value != null;
            out.add(new Line(ok ? State.OK : State.WARN, name, "area " + hex(area) + " of " + areas.length + ": " + text));
            if (ok) {
                readable++;
                readableNames.append(readableNames.length() == 0 ? "" : ", ").append(name);
            }
        }
        summary.add(new Line(readable > 0 ? State.OK : State.FAIL,
                app.getString(R.string.cac_v_props, readable, tried), readableNames.toString()));
    }

    /** 3. setting_zeekr: Zeekr's own manager on android.car, and how many properties it lists. */
    private static void settingZeekr(Context app, Handler h, Object car, List<Line> out, List<Line> summary) {
        String label = "getCarManager(" + SettingZeekrWatch.SERVICE + ")";
        try {
            Object manager = carManager(h, car, SettingZeekrWatch.SERVICE);
            if (manager == null) {
                out.add(new Line(State.FAIL, label, "null"));
                summary.add(new Line(State.FAIL, app.getString(R.string.cac_v_setting_zeekr), "no manager"));
                return;
            }
            out.add(new Line(State.OK, label, manager.getClass().getName()));
            Field field = null;
            for (Class<?> c = manager.getClass(); c != null && field == null; c = c.getSuperclass()) {
                try {
                    field = c.getDeclaredField("mMgr");
                } catch (NoSuchFieldException ignored) {
                    // look further up
                }
            }
            if (field == null) {
                out.add(new Line(State.WARN, "mMgr", "no such field"));
                summary.add(new Line(State.WARN, app.getString(R.string.cac_v_setting_zeekr), "manager without mMgr"));
                return;
            }
            field.setAccessible(true);
            Object base = field.get(manager);
            if (base == null) {
                out.add(new Line(State.FAIL, "mMgr", "null"));
                summary.add(new Line(State.FAIL, app.getString(R.string.cac_v_setting_zeekr), "mMgr is null"));
                return;
            }
            Outcome list = timed(h, () -> base.getClass().getMethod("getPropertyList").invoke(base));
            if (list.value instanceof List) {
                int n = ((List<?>) list.value).size();
                out.add(new Line(n > 0 ? State.OK : State.WARN, "setting_zeekr getPropertyList", n + " properties"));
                summary.add(new Line(n > 0 ? State.OK : State.WARN, app.getString(R.string.cac_v_setting_zeekr),
                        n + " properties"));
            } else {
                String why = list.timedOut ? "no answer within " + seconds(TIMEOUT_MS) : describe(list.error);
                out.add(new Line(State.FAIL, "setting_zeekr getPropertyList", why));
                summary.add(new Line(State.FAIL, app.getString(R.string.cac_v_setting_zeekr), why));
            }
        } catch (Throwable t) {
            String why = t instanceof IllegalStateException ? t.getMessage() : describe(t);
            out.add(new Line(State.FAIL, label, why));
            summary.add(new Line(State.FAIL, app.getString(R.string.cac_v_setting_zeekr), why));
        }
    }

    private static String propertyName(Class<?> ids, int id) {
        if (ids != null) {
            try {
                Object s = ids.getMethod("toString", int.class).invoke(null, id);
                if (s != null) {
                    return s.toString();
                }
            } catch (Throwable ignored) {
                // number below
            }
        }
        return hex(id);
    }

    private static Integer constant(Class<?> ids, String name) {
        if (ids == null) {
            return null;
        }
        try {
            return ids.getField(name).getInt(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int[] areas(Object config) {
        try {
            Object a = config.getClass().getMethod("getAreaIds").invoke(config);
            return a instanceof int[] ? (int[]) a : new int[0];
        } catch (Throwable t) {
            return new int[0];
        }
    }

    private static String propertyValue(Object carPropertyValue) {
        if (carPropertyValue == null) {
            return "null";
        }
        String status = "";
        try {
            Object s = carPropertyValue.getClass().getMethod("getStatus").invoke(carPropertyValue);
            if (s instanceof Integer && (Integer) s != 0) {
                status = " (status " + s + (((Integer) s) == 1 ? " unavailable" : ((Integer) s) == 2 ? " error" : "") + ")";
            }
        } catch (Throwable ignored) {
            // no status
        }
        try {
            return value(carPropertyValue.getClass().getMethod("getValue").invoke(carPropertyValue)) + status;
        } catch (Throwable t) {
            return describe(t);
        }
    }

    // ================================================================= 4. Zeekr and ECARX packages

    private static void packages(Context app, List<Line> out, List<Line> summary) {
        out.add(new Line(State.SECTION, app.getString(R.string.cac_sec_packages), null));
        PackageManager pm = app.getPackageManager();
        List<PackageInfo> all;
        try {
            all = pm.getInstalledPackages(0);
        } catch (Throwable t) {
            out.add(new Line(State.FAIL, "installed packages", describe(t)));
            return;
        }
        List<String> found = new ArrayList<>();
        for (PackageInfo p : all) {
            String lower = p.packageName.toLowerCase(Locale.US);
            if ((lower.contains("ecarx") || lower.contains("zeekr")) && !p.packageName.equals(app.getPackageName())) {
                found.add(p.packageName);
            }
        }
        java.util.Collections.sort(found);
        out.add(new Line(found.isEmpty() ? State.WARN : State.INFO,
                "Zeekr / ECARX packages this app can see: " + found.size() + " of " + all.size() + " installed",
                String.join(", ", found)));
        boolean core = found.contains("com.zeekr.coreservice");
        out.add(new Line(core ? State.INFO : State.WARN, "com.zeekr.coreservice", core ? "installed" : "not visible"));

        int lines = 0;
        int requestable = 0;
        int protectedCount = 0;
        StringBuilder requestableNames = new StringBuilder();
        for (String name : found) {
            PackageInfo p;
            try {
                p = pm.getPackageInfo(name, PackageManager.GET_SERVICES | PackageManager.GET_PERMISSIONS);
            } catch (Throwable t) {
                continue;
            }
            // Services others may bind to: only for the core service and ECARX's own packages
            boolean look = name.equals("com.zeekr.coreservice") || name.toLowerCase(Locale.US).contains("ecarx");
            if (look && p.services != null) {
                for (ServiceInfo s : p.services) {
                    if (s.exported && lines < MAX_PACKAGE_LINES) {
                        lines++;
                        out.add(new Line(State.INFO, "service " + shortName(name, s.name),
                                s.permission == null ? "exported, no permission" : "needs " + s.permission + " ("
                                        + (granted(app, s.permission) ? "this app has it" : "this app does not have it") + ")"));
                    }
                }
            }
            if (p.permissions != null) {
                for (PermissionInfo perm : p.permissions) {
                    int base = perm.getProtection();
                    if (base == PermissionInfo.PROTECTION_NORMAL || base == PermissionInfo.PROTECTION_DANGEROUS) {
                        requestable++;
                        requestableNames.append(requestableNames.length() == 0 ? "" : ", ").append(perm.name);
                        if (lines < MAX_PACKAGE_LINES) {
                            lines++;
                            out.add(new Line(State.WARN, "permission " + perm.name, protection(perm)
                                    + " — any app may ask for it; defined by " + name + ", "
                                    + (granted(app, perm.name) ? "this app has it" : "this app does not have it")));
                        }
                    } else {
                        protectedCount++;
                    }
                }
            }
        }
        out.add(new Line(State.INFO, "permissions they define",
                requestable + " any app may ask for, " + protectedCount + " only for system or same-signer apps"));
        if (requestable > 0) {
            summary.add(new Line(State.WARN, app.getString(R.string.cac_v_requestable, requestable),
                    requestableNames.length() > 600 ? requestableNames.substring(0, 600) + "…" : requestableNames.toString()));
        }
    }

    private static String shortName(String pkg, String cls) {
        return pkg + "/" + (cls.startsWith(pkg) ? cls.substring(pkg.length()) : cls);
    }

    // ================================================================= car permissions

    private static void permissions(Context app, List<Line> out) {
        out.add(new Line(State.SECTION, app.getString(R.string.cac_sec_permissions), null));
        PackageManager pm = app.getPackageManager();
        List<String> names = new ArrayList<>(Arrays.asList(DECLARED_CAR_PERMISSIONS));
        names.addAll(Arrays.asList(OTHER_CAR_PERMISSIONS));
        for (String name : names) {
            String level;
            try {
                level = protection(pm.getPermissionInfo(name, 0));
            } catch (PackageManager.NameNotFoundException e) {
                out.add(new Line(State.INFO, shortPermission(name), "not defined on this head unit"));
                continue;
            } catch (Throwable t) {
                level = describe(t);
            }
            boolean has = granted(app, name);
            out.add(new Line(has ? State.OK : State.WARN, shortPermission(name),
                    level + ", " + (has ? "granted" : "not granted")));
        }
    }

    /** Car permissions the manifest declares that the user still has to allow (dangerous, not granted). */
    public static String[] permissionsToAsk(Context context) {
        PackageManager pm = context.getPackageManager();
        List<String> ask = new ArrayList<>();
        for (String name : DECLARED_CAR_PERMISSIONS) {
            try {
                PermissionInfo info = pm.getPermissionInfo(name, 0);
                if (info.getProtection() == PermissionInfo.PROTECTION_DANGEROUS && !granted(context, name)) {
                    ask.add(name);
                }
            } catch (Throwable ignored) {
                // not on this head unit
            }
        }
        return ask.toArray(new String[0]);
    }

    private static boolean granted(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private static String shortPermission(String name) {
        return name.startsWith("android.car.permission.") ? name.substring("android.car.permission.".length()) : name;
    }

    private static String protection(PermissionInfo info) {
        String base;
        switch (info.getProtection()) {
            case PermissionInfo.PROTECTION_NORMAL:
                base = "normal";
                break;
            case PermissionInfo.PROTECTION_DANGEROUS:
                base = "dangerous (asked at run time)";
                break;
            case PermissionInfo.PROTECTION_SIGNATURE:
                base = "signature";
                break;
            default:
                base = "level " + info.getProtection();
                break;
        }
        int flags = info.getProtectionFlags();
        if ((flags & PermissionInfo.PROTECTION_FLAG_PRIVILEGED) != 0) {
            base += "|privileged";
        }
        return base;
    }

    // ================================================================= helpers

    private interface Call {
        Object run() throws Throwable;
    }

    private static final class Outcome {
        volatile Object value;
        volatile Throwable error;
        volatile boolean timedOut;
        volatile long ms;
    }

    /**
     * Runs one call on the section's own thread and waits at most {@link #TIMEOUT_MS}. A call that
     * hangs keeps that thread, so a section stops at its first hang.
     */
    private static Outcome timed(Handler h, Call call) {
        Outcome o = new Outcome();
        CountDownLatch done = new CountDownLatch(1);
        long start = SystemClock.elapsedRealtime();
        h.post(() -> {
            try {
                o.value = call.run();
            } catch (Throwable t) {
                o.error = t;
            } finally {
                o.ms = SystemClock.elapsedRealtime() - start;
                done.countDown();
            }
        });
        o.timedOut = !await(done);
        return o;
    }

    private static boolean await(CountDownLatch latch) {
        try {
            return latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String seconds(long ms) {
        return (ms / 1000) + " s";
    }

    static String hex(int v) {
        return String.format(Locale.US, "0x%08X", v);
    }

    private static String value(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Integer) {
            int i = (Integer) v;
            return i >= 0 && i < 0x10000 || i < 0 && i > -0x10000 ? Integer.toString(i) : hex(i);
        }
        if (v instanceof Object[]) {
            return Arrays.deepToString((Object[]) v);
        }
        if (v instanceof int[]) {
            return Arrays.toString((int[]) v);
        }
        if (v instanceof float[]) {
            return Arrays.toString((float[]) v);
        }
        return v.toString();
    }

    /** Peels the reflection layers off, down to the real cause. */
    static String describe(Throwable e) {
        if (e == null) {
            return "?";
        }
        Throwable t = e;
        while ((t instanceof InvocationTargetException || t instanceof UndeclaredThrowableException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
