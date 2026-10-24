package com.kooo.evcam.announce;

/**
 * One thing to play on the outside speakers: a phrase read out by the head unit's text-to-speech,
 * or a built-in sound ({@link Tones}). Exactly one of the two is set.
 */
public final class Announcement {

    public final String id;
    /** {@link Tones} key, or empty for a phrase. */
    public final String sound;
    /** The phrase, or the sound's name when {@link #sound} is set (shown on its button). */
    public final String text;

    public Announcement(String id, String sound, String text) {
        this.id = id == null ? "" : id;
        this.sound = sound == null ? "" : sound;
        this.text = text == null ? "" : text;
    }

    public static Announcement phrase(String id, String text) {
        return new Announcement(id, "", text);
    }

    public static Announcement sound(String id, String key, String name) {
        return new Announcement(id, key, name);
    }

    public boolean isSound() {
        return !sound.isEmpty();
    }

    /** Thai script anywhere in the phrase. No voice reads Thai on this car: it plays only when recorded. */
    public boolean isThai() {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '\u0E00' && c <= '\u0E7F') {
                return true;
            }
        }
        return false;
    }
}
