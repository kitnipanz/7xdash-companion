package com.kooo.evcam.telemetry;

import com.kooo.evcam.AppLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import dalvik.system.InMemoryDexClassLoader;

/**
 * Loads {@code com.ecarx.xui.adaptapi} when a Zeekr Store install does not put it on the app
 * class path.
 *
 * <p>The client jar already sits on the head unit ({@code ecarx.adaptapi.impl.jar} on the boot
 * class path, or under a framework directory). A jar fetched from the internet is a different
 * build and does not match this car's service, so this never downloads one. The app class loader
 * is tried first ({@code uses-library}); if the class is missing, the dex inside the head unit's
 * own jar is loaded in memory.</p>
 */
public final class EcarxLoader {

    private static final String TAG = "EcarxLoader";
    /** One dex file larger than this is skipped. The adaptapi client is far smaller. */
    static final int MAX_DEX_BYTES = 48 * 1024 * 1024;
    private static final String[] FRAMEWORK_DIRS = {
            "/system/framework",
            "/system/system_ext/framework",
            "/product/framework",
            "/vendor/framework"
    };

    private static volatile ClassLoader device;
    private static volatile boolean searched;
    private static volatile String note = "not searched";

    private EcarxLoader() {
    }

    private static volatile boolean exemptTried;
    private static volatile String exemption = "not tried";

    /** Whether {@code Lcom/ecarx/} was exempted from the hidden API block, for the Car API dump. */
    public static String exemption() {
        return exemption;
    }

    /**
     * The 7X overseas build (zeekr_dhu_sa8295_ovs) has the adaptapi jar on the boot class path
     * with every member hidden: {@code Car} loads, but {@code getMethods()} comes back empty for
     * an app targeting a recent SDK. Exempts only {@code Lcom/ecarx/}, once per process.
     */
    static void exemptHiddenApi() {
        if (exemptTried) {
            return;
        }
        synchronized (EcarxLoader.class) {
            if (exemptTried) {
                return;
            }
            exemptTried = true;
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
                exemption = "not needed (SDK " + android.os.Build.VERSION.SDK_INT + ")";
                return;
            }
            try {
                boolean ok = org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Lcom/ecarx/");
                exemption = ok ? "Lcom/ecarx/ exempt" : "exemption refused";
            } catch (Throwable t) {
                exemption = "exemption failed: " + t;
            }
            AppLog.i(TAG, "hidden API: " + exemption);
            com.kooo.evcam.blackbox.BlackBox.note("ECARX hidden API: " + exemption);
        }
    }

    /** What the last search found, for the car-link status line and the ambient-light lab. */
    public static String note() {
        return note;
    }

    /**
     * {@code name} from the app class loader, or from the head unit's ECARX jar when the store
     * install cannot see it.
     */
    public static Class<?> load(String name, boolean initialize) throws ClassNotFoundException {
        exemptHiddenApi();
        ClassLoader app = EcarxLoader.class.getClassLoader();
        try {
            return Class.forName(name, initialize, app);
        } catch (ClassNotFoundException missing) {
            ClassLoader extra = deviceLoader();
            if (extra == null) {
                throw new ClassNotFoundException(name + " — " + note, missing);
            }
            try {
                return Class.forName(name, initialize, extra);
            } catch (ClassNotFoundException still) {
                throw new ClassNotFoundException(name + " — " + note, still);
            }
        }
    }

    private static ClassLoader deviceLoader() {
        if (searched) {
            return device;
        }
        synchronized (EcarxLoader.class) {
            if (searched) {
                return device;
            }
            List<String> paths = candidatePaths(
                    System.getenv("BOOTCLASSPATH"),
                    System.getenv("SYSTEMSERVERCLASSPATH"),
                    frameworkJars());
            List<ByteBuffer> buffers = new ArrayList<>();
            List<String> opened = new ArrayList<>();
            for (String path : paths) {
                File file = new File(path);
                if (!file.isFile() || !file.canRead()) {
                    continue;
                }
                try {
                    byte[][] dexes = dexBytes(file);
                    if (dexes.length == 0) {
                        continue;
                    }
                    for (byte[] dex : dexes) {
                        ByteBuffer buf = ByteBuffer.allocateDirect(dex.length);
                        buf.put(dex);
                        buf.flip();
                        buffers.add(buf);
                    }
                    opened.add(file.getName());
                } catch (IOException e) {
                    AppLog.w(TAG, "could not read " + path + ": " + e.getMessage());
                }
            }
            searched = true;
            if (buffers.isEmpty()) {
                note = paths.isEmpty()
                        ? "no ecarx jar on this head unit"
                        : "ecarx jar not readable (" + paths.size() + " paths)";
                AppLog.w(TAG, note);
                return null;
            }
            try {
                device = new InMemoryDexClassLoader(
                        buffers.toArray(new ByteBuffer[0]), EcarxLoader.class.getClassLoader());
                note = "loaded " + String.join(", ", opened);
                AppLog.i(TAG, note);
            } catch (Throwable t) {
                note = "ecarx dex refused: " + t.getMessage();
                AppLog.w(TAG, note);
                device = null;
            }
            return device;
        }
    }

    /** Jar paths from the two class-path variables plus framework directories. */
    static List<String> candidatePaths(String boot, String system, List<String> framework) {
        Set<String> out = new LinkedHashSet<>();
        collect(out, boot);
        collect(out, system);
        if (framework != null) {
            out.addAll(framework);
        }
        return new ArrayList<>(out);
    }

    /** Names under {@code dir} that look like the ECARX client. */
    static List<String> jarsIn(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return Collections.emptyList();
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (File file : files) {
            if (file.isFile() && isEcarxJar(file.getName())) {
                out.add(file.getAbsolutePath());
            }
        }
        return out;
    }

    static boolean isEcarxJar(String path) {
        if (path == null) {
            return false;
        }
        String name = path.toLowerCase(Locale.US);
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        return name.endsWith(".jar") && (name.contains("ecarx") || name.contains("adaptapi"));
    }

    /** {@code classes.dex} then {@code classes2.dex}, {@code classes10.dex} in numeric order. */
    static int dexIndex(String name) {
        if ("classes.dex".equals(name)) {
            return 1;
        }
        if (name != null && name.startsWith("classes") && name.endsWith(".dex")) {
            try {
                return Integer.parseInt(name.substring("classes".length(), name.length() - ".dex".length()));
            } catch (NumberFormatException ignored) {
                return Integer.MAX_VALUE;
            }
        }
        return Integer.MAX_VALUE;
    }

    /** Dex images inside {@code file}: the file itself when it is a dex, else {@code classes*.dex}. */
    static byte[][] dexBytes(File file) throws IOException {
        if (isRawDex(file)) {
            return new byte[][]{readFile(file)};
        }
        try (ZipFile zip = new ZipFile(file)) {
            List<String> names = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("classes") && name.endsWith(".dex") && !name.contains("/")) {
                    names.add(name);
                }
            }
            names.sort((a, b) -> Integer.compare(dexIndex(a), dexIndex(b)));
            List<byte[]> out = new ArrayList<>();
            for (String name : names) {
                ZipEntry entry = zip.getEntry(name);
                if (entry.getSize() > MAX_DEX_BYTES) {
                    continue;
                }
                out.add(readStream(zip.getInputStream(entry), MAX_DEX_BYTES));
            }
            return out.toArray(new byte[0][]);
        } catch (ZipException e) {
            return new byte[0][];
        }
    }

    private static List<String> frameworkJars() {
        List<String> out = new ArrayList<>();
        for (String dir : FRAMEWORK_DIRS) {
            out.addAll(jarsIn(new File(dir)));
        }
        return out;
    }

    private static void collect(Set<String> out, String classpath) {
        if (classpath == null || classpath.isEmpty()) {
            return;
        }
        for (String jar : classpath.split(":")) {
            if (isEcarxJar(jar)) {
                out.add(jar);
            }
        }
    }

    private static boolean isRawDex(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] magic = new byte[4];
            int n = in.read(magic);
            return n == 4 && magic[0] == 'd' && magic[1] == 'e' && magic[2] == 'x' && magic[3] == '\n';
        }
    }

    private static byte[] readFile(File file) throws IOException {
        if (file.length() > MAX_DEX_BYTES) {
            throw new IOException("dex larger than " + MAX_DEX_BYTES);
        }
        try (InputStream in = new FileInputStream(file)) {
            return readStream(in, MAX_DEX_BYTES);
        }
    }

    private static byte[] readStream(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) >= 0) {
            total += n;
            if (total > max) {
                throw new IOException("dex larger than " + max);
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
