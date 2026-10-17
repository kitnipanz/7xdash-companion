package com.kooo.evcam.recording;

import android.content.Context;

import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.share.UsbExport;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Files in {@code <usb>/instant captures}. Listing and deleting stay inside
 * that folder and only touch this app's clip names.
 */
public final class InstantClips {

    private static final Pattern CLIP = Pattern.compile("^\\d{8}_\\d{6}_[A-Za-z0-9_]+\\.mp4$");

    private InstantClips() {
    }

    /** {@code usbRoot/instant captures}, or null when no USB stick is mounted. */
    public static File folder(Context context) {
        if (context == null) {
            return null;
        }
        File root = StorageHelper.getExternalSdCardRoot(context);
        if (root == null) {
            return null;
        }
        return UsbExport.instantCaptures(root);
    }

    /** Newest first. Directories and other files are left out. */
    public static List<File> list(File dir) {
        List<File> out = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) {
            return out;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return out;
        }
        for (File file : files) {
            if (owned(dir, file)) {
                out.add(file);
            }
        }
        Collections.sort(out, (a, b) -> b.getName().compareTo(a.getName()));
        return out;
    }

    /** Deletes one clip that lives directly in {@code dir}. */
    public static boolean delete(File dir, File file) {
        return owned(dir, file) && file.delete();
    }

    /** Deletes every clip in {@code dir}. Returns how many were removed. */
    public static int deleteAll(File dir) {
        int removed = 0;
        for (File file : list(dir)) {
            if (file.delete()) {
                removed++;
            }
        }
        return removed;
    }

    static boolean owned(File dir, File file) {
        if (dir == null || file == null || !file.isFile()) {
            return false;
        }
        if (!CLIP.matcher(file.getName()).matches()) {
            return false;
        }
        try {
            File parent = file.getCanonicalFile().getParentFile();
            File root = dir.getCanonicalFile();
            return parent != null && parent.equals(root);
        } catch (IOException e) {
            return false;
        }
    }
}
