package com.kooo.evcam.input;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Saved sticker MAC addresses, one per line. */
public final class StickerDevices {

    private StickerDevices() {
    }

    public static List<String> parse(String raw) {
        List<String> items = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return items;
        }
        for (String line : raw.split("\n")) {
            String mac = normalize(line);
            if (!mac.isEmpty() && !items.contains(mac)) {
                items.add(mac);
            }
        }
        return items;
    }

    public static String write(List<String> items) {
        StringBuilder out = new StringBuilder();
        if (items == null) {
            return "";
        }
        for (String item : items) {
            String mac = normalize(item);
            if (mac.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(mac);
        }
        return out.toString();
    }

    public static List<String> put(List<String> items, String address) {
        List<String> out = new ArrayList<>();
        String mac = normalize(address);
        if (items != null) {
            for (String item : items) {
                String saved = normalize(item);
                if (!saved.isEmpty() && !saved.equals(mac)) {
                    out.add(saved);
                }
            }
        }
        if (!mac.isEmpty()) {
            out.add(mac);
        }
        return out;
    }

    public static List<String> remove(List<String> items, String address) {
        List<String> out = new ArrayList<>();
        String mac = normalize(address);
        if (items == null) {
            return out;
        }
        for (String item : items) {
            String saved = normalize(item);
            if (!saved.isEmpty() && !saved.equals(mac)) {
                out.add(saved);
            }
        }
        return out;
    }

    public static String normalize(String address) {
        if (address == null) {
            return "";
        }
        return address.trim().toUpperCase(Locale.US);
    }
}
