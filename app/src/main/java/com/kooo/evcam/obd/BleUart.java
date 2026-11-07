package com.kooo.evcam.obd;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Finds the serial pipe on a Bluetooth LE adapter. There is no standard for it: each maker has its
 * own service, with one characteristic to write and one that notifies (sometimes the same one).
 * Known services come first; otherwise any service that is not a standard one and has both will do.
 */
final class BleUart {

    /** One characteristic as discovered; plain data so the choice can be tested off the car. */
    static final class Characteristic {
        final UUID service;
        final UUID uuid;
        final boolean notifies;
        final boolean writes;

        Characteristic(UUID service, UUID uuid, boolean notifies, boolean writes) {
            this.service = service;
            this.uuid = uuid;
            this.notifies = notifies;
            this.writes = writes;
        }
    }

    /** The pair to use. May be the same characteristic twice. */
    static final class Choice {
        final UUID service;
        final UUID notify;
        final UUID write;

        Choice(UUID service, UUID notify, UUID write) {
            this.service = service;
            this.notify = notify;
            this.write = write;
        }
    }

    /** Most used on cheap ELM327 BLE adapters, in order of trust. */
    private static final List<UUID> KNOWN = Arrays.asList(
            UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2"),  // Vgate iCar Pro BLE
            shortUuid(0xFFF0),
            shortUuid(0xFFE0),
            shortUuid(0x18F0));

    private static final String[] NAMES = {
            "OBD", "ELM", "VLINK", "V-LINK", "VGATE", "ICAR", "KONNWEI", "VEEPEAK", "LELINK", "CARISTA", "FIXD"
    };

    private BleUart() {
    }

    /** Null when nothing on the device looks like a serial pipe. */
    static Choice pick(List<Characteristic> found) {
        for (UUID service : KNOWN) {
            Choice choice = pipeIn(found, service);
            if (choice != null) {
                return choice;
            }
        }
        List<UUID> others = new ArrayList<>();
        for (Characteristic each : found) {
            if (!others.contains(each.service) && !isStandard(each.service)) {
                others.add(each.service);
            }
        }
        for (UUID service : others) {
            Choice choice = pipeIn(found, service);
            if (choice != null) {
                return choice;
            }
        }
        return null;
    }

    private static Choice pipeIn(List<Characteristic> found, UUID service) {
        Characteristic notify = null;
        Characteristic write = null;
        for (Characteristic each : found) {
            if (service.equals(each.service) && each.notifies && notify == null) {
                notify = each;
            }
        }
        if (notify == null) {
            return null;
        }
        // A separate write characteristic beats one that also notifies
        for (Characteristic each : found) {
            if (service.equals(each.service) && each.writes
                    && (write == null || write.uuid.equals(notify.uuid))) {
                write = each;
            }
        }
        return write == null ? null : new Choice(service, notify.uuid, write.uuid);
    }

    /** Bluetooth SIG services such as battery or device info, 0x1800 to 0x18FF. */
    private static boolean isStandard(UUID uuid) {
        long top = uuid.getMostSignificantBits();
        int shortId = (int) (top >>> 32);
        return uuid.getLeastSignificantBits() == 0x800000805F9B34FBL
                && (top & 0xFFFFFFFFL) == 0x1000L
                && shortId >= 0x1800 && shortId <= 0x18FF;
    }

    /** Is this probably an OBD adapter? Sorts the scan list; anything named can still be chosen. */
    static boolean looksLikeObd(String name) {
        if (name == null) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        for (String each : NAMES) {
            if (upper.contains(each)) {
                return true;
            }
        }
        return false;
    }

    private static UUID shortUuid(int id) {
        return new UUID(((long) id << 32) | 0x1000L, 0x800000805F9B34FBL);
    }
}
