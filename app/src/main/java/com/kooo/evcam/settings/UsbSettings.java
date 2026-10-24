package com.kooo.evcam.settings;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.widget.Toast;

import com.kooo.evcam.R;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.blackbox.BlackBox;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps a copy of the driver's settings on the USB stick ({@code <USB>/7xDash/settings/}), so a
 * reinstall or "Clear data" gets everything back: switches, shortcuts, stickers and lights,
 * speakers, announcements and their recordings.
 *
 * <p>The head unit keeps its own copy too, so the app works with the stick out. A few seconds
 * after any setting changes, the copy on USB is rewritten. On a fresh start (no local mark yet)
 * with the stick in and a copy on it, the copy is restored before anything reads a setting. Until
 * that check has run, nothing is written to the stick, so a fresh install never overwrites a good
 * copy with defaults.</p>
 *
 * <p>Not covered: Android's own grants (overlay, accessibility, permissions) and Bluetooth
 * pairing, which belong to the system, and the downloadable English voice (downloaded again).</p>
 */
public final class UsbSettings {

    /** Settings files copied. Run-time state (recording intent, black box marks, counters) stays local. */
    static List<String> files(Context context) {
        List<String> out = new ArrayList<>();
        out.add("app_config");
        out.add(context.getPackageName() + "_preferences");
        out.add("announcements");
        out.add("profiles");
        out.add("cabin_eq");
        out.add("sticker_names");
        out.add("developer_mode");
        return out;
    }

    private static final String STATE = "usb_settings";
    private static final String KEY_CHECKED = "checked";
    private static final String KEY_SAVED_AT = "saved_at";
    private static final long SAVE_DELAY_MS = 3_000L;
    private static final String DIR = "7xDash/settings";
    private static final String FILE = "settings.txt";
    private static final String VOICE = "announce_voice";

    private static HandlerThread thread;
    private static Handler worker;
    private static Context app;
    /** Held so the preference listeners are not garbage collected. */
    private static final List<SharedPreferences> watched = new ArrayList<>();
    private static final SharedPreferences.OnSharedPreferenceChangeListener changed =
            (prefs, key) -> scheduleSave();
    private static final Runnable save = () -> saveNow(false);

    private UsbSettings() {
    }

    /** Application start, before anything reads a setting. */
    public static synchronized void start(Context context) {
        if (app != null) {
            return;
        }
        app = context.getApplicationContext();
        thread = new HandlerThread("usb-settings");
        thread.start();
        worker = new Handler(thread.getLooper());
        if (!checked()) {
            File copy = copyOnUsb();
            if (copy != null && copy.isFile()) {
                restore(copy);
                markChecked();
            } else if (usbDir() != null) {
                // Stick in, nothing on it: this is the first copy
                markChecked();
            } else {
                waitForUsb();
            }
        }
        for (String name : files(app)) {
            SharedPreferences prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE);
            prefs.registerOnSharedPreferenceChangeListener(changed);
            watched.add(prefs);
        }
        scheduleSave();
    }

    /** "Last saved to USB" for the settings row, or null when never. */
    public static long savedAt(Context context) {
        return state(context).getLong(KEY_SAVED_AT, 0L);
    }

    /** Settings › System › Back up now. Worker thread result on the main thread. */
    public static void backupNow(Context context) {
        Context a = context.getApplicationContext();
        worker.post(() -> {
            markChecked();
            boolean ok = saveNow(true);
            new Handler(a.getMainLooper()).post(() -> com.kooo.evcam.ui.Notice.makeText(a,
                    ok ? R.string.usb_settings_saved : R.string.usb_settings_no_usb, Toast.LENGTH_SHORT).show());
        });
    }

    /** Settings › System › Restore from USB: put the copy back, then start 7xDash again so it reads them. */
    public static void restoreNow(Context context) {
        Context a = context.getApplicationContext();
        worker.post(() -> {
            File copy = copyOnUsb();
            if (copy == null || !copy.isFile()) {
                new Handler(a.getMainLooper()).post(() -> com.kooo.evcam.ui.Notice.makeText(a,
                        R.string.usb_settings_none, Toast.LENGTH_SHORT).show());
                return;
            }
            worker.removeCallbacks(save);
            restore(copy);
            markChecked();
            relaunch(a);
        });
    }

    private static boolean checked() {
        return state(app).getBoolean(KEY_CHECKED, false);
    }

    private static void markChecked() {
        state(app).edit().putBoolean(KEY_CHECKED, true).commit();
    }

    private static SharedPreferences state(Context context) {
        return context.getApplicationContext().getSharedPreferences(STATE, Context.MODE_PRIVATE);
    }

    private static void scheduleSave() {
        if (worker != null) {
            worker.removeCallbacks(save);
            worker.postDelayed(save, SAVE_DELAY_MS);
        }
    }

    private static File usbDir() {
        File root = StorageHelper.getExternalSdCardRoot(app);
        return root == null ? null : new File(root, DIR);
    }

    private static File copyOnUsb() {
        File dir = usbDir();
        return dir == null ? null : new File(dir, FILE);
    }

    /** Worker thread. False when there is no stick, or the copy could not be written. */
    private static boolean saveNow(boolean force) {
        if (!force && !checked()) {
            return false;
        }
        File dir = usbDir();
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) {
            return false;
        }
        Map<String, Map<String, ?>> all = new LinkedHashMap<>();
        for (String name : files(app)) {
            all.put(name, app.getSharedPreferences(name, Context.MODE_PRIVATE).getAll());
        }
        try {
            File part = new File(dir, FILE + ".part");
            Files.write(part.toPath(), encode(all).getBytes(StandardCharsets.UTF_8));
            File target = new File(dir, FILE);
            if (!part.renameTo(target)) {
                Files.copy(part.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                part.delete();
            }
            mirror(new File(app.getFilesDir(), VOICE), new File(dir, VOICE));
            state(app).edit().putLong(KEY_SAVED_AT, System.currentTimeMillis()).apply();
            return true;
        } catch (Exception e) {
            BlackBox.note("Settings: could not write the copy on USB: " + e);
            return false;
        }
    }

    /** Worker or start thread. Replaces each settings file with the copy's values. */
    private static void restore(File copy) {
        try {
            Map<String, Map<String, Object>> all = decode(
                    new String(Files.readAllBytes(copy.toPath()), StandardCharsets.UTF_8));
            int keys = 0;
            for (Map.Entry<String, Map<String, Object>> file : all.entrySet()) {
                if (!files(app).contains(file.getKey())) {
                    continue;
                }
                SharedPreferences.Editor edit = app.getSharedPreferences(file.getKey(), Context.MODE_PRIVATE)
                        .edit().clear();
                for (Map.Entry<String, Object> e : file.getValue().entrySet()) {
                    put(edit, e.getKey(), e.getValue());
                    keys++;
                }
                edit.commit();
            }
            mirror(new File(copy.getParentFile(), VOICE), new File(app.getFilesDir(), VOICE));
            BlackBox.noteImportant("Settings: restored " + keys + " settings from " + copy);
        } catch (Exception e) {
            BlackBox.noteImportant("Settings: could not restore from USB: " + e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void put(SharedPreferences.Editor edit, String key, Object value) {
        if (value instanceof Boolean) {
            edit.putBoolean(key, (Boolean) value);
        } else if (value instanceof Integer) {
            edit.putInt(key, (Integer) value);
        } else if (value instanceof Long) {
            edit.putLong(key, (Long) value);
        } else if (value instanceof Float) {
            edit.putFloat(key, (Float) value);
        } else if (value instanceof Set) {
            edit.putStringSet(key, (Set<String>) value);
        } else if (value != null) {
            edit.putString(key, value.toString());
        }
    }

    /** Makes {@code to} hold exactly the files of {@code from} (one level, the recordings). */
    private static void mirror(File from, File to) throws java.io.IOException {
        File[] source = from.listFiles();
        if (source == null) {
            source = new File[0];
        }
        if (!to.isDirectory() && !to.mkdirs()) {
            return;
        }
        Set<String> keep = new HashSet<>();
        for (File f : source) {
            if (!f.isFile()) {
                continue;
            }
            keep.add(f.getName());
            File target = new File(to, f.getName());
            if (target.length() != f.length()) {
                Files.copy(f.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        File[] existing = to.listFiles();
        if (existing != null) {
            for (File f : existing) {
                if (f.isFile() && !keep.contains(f.getName())) {
                    f.delete();
                }
            }
        }
    }

    /** No copy was found at start because the stick was not in yet: look again when it mounts. */
    private static void waitForUsb() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_MEDIA_MOUNTED);
        filter.addDataScheme("file");
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                worker.post(() -> {
                    if (checked()) {
                        return;
                    }
                    File copy = copyOnUsb();
                    if (copy != null && copy.isFile()) {
                        // Already running on fresh settings: offer, do not swap them under the driver
                        new Handler(app.getMainLooper()).post(() -> com.kooo.evcam.ui.Notice.makeText(app,
                                R.string.usb_settings_found, Toast.LENGTH_LONG).show());
                    } else if (usbDir() != null) {
                        markChecked();
                        scheduleSave();
                    }
                });
            }
        };
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            app.registerReceiver(receiver, filter);
        }
    }

    private static void relaunch(Context context) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            PendingIntent pending = PendingIntent.getActivity(context, 7110, launch,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT);
            AlarmManager alarms = context.getSystemService(AlarmManager.class);
            if (alarms != null) {
                alarms.set(AlarmManager.RTC, System.currentTimeMillis() + 800, pending);
            }
        }
        BlackBox.flush(500);
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    // ------------------------------------------------------------------ format

    private static final char SET_SEP = '\u001F';

    /**
     * One setting per line: file, key, type (b i l f s t) and value, tab-separated, with
     * backslash, tab and newline escaped. A string set ({@code t}) joins its items with U+001F.
     */
    static String encode(Map<String, ? extends Map<String, ?>> all) {
        StringBuilder sb = new StringBuilder("7xdash-settings 1\n");
        for (Map.Entry<String, ? extends Map<String, ?>> file : all.entrySet()) {
            for (Map.Entry<String, ?> e : file.getValue().entrySet()) {
                Object v = e.getValue();
                String type;
                String text;
                if (v instanceof Boolean) {
                    type = "b";
                    text = v.toString();
                } else if (v instanceof Integer) {
                    type = "i";
                    text = v.toString();
                } else if (v instanceof Long) {
                    type = "l";
                    text = v.toString();
                } else if (v instanceof Float) {
                    type = "f";
                    text = Float.toString((Float) v);
                } else if (v instanceof Set) {
                    type = "t";
                    StringBuilder items = new StringBuilder();
                    for (Object item : (Set<?>) v) {
                        if (items.length() > 0) {
                            items.append(SET_SEP);
                        }
                        items.append(item);
                    }
                    text = items.toString();
                } else if (v != null) {
                    type = "s";
                    text = v.toString();
                } else {
                    continue;
                }
                sb.append(escape(file.getKey())).append('\t').append(escape(e.getKey())).append('\t')
                        .append(type).append('\t').append(escape(text)).append('\n');
            }
        }
        return sb.toString();
    }

    static Map<String, Map<String, Object>> decode(String text) {
        Map<String, Map<String, Object>> all = new LinkedHashMap<>();
        String[] lines = text.split("\n");
        if (lines.length == 0 || !lines[0].startsWith("7xdash-settings ")) {
            return all;
        }
        for (int i = 1; i < lines.length; i++) {
            String[] p = lines[i].split("\t", -1);
            if (p.length != 4) {
                continue;
            }
            String value = unescape(p[3]);
            Object v;
            try {
                switch (p[2]) {
                    case "b":
                        v = Boolean.parseBoolean(value);
                        break;
                    case "i":
                        v = Integer.parseInt(value);
                        break;
                    case "l":
                        v = Long.parseLong(value);
                        break;
                    case "f":
                        v = Float.parseFloat(value);
                        break;
                    case "t":
                        Set<String> set = new HashSet<>();
                        if (!value.isEmpty()) {
                            for (String item : value.split(String.valueOf(SET_SEP), -1)) {
                                set.add(item);
                            }
                        }
                        v = set;
                        break;
                    case "s":
                        v = value;
                        break;
                    default:
                        continue;
                }
            } catch (NumberFormatException e) {
                continue;
            }
            all.computeIfAbsent(unescape(p[0]), k -> new LinkedHashMap<>()).put(unescape(p[1]), v);
        }
        return all;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                out.append(next == 't' ? '\t' : next == 'n' ? '\n' : next);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
