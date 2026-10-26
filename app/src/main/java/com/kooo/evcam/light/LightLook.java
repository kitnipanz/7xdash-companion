package com.kooo.evcam.light;

import java.util.Objects;

/** What the light shows: a colour, an animation, or off. Kept in {@link GearLightRules} as JSON (Gson). */
public final class LightLook {

    /**
     * The lights' built-in animations are off for now: on a real strip they did not look like
     * their previews. A look saved with one shows as a steady colour, and the editor offers only
     * a colour or Off. The animation code stays, for when they match.
     */
    static final boolean ANIMATIONS = false;

    /** {@code 0xRRGGBB} */
    public int color = 0xFFFFFF;
    /** 0–100 */
    public int brightness = 80;
    /** {@link LightProtocol.Effect} name */
    public String effect = LightProtocol.Effect.STATIC.name();
    /** Animation speed 0–100, faster up. */
    public int speed = 50;

    public LightLook() {
    }

    public LightLook(int color, int brightness, LightProtocol.Effect effect, int speed) {
        this.color = color & 0xFFFFFF;
        this.brightness = brightness;
        this.effect = effect.name();
        this.speed = speed;
    }

    public LightProtocol.Effect effect() {
        LightProtocol.Effect e = LightProtocol.Effect.parse(effect);
        return !ANIMATIONS && animated(e) ? LightProtocol.Effect.STATIC : e;
    }

    static boolean animated(LightProtocol.Effect e) {
        return e != LightProtocol.Effect.STATIC && e != LightProtocol.Effect.OFF;
    }

    public LightLook copy() {
        return new LightLook(color, brightness, effect(), speed);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof LightLook)) {
            return false;
        }
        LightLook other = (LightLook) o;
        return color == other.color && brightness == other.brightness && speed == other.speed
                && effect() == other.effect();
    }

    @Override
    public int hashCode() {
        return Objects.hash(color, brightness, effect(), speed);
    }
}
