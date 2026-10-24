package com.kooo.evcam.light;

import java.util.Locale;
import java.util.UUID;

/**
 * Bytes for cheap Bluetooth LE RGB lights (USB strips, lamps).
 *
 * <p>No pairing, no reply: the app writes a short packet to one characteristic. The two
 * families below cover most of the cheap ones. Which one a light speaks is read from its
 * services after connecting, not from its name, since clones rename freely.</p>
 *
 * <ul>
 *   <li>{@link Family#ELK}: ELK-BLEDOM, MELK, duoCo Strip / Lotus Lantern apps.
 *       Service {@code FFF0}, write {@code FFF3}, packets {@code 7E .. EF}.</li>
 *   <li>{@link Family#TRIONES}: Triones, LEDBLE, Happy Lighting app.
 *       Service {@code FFD5}, write {@code FFD9}. No brightness command, so brightness
 *       scales the colour.</li>
 * </ul>
 */
public final class LightProtocol {

    public enum Family {
        ELK(uuid16(0xFFF0), uuid16(0xFFF3)),
        TRIONES(uuid16(0xFFD5), uuid16(0xFFD9));

        public final UUID service;
        public final UUID write;

        Family(UUID service, UUID write) {
            this.service = service;
            this.write = write;
        }
    }

    /** Advertised names these lights ship with. Only orders the scan list. */
    private static final String[] NAME_PREFIXES = {
            "ELK", "MELK", "BLEDOM", "DUOCO", "LEDBLE", "TRIONES", "QHM", "LEDNET", "LED-", "LED_"
    };

    private LightProtocol() {
    }

    static UUID uuid16(int value) {
        return UUID.fromString(String.format(Locale.US, "0000%04x-0000-1000-8000-00805f9b34fb", value));
    }

    /** The family whose write characteristic this light has, or null. */
    public static Family detect(Iterable<UUID> characteristics) {
        if (characteristics == null) {
            return null;
        }
        for (UUID uuid : characteristics) {
            for (Family family : Family.values()) {
                if (family.write.equals(uuid)) {
                    return family;
                }
            }
        }
        return null;
    }

    public static Family parseFamily(String name) {
        if (name == null) {
            return null;
        }
        for (Family family : Family.values()) {
            if (family.name().equals(name)) {
                return family;
            }
        }
        return null;
    }

    /** Looks like one of these lights by name or advertised service. */
    public static boolean looksLikeLight(String name, Iterable<UUID> services) {
        if (services != null) {
            for (UUID uuid : services) {
                for (Family family : Family.values()) {
                    if (family.service.equals(uuid)) {
                        return true;
                    }
                }
            }
        }
        if (name == null) {
            return false;
        }
        String upper = name.trim().toUpperCase(Locale.US);
        for (String prefix : NAME_PREFIXES) {
            if (upper.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public static byte[] power(Family family, boolean on) {
        if (family == Family.TRIONES) {
            return bytes(0xCC, on ? 0x23 : 0x24, 0x33);
        }
        return on
                ? bytes(0x7E, 0x00, 0x04, 0xF0, 0x00, 0x01, 0xFF, 0x00, 0xEF)
                : bytes(0x7E, 0x00, 0x04, 0x00, 0x00, 0x00, 0xFF, 0x00, 0xEF);
    }

    /**
     * Colour at a brightness.
     *
     * @param rgb        {@code 0xRRGGBB}
     * @param brightness 0–100. ELK has its own command for it ({@link #brightness}), so the colour
     *                   goes out at full strength; TRIONES has none, so the colour is scaled.
     */
    public static byte[] color(Family family, int rgb, int brightness) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        if (family == Family.TRIONES) {
            int level = clamp(brightness);
            return bytes(0x56, r * level / 100, g * level / 100, b * level / 100, 0x00, 0xF0, 0xAA);
        }
        return bytes(0x7E, 0x00, 0x05, 0x03, r, g, b, 0x00, 0xEF);
    }

    /** Brightness 0–100, or null when the family only has it through {@link #color}. */
    public static byte[] brightness(Family family, int brightness) {
        if (family == Family.TRIONES) {
            return null;
        }
        return bytes(0x7E, 0x00, 0x01, clamp(brightness), 0x00, 0x00, 0x00, 0x00, 0xEF);
    }

    /** {@code 0xRRGGBB} from hue 0–359 at full saturation and value. */
    public static int hue(int degrees) {
        int h = ((degrees % 360) + 360) % 360;
        int x = 255 * (60 - Math.abs(h % 120 - 60)) / 60;
        int r;
        int g;
        int b;
        if (h < 60) {
            r = 255; g = x; b = 0;
        } else if (h < 120) {
            r = x; g = 255; b = 0;
        } else if (h < 180) {
            r = 0; g = 255; b = x;
        } else if (h < 240) {
            r = 0; g = x; b = 255;
        } else if (h < 300) {
            r = x; g = 0; b = 255;
        } else {
            r = 255; g = 0; b = x;
        }
        return (r << 16) | (g << 8) | b;
    }

    public static String hex(byte[] packet) {
        StringBuilder out = new StringBuilder();
        for (byte value : packet) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(String.format(Locale.US, "%02X", value & 0xFF));
        }
        return out.toString();
    }

    private static int clamp(int brightness) {
        return Math.max(0, Math.min(100, brightness));
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }
}
