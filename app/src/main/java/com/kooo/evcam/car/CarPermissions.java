package com.kooo.evcam.car;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code android.car.permission.*} permissions the manifest declares, sorted by what the app
 * can do about them on this head unit.
 *
 * <p>Normal ones are granted at install. Dangerous ones the driver allows in the system dialog,
 * so those are the ones to ask for. Signature and privileged ones only a platform-signed or
 * system app gets; nothing the app does can change that. Protection levels are read from the
 * head unit, not assumed, because a car ROM can set its own, and a phone or a head unit
 * without the Car service has none of these at all.</p>
 */
public final class CarPermissions {
    private static final String PREFIX = "android.car.permission.";

    private CarPermissions() {
    }

    /** Declared car permissions the head unit knows as runtime (dangerous) ones. */
    public static List<String> runtime(Context context) {
        List<String> out = new ArrayList<>();
        PackageManager pm = context.getPackageManager();
        for (String permission : declared(context)) {
            try {
                PermissionInfo info = pm.getPermissionInfo(permission, 0);
                if (info.getProtection() == PermissionInfo.PROTECTION_DANGEROUS) {
                    out.add(permission);
                }
            } catch (PackageManager.NameNotFoundException e) {
                // Not on this head unit
            }
        }
        return out;
    }

    /** Runtime car permissions not granted yet: the ones to ask for. */
    public static String[] missing(Context context) {
        List<String> out = new ArrayList<>();
        for (String permission : runtime(context)) {
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                out.add(permission);
            }
        }
        return out.toArray(new String[0]);
    }

    private static List<String> declared(Context context) {
        List<String> out = new ArrayList<>();
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions != null) {
                for (String permission : info.requestedPermissions) {
                    if (permission.startsWith(PREFIX)) {
                        out.add(permission);
                    }
                }
            }
        } catch (PackageManager.NameNotFoundException ignored) {
            // Our own package is always there
        }
        return out;
    }

    /** Names that mark a car or car-vendor permission (Android Automotive, ECARX, Zeekr, Geely, Flyme). */
    private static final String[] VENDOR_MARKS = {"android.car.", "ecarx", "zeekr", "geely", "flyme", "meizu"};

    /**
     * For Diagnostics: every car and car-vendor permission this head unit defines, its protection
     * level, whether 7xDash declares it and whether it holds it. Installed straight on the head unit
     * (not inside a container app) the app only has what it declares and is granted, so this shows
     * which ones it can still ask for (normal, dangerous) and which only a system app gets.
     */
    public static void appendTo(StringBuilder sb, Context context) {
        sb.append("========== Car permissions ==========\n");
        PackageManager pm = context.getPackageManager();
        List<String> declared = declaredAll(context);
        java.util.TreeMap<String, PermissionInfo> found = new java.util.TreeMap<>();
        try {
            for (android.content.pm.PackageInfo info : pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
                if (info.permissions == null) {
                    continue;
                }
                for (PermissionInfo permission : info.permissions) {
                    if (isCar(permission.name)) {
                        found.put(permission.name, permission);
                    }
                }
            }
        } catch (RuntimeException e) {
            sb.append("package list refused: ").append(e.getMessage()).append('\n');
        }
        int[] counts = new int[4];
        for (PermissionInfo permission : found.values()) {
            String level = level(permission);
            boolean mine = declared.contains(permission.name);
            boolean held = context.checkSelfPermission(permission.name) == PackageManager.PERMISSION_GRANTED;
            if ("normal".equals(level)) counts[0]++;
            else if ("dangerous".equals(level)) counts[1]++;
            else counts[2]++;
            if (held) counts[3]++;
            sb.append(held ? "[held] " : mine ? "[asked] " : "[-] ")
                    .append(permission.name).append(" : ").append(level)
                    .append(" (").append(permission.packageName).append(")\n");
        }
        sb.append("total ").append(found.size()).append(": normal ").append(counts[0])
                .append(", dangerous ").append(counts[1]).append(", system only ").append(counts[2])
                .append(", held ").append(counts[3]).append("\n\n");
    }

    private static boolean isCar(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        for (String mark : VENDOR_MARKS) {
            if (lower.contains(mark)) {
                return true;
            }
        }
        return false;
    }

    private static String level(PermissionInfo info) {
        int base = info.getProtection();
        String text = base == PermissionInfo.PROTECTION_NORMAL ? "normal"
                : base == PermissionInfo.PROTECTION_DANGEROUS ? "dangerous"
                : base == PermissionInfo.PROTECTION_SIGNATURE ? "signature"
                : "level " + base;
        int flags = info.getProtectionFlags();
        if ((flags & PermissionInfo.PROTECTION_FLAG_PRIVILEGED) != 0) text += "|privileged";
        if ((flags & PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0) text += "|development";
        if ((flags & PermissionInfo.PROTECTION_FLAG_APPOP) != 0) text += "|appop";
        if ((flags & PermissionInfo.PROTECTION_FLAG_PRE23) != 0) text += "|pre23";
        if ((flags & PermissionInfo.PROTECTION_FLAG_INSTALLER) != 0) text += "|installer";
        if ((flags & 0x8000) != 0) text += "|vendorPrivileged"; // PROTECTION_FLAG_VENDOR_PRIVILEGED (hidden)
        return text;
    }

    private static List<String> declaredAll(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), PackageManager.GET_PERMISSIONS);
            return info.requestedPermissions == null ? new ArrayList<>()
                    : java.util.Arrays.asList(info.requestedPermissions);
        } catch (PackageManager.NameNotFoundException e) {
            return new ArrayList<>();
        }
    }
}
