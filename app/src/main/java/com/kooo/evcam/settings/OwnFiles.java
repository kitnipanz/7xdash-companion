package com.kooo.evcam.settings;

import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lists folders this app already owns. A path is shown only when its canonical file sits inside
 * one of those roots, so a symlink cannot walk out into another app's directory.
 */
public final class OwnFiles {

    public enum Kind {
        PRIVATE,
        EXTERNAL
    }

    public static final class Root {
        public final Kind kind;
        public final File dir;

        public Root(Kind kind, File dir) {
            this.kind = kind;
            this.dir = dir;
        }
    }

    public static final class Entry {
        public final String name;
        public final File file;
        public final boolean directory;
        public final boolean inside;
        public final long bytes;

        Entry(String name, File file, boolean directory, boolean inside, long bytes) {
            this.name = name;
            this.file = file;
            this.directory = directory;
            this.inside = inside;
            this.bytes = bytes;
        }
    }

    public static final class Listing {
        public final boolean allowed;
        public final boolean readable;
        public final List<Entry> entries;

        Listing(boolean allowed, boolean readable, List<Entry> entries) {
            this.allowed = allowed;
            this.readable = readable;
            this.entries = entries;
        }
    }

    private OwnFiles() {
    }

    /** Private data directory, plus the app's external files directory when the platform returns one. */
    public static List<Root> roots(File dataDir, File externalDir) {
        List<Root> roots = new ArrayList<>();
        if (dataDir != null) {
            roots.add(new Root(Kind.PRIVATE, dataDir));
        }
        if (externalDir != null) {
            roots.add(new Root(Kind.EXTERNAL, externalDir));
        }
        return roots;
    }

    public static List<File> directories(List<Root> roots) {
        List<File> dirs = new ArrayList<>();
        for (Root root : roots) {
            dirs.add(root.dir);
        }
        return dirs;
    }

    /** True when {@code file} resolves to {@code root} or a file under it. */
    public static boolean inside(File file, List<File> roots) {
        File canon = canonical(file);
        if (canon == null) {
            return false;
        }
        String path = canon.getPath();
        for (File root : roots) {
            File baseFile = canonical(root);
            if (baseFile == null) {
                continue;
            }
            String base = baseFile.getPath();
            if (path.equals(base) || path.startsWith(base + File.separator)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Children of {@code dir}. {@code allowed} is false when {@code dir} is outside the roots.
     * {@code readable} is false when the platform refuses the listing.
     */
    public static Listing list(File dir, List<File> roots) {
        if (!inside(dir, roots)) {
            return new Listing(false, false, Collections.emptyList());
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return new Listing(true, false, Collections.emptyList());
        }
        List<Entry> entries = new ArrayList<>();
        for (File child : children) {
            boolean childInside = inside(child, roots);
            boolean directory = child.isDirectory();
            long bytes = directory ? -1L : child.length();
            entries.add(new Entry(child.getName(), child, directory, childInside, bytes));
        }
        Collections.sort(entries, (a, b) -> {
            if (a.directory != b.directory) {
                return a.directory ? -1 : 1;
            }
            return a.name.compareToIgnoreCase(b.name);
        });
        return new Listing(true, true, entries);
    }

    /**
     * Unix mode of {@code file} without following a symlink, plus user and group ids.
     * When the platform refuses the stat, the result is only what this process can do
     * ({@code access rw-}).
     */
    public static String permission(File file) {
        if (file == null) {
            return "permission unknown";
        }
        try {
            StructStat stat = Os.lstat(file.getPath());
            return mode(stat.st_mode) + " · " + stat.st_uid + ":" + stat.st_gid;
        } catch (ErrnoException | SecurityException e) {
            return access(file);
        }
    }

    /** {@code st_mode} as a type character plus nine permission bits, for example {@code drwxr-xr-x}. */
    public static String mode(int stMode) {
        char type;
        int kind = stMode & 0170000;
        if (kind == 0040000) {
            type = 'd';
        } else if (kind == 0120000) {
            type = 'l';
        } else if (kind == 0020000) {
            type = 'c';
        } else if (kind == 0060000) {
            type = 'b';
        } else if (kind == 0010000) {
            type = 'p';
        } else if (kind == 0140000) {
            type = 's';
        } else {
            type = '-';
        }
        char[] bits = new char[9];
        int[] masks = {00400, 00200, 00100, 00040, 00020, 00010, 00004, 00002, 00001};
        char[] letters = {'r', 'w', 'x', 'r', 'w', 'x', 'r', 'w', 'x'};
        for (int i = 0; i < masks.length; i++) {
            bits[i] = (stMode & masks[i]) != 0 ? letters[i] : '-';
        }
        return type + new String(bits);
    }

    private static String access(File file) {
        return "access "
                + (file.canRead() ? 'r' : '-')
                + (file.canWrite() ? 'w' : '-')
                + (file.canExecute() ? 'x' : '-');
    }

    private static File canonical(File file) {
        if (file == null) {
            return null;
        }
        try {
            return file.getCanonicalFile();
        } catch (IOException e) {
            return null;
        }
    }
}
