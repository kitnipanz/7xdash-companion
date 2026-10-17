package com.kooo.evcam.input;

import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.SparseArray;

import com.kooo.evcam.AppConfig;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes sticker adverts seen by the BLE signals screen into the sticker log, so a press on a
 * sleeping sticker can be read back: does its advert carry the press, or only "I am here"?
 * Only saved stickers and sticker-looking names are written; a payload is written when it
 * changes, and an unchanged one at most once a second.
 */
final class StickerAdverts {

    private static final Map<String, String> LAST_PAYLOAD = new HashMap<>();
    private static final Map<String, Long> LAST_AT = new HashMap<>();

    private StickerAdverts() {
    }

    static void start(Context context) {
        StickerLog.attach(context);
        synchronized (LAST_PAYLOAD) {
            LAST_PAYLOAD.clear();
            LAST_AT.clear();
        }
        StickerLog.line("advert scan start");
    }

    static void stop() {
        StickerLog.line("advert scan stop");
    }

    /** Any thread. */
    static void seen(Context context, ScanResult result) {
        if (context == null || result == null || result.getDevice() == null) {
            return;
        }
        String mac;
        String name;
        try {
            mac = StickerDevices.normalize(result.getDevice().getAddress());
            name = result.getDevice().getName();
        } catch (SecurityException e) {
            return;
        }
        ScanRecord record = result.getScanRecord();
        if (name == null && record != null) {
            name = record.getDeviceName();
        }
        if (!isButton(name)) {
            return;
        }
        boolean saved = StickerDevices.parse(new AppConfig(context).getStickerDevices()).contains(mac);
        byte[] raw = record == null ? null : record.getBytes();
        String payload = StickerFrame.hex(trim(raw));
        long now = SystemClock.elapsedRealtime();
        synchronized (LAST_PAYLOAD) {
            String last = LAST_PAYLOAD.get(mac);
            Long at = LAST_AT.get(mac);
            // Only changes: the same broadcast repeats about once a second.
            if (payload.equals(last)) {
                return;
            }
            boolean changed = last != null && !payload.equals(last);
            LAST_PAYLOAD.put(mac, payload);
            LAST_AT.put(mac, now);
            String decoded = decode(raw);
            StickerLog.line("advert " + mac + (saved ? "" : " (unsaved)")
                    + (decoded.isEmpty() ? "" : " " + decoded)
                    + " rssi=" + result.getRssi()
                    + " connectable=" + result.isConnectable()
                    + (changed ? "" : " FIRST")
                    + " raw=" + payload);
        }
    }

    static final String BUTTON_NAME = "ZEEKR_button";

    static boolean isButton(String name) {
        return name != null && name.trim().equalsIgnoreCase(BUTTON_NAME);
    }

    /** Single press (y=00) and double press (y=02), confirmed in the car 2026-10-16. */
    static final int Y_SINGLE = 0x00;
    static final int Y_DOUBLE = 0x02;

    /**
     * {cnt, y} from a button advert, or null when the bytes are not one. cnt is the button's
     * own press counter: it changes once per press and stays put between presses.
     */
    static int[] fields(byte[] raw) {
        int at = buttonData(raw);
        if (at < 0) {
            return null;
        }
        return new int[]{raw[at + 7] & 0xFF, raw[at + 2] & 0xFF};
    }

    /**
     * A new press when this cnt differs from the last one seen for the button. The first
     * advert after start only sets the baseline, so nothing fires when the app opens.
     */
    static boolean isNewPress(Integer lastCnt, int cnt) {
        return lastCnt != null && lastCnt != cnt;
    }

    /** The GATT-style press byte for a y value (31 single, 32 double), or null if unknown. */
    static byte[] frameFor(int y) {
        if (y == Y_SINGLE) {
            return new byte[]{0x31};
        }
        if (y == Y_DOUBLE) {
            return new byte[]{0x32};
        }
        return null;
    }

    /** Offset of the FF byte that starts the button data, or -1. */
    private static int buttonData(byte[] raw) {
        if (raw == null) {
            return -1;
        }
        int i = 0;
        while (i + 1 < raw.length) {
            int len = raw[i] & 0xFF;
            if (len == 0 || i + len >= raw.length + 1) {
                break;
            }
            int type = raw[i + 1] & 0xFF;
            if (type == 0x02 && len == 0x12 && i + 2 + 10 < raw.length && (raw[i + 2] & 0xFF) == 0xFF) {
                return i + 2;
            }
            i += len + 1;
        }
        return -1;
    }

    /**
     * The button's data rides in an AD structure typed "16-bit UUIDs" (0x02), 18 bytes:
     * FF, X, Y, 4B 01 03 01, CNT, 04 00 00, then a 6-byte tail. CNT ticks about once a second
     * and is the same on every button; cnt is the button's press counter and Y the
     * press kind (00 single, 02 double). Returns "cnt=AA y=00" or "" if the shape does not match.
     */
    static String decode(byte[] raw) {
        int at = buttonData(raw);
        if (at < 0) {
            return "";
        }
        int x = raw[at + 1] & 0xFF;
        int y = raw[at + 2] & 0xFF;
        int cnt = raw[at + 7] & 0xFF;
        return String.format("cnt=%02X x=%02X y=%02X%s", cnt, x, y,
                y == Y_DOUBLE ? " DOUBLE" : y == Y_SINGLE ? "" : " Y?");
    }


    /** Advert buffers are padded with zeros to 62 bytes; drop the padding. */
    private static byte[] trim(byte[] raw) {
        if (raw == null) {
            return null;
        }
        int end = raw.length;
        while (end > 0 && raw[end - 1] == 0) {
            end--;
        }
        byte[] out = new byte[end];
        System.arraycopy(raw, 0, out, 0, end);
        return out;
    }
}
