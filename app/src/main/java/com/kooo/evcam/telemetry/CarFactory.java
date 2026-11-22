package com.kooo.evcam.telemetry;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * Builds the ECARX car object ({@link EcarxSource#CAR_CLASS}).
 *
 * <p>Most head units have {@code Car.create(Context)} and hand back a ready car. Some builds do not
 * (the 7X overseas userdebug build {@code zeekr_dhu_sa8295_ovs}, 2026-07-10, has the class but no
 * {@code create(Context)}). For those, every public static factory and public constructor on the
 * class is tried in turn, with the arguments we know how to fill: the context, a handler / looper /
 * executor on our own thread, a timeout for a {@code long}, and a do-nothing listener (that records
 * what it is told) for any interface. A car that is not ready at once gets {@code connect()} and a
 * short wait for its function or sensor manager.</p>
 *
 * <p>All reflection; the result says how the car was built, or what every try did, so one
 * diagnostics export shows the right way in.</p>
 */
final class CarFactory {

    /** Timeout given to a factory's {@code long} parameter (Android's {@code Car.createCar} takes one). */
    static final long FACTORY_TIMEOUT_MS = 5_000L;
    /** How long a car that is not ready at once may take to connect. */
    static final long READY_WAIT_MS = 5_000L;
    private static final long READY_POLL_MS = 100L;
    /** Factory names worth trying even when the return type says nothing (returns Object). */
    private static final List<String> FACTORY_NAMES = Arrays.asList(
            "create", "createCar", "getInstance", "getCar", "newInstance", "get", "getDefault", "obtain", "instance");

    /** What the factory needs from the outside: the context and a thread for the car's callbacks. */
    interface Env {
        Object context();

        Handler handler();

        Looper looper();

        Executor executor();
    }

    /** How building went. */
    static final class Result {
        /** The car, or null. */
        final Object car;
        /** How it was built ("Car.create(Context)"), or null. */
        final String how;
        /** What each try did (one line each); empty when the first try worked. */
        final List<String> tries;
        /** There was at least one factory or constructor we could call. */
        final boolean anyCandidate;
        /** The car's manager was there at once, or after how long ("ready after 820 ms", "not ready after 5000 ms"). */
        final String ready;

        Result(Object car, String how, List<String> tries, boolean anyCandidate, String ready) {
            this.car = car;
            this.how = how;
            this.tries = tries;
            this.anyCandidate = anyCandidate;
            this.ready = ready;
        }

        String triesLine() {
            return String.join("; ", tries);
        }
    }

    private CarFactory() {
    }

    /** Builds a car on {@code carClass}. Blocking (up to a few seconds); never on the main thread. */
    static Result create(Class<?> carClass, Env env) {
        List<Executable> candidates = candidates(carClass);
        List<String> tries = new ArrayList<>();
        boolean anyCallable = false;
        for (Executable e : candidates) {
            List<Object> listened = Collections.synchronizedList(new ArrayList<>());
            Object[] args;
            try {
                args = arguments(e, env, listened);
            } catch (IllegalArgumentException cannot) {
                tries.add(signature(e) + ": " + cannot.getMessage());
                continue;
            }
            anyCallable = true;
            Object car;
            try {
                car = invoke(e, args);
            } catch (Throwable t) {
                tries.add(signature(e) + " threw " + describe(t));
                continue;
            }
            if (car == null) {
                tries.add(signature(e) + " returned null");
                continue;
            }
            String ready = awaitReady(car, listened);
            if (!listened.isEmpty()) {
                ready += "; told " + listened;
            }
            return new Result(car, signature(e), tries, true, ready);
        }
        if (candidates.isEmpty()) {
            tries.add("no static factory or public constructor");
        }
        return new Result(null, null, tries, anyCallable, null);
    }

    /**
     * Everything worth calling, best first: {@code create(Context)} (the usual way), then static
     * methods named like a factory or returning a car, then public constructors; fewer parameters first.
     */
    static List<Executable> candidates(Class<?> carClass) {
        List<Method> statics = new ArrayList<>();
        for (Method m : carClass.getMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            Class<?> r = m.getReturnType();
            if (r.isPrimitive() || r == String.class) {
                continue;
            }
            boolean named = FACTORY_NAMES.contains(m.getName());
            boolean returnsCar = carClass.isAssignableFrom(r) || (r != Object.class && r.isAssignableFrom(carClass));
            if (named || returnsCar) {
                statics.add(m);
            }
        }
        statics.sort((a, b) -> {
            int ra = rank(a), rb = rank(b);
            if (ra != rb) {
                return Integer.compare(ra, rb);
            }
            if (a.getParameterCount() != b.getParameterCount()) {
                return Integer.compare(a.getParameterCount(), b.getParameterCount());
            }
            return signature(a).compareTo(signature(b));
        });
        List<Executable> out = new ArrayList<>(statics);
        if (!Modifier.isAbstract(carClass.getModifiers()) && !carClass.isInterface()) {
            List<Constructor<?>> ctors = new ArrayList<>(Arrays.asList(carClass.getConstructors()));
            ctors.sort((a, b) -> Integer.compare(a.getParameterCount(), b.getParameterCount()));
            out.addAll(ctors);
        }
        return out;
    }

    /** {@code create} first, then the other factory names in list order, then the rest. */
    private static int rank(Method m) {
        int i = FACTORY_NAMES.indexOf(m.getName());
        return i < 0 ? FACTORY_NAMES.size() : i;
    }

    /** Fills the parameters, or throws IllegalArgumentException naming the one we cannot fill. */
    private static Object[] arguments(Executable e, Env env, List<Object> listened) {
        Class<?>[] types = e.getParameterTypes();
        Object[] args = new Object[types.length];
        Object context = env.context();
        for (int i = 0; i < types.length; i++) {
            Class<?> p = types[i];
            if (context != null && p.isInstance(context)) {
                args[i] = context;
            } else if (p == Handler.class) {
                args[i] = env.handler();
            } else if (p == Looper.class) {
                args[i] = env.looper();
            } else if (p == Executor.class) {
                args[i] = env.executor();
            } else if (p == long.class) {
                args[i] = FACTORY_TIMEOUT_MS;
            } else if (p.isInterface()) {
                args[i] = listener(p, listened);
            } else {
                throw new IllegalArgumentException("cannot fill " + p.getName());
            }
            if (args[i] == null && !p.isPrimitive()) {
                throw new IllegalArgumentException("no " + p.getSimpleName() + " here");
            }
        }
        return args;
    }

    /** A listener that does nothing but write down what it was told. */
    private static Object listener(Class<?> type, List<Object> listened) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "equals":
                    return args != null && args.length == 1 && proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return type.getSimpleName() + " listener";
                default:
                    break;
            }
            StringBuilder line = new StringBuilder(method.getName()).append('(');
            if (args != null) {
                for (int i = 0; i < args.length; i++) {
                    if (i > 0) {
                        line.append(", ");
                    }
                    line.append(shortValue(args[i]));
                }
            }
            listened.add(line.append(')').toString());
            return defaultValue(method.getReturnType());
        });
    }

    private static String shortValue(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Number || v instanceof Boolean || v instanceof CharSequence) {
            return String.valueOf(v);
        }
        return v.getClass().getSimpleName();
    }

    static Object defaultValue(Class<?> r) {
        if (!r.isPrimitive() || r == void.class) {
            return null;
        }
        if (r == boolean.class) {
            return false;
        }
        if (r == char.class) {
            return '\0';
        }
        if (r == long.class) {
            return 0L;
        }
        if (r == float.class) {
            return 0f;
        }
        if (r == double.class) {
            return 0d;
        }
        if (r == byte.class) {
            return (byte) 0;
        }
        if (r == short.class) {
            return (short) 0;
        }
        return 0;
    }

    private static Object invoke(Executable e, Object[] args) throws Exception {
        if (e instanceof Method) {
            return ((Method) e).invoke(null, args);
        }
        return ((Constructor<?>) e).newInstance(args);
    }

    /**
     * Ready = the function or sensor manager comes back. Not ready at once: {@code connect()} if it has
     * one, then wait up to {@link #READY_WAIT_MS}. Either way the car is returned; the caller reads it.
     */
    static String awaitReady(Object car, List<Object> listened) {
        if (ready(car)) {
            return "ready at once";
        }
        Method connect = EcarxSource.findMethod(car, "connect");
        String connected = "";
        if (connect != null) {
            try {
                connect.invoke(car);
                connected = "connect() called; ";
            } catch (Throwable t) {
                connected = "connect() threw " + describe(t) + "; ";
            }
        }
        long start = SystemClock.elapsedRealtime();
        long waited = 0;
        while (waited < READY_WAIT_MS) {
            if (ready(car)) {
                return connected + "ready after " + waited + " ms";
            }
            try {
                Thread.sleep(READY_POLL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            long now = SystemClock.elapsedRealtime();
            // unit tests: the android.jar stub clock reads 0, count our own sleeps instead
            waited = now > start ? now - start : waited + READY_POLL_MS;
        }
        return connected + "not ready after " + waited + " ms (" + listened.size() + " callbacks)";
    }

    private static boolean ready(Object car) {
        return managerOf(car, "getICarFunction") != null || managerOf(car, "getSensorManager") != null;
    }

    private static Object managerOf(Object car, String name) {
        Method m = EcarxSource.findMethod(car, name);
        if (m == null) {
            return null;
        }
        try {
            return m.invoke(car);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The car class as the head unit has it: super class, interfaces, public constructors and
     * methods (static ones marked). Goes to the black box when {@code create(Context)} is missing.
     */
    static String describeApi(Class<?> carClass) {
        StringBuilder b = new StringBuilder(carClass.getName());
        b.append(Modifier.isAbstract(carClass.getModifiers()) ? " (abstract)" : "");
        Class<?> sup = carClass.getSuperclass();
        if (sup != null && sup != Object.class) {
            b.append(" extends ").append(sup.getName());
        }
        if (carClass.getInterfaces().length > 0) {
            b.append(" implements");
            for (Class<?> i : carClass.getInterfaces()) {
                b.append(' ').append(i.getName());
            }
        }
        List<String> lines = new ArrayList<>();
        for (Constructor<?> c : carClass.getConstructors()) {
            lines.add("new " + signature(c));
        }
        List<String> methods = new ArrayList<>();
        for (Method m : carClass.getMethods()) {
            if (m.getDeclaringClass() == Object.class) {
                continue;
            }
            methods.add((Modifier.isStatic(m.getModifiers()) ? "static " : "")
                    + m.getReturnType().getSimpleName() + ' ' + signature(m));
        }
        Collections.sort(methods);
        lines.addAll(methods);
        for (String line : lines) {
            b.append("\n  ").append(line);
        }
        if (lines.isEmpty()) {
            b.append("\n  (no public constructors or methods)");
        }
        return b.toString();
    }

    /** {@code create(Context, Handler)}. */
    static String signature(Executable e) {
        StringBuilder b = new StringBuilder(e instanceof Constructor ? e.getDeclaringClass().getSimpleName() : e.getName());
        b.append('(');
        Class<?>[] types = e.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) {
                b.append(", ");
            }
            b.append(types[i].getSimpleName());
        }
        return b.append(')').toString();
    }

    private static String describe(Throwable e) {
        Throwable t = e;
        while (t instanceof InvocationTargetException && t.getCause() != null) {
            t = t.getCause();
        }
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    // ================================================================= the real environment

    private static final Object THREAD_LOCK = new Object();
    private static HandlerThread callbackThread;
    private static Handler callbackHandler;

    /** The app's environment: the context given, callbacks on one shared thread of ours. */
    static Env env(Object context) {
        return new Env() {
            @Override
            public Object context() {
                return context;
            }

            @Override
            public Handler handler() {
                return callbackHandler();
            }

            @Override
            public Looper looper() {
                return callbackHandler().getLooper();
            }

            @Override
            public Executor executor() {
                Handler h = callbackHandler();
                return r -> h.post(r);
            }
        };
    }

    private static Handler callbackHandler() {
        synchronized (THREAD_LOCK) {
            if (callbackHandler == null) {
                callbackThread = new HandlerThread("ecarx-car-callbacks");
                callbackThread.start();
                callbackHandler = new Handler(callbackThread.getLooper());
            }
            return callbackHandler;
        }
    }

    /** For log lines. */
    static String summary(Result r) {
        if (r.car != null) {
            return String.format(Locale.US, "via Car.%s, %s%s", r.how, r.ready,
                    r.tries.isEmpty() ? "" : " (before that: " + r.triesLine() + ")");
        }
        return "no way to build a Car: " + r.triesLine();
    }
}
