package com.kooo.evcam.announce;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The announcements the driver can play, in the order shown, and which one plays when Sentry Mode
 * arms. First read fills in {@link #defaults()}; after that the list is whatever the driver kept.
 */
public final class AnnouncementStore {

    private static final String PREFS = "announcements";
    private static final String KEY_LIST = "list";
    private static final String KEY_SENTRY = "sentry";
    /** Sounds already offered to this list, so ones added in a later version show up once, after the others. */
    private static final String KEY_SOUNDS_OFFERED = "sounds_offered";
    /** Thai phrases shipped in 1.3.0 to 1.3.2; dropped since no voice could read them. */
    static final List<String> OLD_THAI = java.util.Arrays.asList(
            "th-step-back", "th-reversing", "th-recording", "th-thanks");
    private static final String KEY_THAI_DROPPED = "thai_dropped";
    /** Longest phrase kept: text-to-speech engines refuse very long input. */
    public static final int MAX_TEXT = 200;

    private final SharedPreferences prefs;
    private final File dir;

    public AnnouncementStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        dir = context.getApplicationContext().getFilesDir();
    }

    public List<Announcement> all() {
        String saved = prefs.getString(KEY_LIST, null);
        if (saved == null) {
            return defaults();
        }
        List<Announcement> list = decode(saved);
        String offered = prefs.getString(KEY_SOUNDS_OFFERED, Tones.CHIME + "," + Tones.BEEP + "," + Tones.SIREN);
        boolean changed = addNewSounds(list, offered);
        if (!prefs.getBoolean(KEY_THAI_DROPPED, false)) {
            // Once: the shipped Thai phrases go, unless the driver recorded them in their own voice
            changed |= list.removeIf(a -> OLD_THAI.contains(a.id) && !hasRecording(a.id));
            prefs.edit().putBoolean(KEY_THAI_DROPPED, true).apply();
        }
        if (changed) {
            save(list);
        }
        return list;
    }

    /**
     * Adds the sounds not offered before after the last sound in the list (a sound the driver deleted
     * stays deleted). True when the list changed.
     */
    static boolean addNewSounds(List<Announcement> list, String offered) {
        List<String> known = java.util.Arrays.asList(offered.split(","));
        int at = 0;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).isSound()) {
                at = i + 1;
            }
        }
        boolean changed = false;
        for (Announcement sound : defaultSounds()) {
            if (!known.contains(sound.sound)) {
                list.add(at++, sound);
                changed = true;
            }
        }
        return changed;
    }

    public Announcement find(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (Announcement a : all()) {
            if (a.id.equals(id)) {
                return a;
            }
        }
        return null;
    }

    /** Adds a phrase at the end. Blank text is ignored; long text is cut to {@link #MAX_TEXT}. */
    public Announcement add(String text) {
        String clean = clean(text);
        if (clean.isEmpty()) {
            return null;
        }
        Announcement added = Announcement.phrase(UUID.randomUUID().toString(), clean);
        List<Announcement> list = all();
        list.add(added);
        save(list);
        return added;
    }

    public void remove(String id) {
        List<Announcement> list = all();
        list.removeIf(a -> a.id.equals(id));
        save(list);
        recording(id).delete();
        if (id.equals(sentryId())) {
            setSentryId("");
        }
    }

    /** The driver's own recording of a phrase; played instead of the speech engine when it exists. */
    public File recording(String id) {
        return new File(new File(dir, "announce_voice"), id.replaceAll("[^A-Za-z0-9_-]", "_") + ".wav");
    }

    public boolean hasRecording(String id) {
        return recording(id).length() > 44;
    }

    /** Announcement played when Sentry Mode arms; empty for none (the default). */
    public String sentryId() {
        return prefs.getString(KEY_SENTRY, "");
    }

    public void setSentryId(String id) {
        prefs.edit().putString(KEY_SENTRY, id == null ? "" : id).apply();
    }

    private void save(List<Announcement> list) {
        prefs.edit().putString(KEY_LIST, encode(list))
                .putString(KEY_SOUNDS_OFFERED, String.join(",", Tones.ALL)).apply();
    }

    static String clean(String text) {
        if (text == null) {
            return "";
        }
        String one = text.replaceAll("\\s+", " ").trim();
        return one.length() > MAX_TEXT ? one.substring(0, MAX_TEXT).trim() : one;
    }

    /** Sounds first, then English phrases. Ids stay fixed so a sentry pick survives. */
    static List<Announcement> defaults() {
        List<Announcement> list = defaultSounds();
        list.add(Announcement.phrase("en-step-back", "Please step back from the car."));
        list.add(Announcement.phrase("en-reversing", "Caution, this vehicle is reversing."));
        list.add(Announcement.phrase("en-recording", "Please do not touch the car. You are being recorded."));
        list.add(Announcement.phrase("en-coming-through", "Excuse me, coming through."));
        list.add(Announcement.phrase("en-thanks", "Thank you!"));
        return list;
    }

    /** Every built-in sound; the id is "sound-" plus its key. The name is only a fallback (labels come from strings). */
    static List<Announcement> defaultSounds() {
        List<Announcement> list = new ArrayList<>();
        for (String key : Tones.ALL) {
            list.add(Announcement.sound("sound-" + key, key, key));
        }
        return list;
    }

    /** One announcement per line: id, sound and text separated by tabs, with \\ \t \n escaped. */
    static String encode(List<Announcement> list) {
        StringBuilder sb = new StringBuilder();
        for (Announcement a : list) {
            sb.append(escape(a.id)).append('\t').append(escape(a.sound)).append('\t')
                    .append(escape(a.text)).append('\n');
        }
        return sb.toString();
    }

    static List<Announcement> decode(String saved) {
        List<Announcement> list = new ArrayList<>();
        for (String line : saved.split("\n")) {
            String[] parts = line.split("\t", -1);
            if (parts.length != 3 || parts[0].isEmpty()) {
                continue;
            }
            list.add(new Announcement(unescape(parts[0]), unescape(parts[1]), unescape(parts[2])));
        }
        return list;
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
