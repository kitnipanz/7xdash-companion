package com.kooo.evcam.recording;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Protected clip groups ({@code yyyyMMdd_HHmmss}). Auto-delete never removes them.
 * Stored as one stamp per line.
 */
public final class SavedClips {

    private SavedClips() {
    }

    public static Set<String> parse(String raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String line : raw.split("\n")) {
            String stamp = normalize(line);
            if (stamp != null) {
                out.add(stamp);
            }
        }
        return out;
    }

    public static String write(Set<String> stamps) {
        if (stamps == null || stamps.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        List<String> ordered = new ArrayList<>(stamps);
        Collections.sort(ordered);
        for (String stamp : ordered) {
            String n = normalize(stamp);
            if (n == null) {
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(n);
        }
        return out.toString();
    }

    public static boolean isProtected(Set<String> stamps, String fileName) {
        if (stamps == null || stamps.isEmpty() || fileName == null) {
            return false;
        }
        String group = com.kooo.evcam.camera.StoragePlan.groupOf(fileName);
        return group.length() == 15 && stamps.contains(group);
    }

    /** Stamp from a file name, or null if it is not one of ours. */
    public static String stampOf(String fileName) {
        if (!com.kooo.evcam.camera.StoragePlan.isOwnClip(fileName)) {
            return null;
        }
        String group = com.kooo.evcam.camera.StoragePlan.groupOf(fileName);
        return group.length() == 15 ? group : null;
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String stamp = raw.trim();
        if (stamp.length() != 15 || stamp.charAt(8) != '_') {
            return null;
        }
        for (int i = 0; i < stamp.length(); i++) {
            char c = stamp.charAt(i);
            if (i == 8) {
                continue;
            }
            if (c < '0' || c > '9') {
                return null;
            }
        }
        return stamp;
    }
}
