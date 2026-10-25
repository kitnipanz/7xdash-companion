package com.kooo.evcam.light;

import java.util.Objects;

/** What the light shows: a colour, an animation, or off. Kept in {@link GearLightRules} as JSON (Gson). */
public final class LightLook {

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
        return LightProtocol.Effect.parse(effect);
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
