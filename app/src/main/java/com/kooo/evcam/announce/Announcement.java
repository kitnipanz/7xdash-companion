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
    /** Playback speed, percent of normal ({@link #MIN_TUNE}-{@link #MAX_TUNE}). */
    public final int speed;
    /**
     * Voice pitch, percent of normal. Only a phrase read by a voice uses it; a recording or a sound
     * plays like a tape, its pitch following {@link #speed}.
     */
    public final int pitch;
    /**
     * Speaker in 7xDash's English voice ({@link OfflineVoice#VOICES}), or {@link #VOICE_STANDARD} for
     * the usual one. Used only by a phrase that voice reads.
     */
    public final int voice;

    public static final int VOICE_STANDARD = -1;
    public static final int MIN_TUNE = 50;
    public static final int MAX_TUNE = 150;

    public Announcement(String id, String sound, String text) {
        this(id, sound, text, 100, 100, VOICE_STANDARD);
    }

    public Announcement(String id, String sound, String text, int speed, int pitch, int voice) {
        this.id = id == null ? "" : id;
        this.sound = sound == null ? "" : sound;
        this.text = text == null ? "" : text;
        this.speed = clampTune(speed);
        this.pitch = clampTune(pitch);
        this.voice = voice < 0 ? VOICE_STANDARD : voice;
    }

    public Announcement withTune(int speed, int pitch, int voice) {
        return new Announcement(id, sound, text, speed, pitch, voice);
    }

    public boolean isTuned() {
        return speed != 100 || pitch != 100 || voice != VOICE_STANDARD;
    }

    static int clampTune(int percent) {
        return Math.max(MIN_TUNE, Math.min(MAX_TUNE, percent));
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
