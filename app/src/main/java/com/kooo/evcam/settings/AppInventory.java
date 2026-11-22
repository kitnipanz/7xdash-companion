package com.kooo.evcam.settings;

import android.content.pm.PermissionInfo;

/**
 * How a declared permission is reported on the inventory screen: granted or not, and the
 * protection level the device assigned. A level the platform does not define stays unknown.
 */
public final class AppInventory {

    /** Optional shared libraries named in the manifest. Absent on a phone is normal. */
    public static final String[] DECLARED_LIBRARIES = {
            "android.car",
            "com.ecarx.xui.adaptapi"
    };

    /** {@code PackageManager.REQUESTED_PERMISSION_GRANTED}. The compile stub does not expose the name. */
    static final int REQUESTED_PERMISSION_GRANTED = 1 << 1;

    private AppInventory() {
    }

    public static boolean granted(int requestedFlags) {
        return (requestedFlags & REQUESTED_PERMISSION_GRANTED) != 0;
    }

    /** {@code base} is {@link PermissionInfo#getProtection()}, {@code flags} the protection flags. */
    public static String protection(int base, int flags) {
        String text = base == PermissionInfo.PROTECTION_NORMAL ? "normal"
                : base == PermissionInfo.PROTECTION_DANGEROUS ? "dangerous"
                : base == PermissionInfo.PROTECTION_SIGNATURE ? "signature"
                : "level " + base;
        if ((flags & PermissionInfo.PROTECTION_FLAG_PRIVILEGED) != 0) {
            text += "|privileged";
        }
        if ((flags & PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0) {
            text += "|development";
        }
        if ((flags & PermissionInfo.PROTECTION_FLAG_APPOP) != 0) {
            text += "|appop";
        }
        if ((flags & PermissionInfo.PROTECTION_FLAG_PRE23) != 0) {
            text += "|pre23";
        }
        if ((flags & PermissionInfo.PROTECTION_FLAG_INSTALLER) != 0) {
            text += "|installer";
        }
        if ((flags & 0x8000) != 0) {
            text += "|vendorPrivileged";
        }
        return text;
    }
}
