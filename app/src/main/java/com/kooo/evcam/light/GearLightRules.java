package com.kooo.evcam.light;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What the mood light shows for each gear, saved as one JSON string ({@code AppConfig}).
 *
 * <p>A null look means "leave the light as it is" for that gear. {@link #pick} is pure, so the
 * hysteresis is tested ({@code GearLightRulesTest}).</p>
 */
public final class GearLightRules {

    /** Moving below this counts as stopped (D at 0 km/h). */
    static final float STOP_BELOW_KMH = 1f;
    /** Stopped needs this much speed before it counts as moving again. */
    static final float MOVE_FROM_KMH = 3f;
    /** A speed colour stays until the speed drops this far under where it starts. */
    static final float BAND_HYSTERESIS_KMH = 3f;
    public static final int MAX_BANDS = 8;
    /** A blend moves in this many steps between two speed colours, so the light is not rewritten every reading. */
    static final int BLEND_STEPS = 20;

    /** One "from this speed" colour, in D. */
    public static final class Band {
        public int fromKmh;
        public LightLook look;

        public Band() {
        }

        public Band(int fromKmh, LightLook look) {
            this.fromKmh = fromKmh;
            this.look = look;
        }
    }

    /** Where {@link #pick} landed: a key for "same as before?" and the look to show (null = leave it). */
    public static final class Pick {
        public final String key;
        public final LightLook look;
        /** True while the decision would change with speed: the caller then needs speed readings. */
        public final boolean wantsSpeed;

        Pick(String key, LightLook look, boolean wantsSpeed) {
            this.key = key;
            this.look = look;
            this.wantsSpeed = wantsSpeed;
        }
    }

    public static final String KEY_P = "P";
    public static final String KEY_R = "R";
    public static final String KEY_D = "D";
    public static final String KEY_D_STOPPED = "D0";
    static final String KEY_BAND = "band:";
    /** Between {@link #KEY_BAND} index and blend step: {@code band:0~5}. */
    static final String BLEND = "~";

    private static final Gson GSON = new Gson();

    public boolean enabled;
    public LightLook park;
    public LightLook reverse;
    public LightLook drive;
    public LightLook driveStopped;
    public List<Band> bands = new ArrayList<>();
    /** Between two solid speed colours, mix them by where the speed sits ({@link #mix}). */
    public boolean blend = true;

    public static GearLightRules defaults() {
        GearLightRules rules = new GearLightRules();
        rules.park = new LightLook(0x8000FF, 60, LightProtocol.Effect.BREATHE, 30);
        rules.reverse = new LightLook(0xFFFFFF, 100, LightProtocol.Effect.STATIC, 50);
        rules.drive = new LightLook(0x0060FF, 50, LightProtocol.Effect.STATIC, 50);
        rules.driveStopped = new LightLook(0x00FF00, 40, LightProtocol.Effect.STATIC, 50);
        rules.bands.add(new Band(80, new LightLook(0xFF6000, 70, LightProtocol.Effect.STATIC, 50)));
        rules.bands.add(new Band(120, new LightLook(0xFF0000, 100, LightProtocol.Effect.BLINK, 70)));
        return rules;
    }

    public static GearLightRules parse(String json) {
        if (json == null || json.isEmpty()) {
            return defaults();
        }
        try {
            GearLightRules rules = GSON.fromJson(json, GearLightRules.class);
            if (rules == null) {
                return defaults();
            }
            if (rules.bands == null) {
                rules.bands = new ArrayList<>();
            }
            rules.bands.removeIf(band -> band == null || band.look == null);
            rules.sortBands();
            return rules;
        } catch (JsonParseException e) {
            return defaults();
        }
    }

    public String toJson() {
        sortBands();
        return GSON.toJson(this);
    }

    public void sortBands() {
        Collections.sort(bands, (a, b) -> Integer.compare(a.fromKmh, b.fromKmh));
    }

    public LightLook look(String key) {
        switch (key) {
            case KEY_P: return park;
            case KEY_R: return reverse;
            case KEY_D: return drive;
            case KEY_D_STOPPED: return driveStopped;
            default: return null;
        }
    }

    public void setLook(String key, LightLook look) {
        switch (key) {
            case KEY_P: park = look; break;
            case KEY_R: reverse = look; break;
            case KEY_D: drive = look; break;
            case KEY_D_STOPPED: driveStopped = look; break;
            default: break;
        }
    }

    /**
     * What to show now.
     *
     * @param gear     "P" / "R" / "N" / "D", null when unknown
     * @param speedKmh null when not read
     * @param previous the last {@link Pick#key}, for hysteresis; null at first
     */
    public Pick pick(String gear, Float speedKmh, String previous) {
        if (!enabled || gear == null) {
            return new Pick(null, null, false);
        }
        switch (gear) {
            case "P":
                return new Pick(KEY_P, park, false);
            case "R":
                return new Pick(KEY_R, reverse, false);
            case "D":
                return pickDrive(speedKmh, previous);
            default:
                // N (and anything new): leave the light as it is
                return new Pick(null, null, false);
        }
    }

    private Pick pickDrive(Float speedKmh, String previous) {
        boolean wantsSpeed = driveStopped != null || !bands.isEmpty();
        if (speedKmh == null) {
            return new Pick(KEY_D, drive, wantsSpeed);
        }
        float v = speedKmh;
        if (driveStopped != null) {
            // Shifting into D from P or R starts out stopped
            boolean wasMoving = KEY_D.equals(previous) || previous != null && previous.startsWith(KEY_BAND);
            boolean stopped = wasMoving ? v < STOP_BELOW_KMH : v < MOVE_FROM_KMH;
            if (stopped) {
                return new Pick(KEY_D_STOPPED, driveStopped, true);
            }
        }
        int band = -1;
        for (int i = 0; i < bands.size(); i++) {
            if (v >= bands.get(i).fromKmh) {
                band = i;
            }
        }
        if (blendable(band)) {
            Band low = bands.get(band);
            Band high = bands.get(band + 1);
            float t = (v - low.fromKmh) / (float) (high.fromKmh - low.fromKmh);
            int step = Math.max(0, Math.min(BLEND_STEPS, Math.round(t * BLEND_STEPS)));
            return new Pick(KEY_BAND + band + BLEND + step,
                    mix(low.look, high.look, step / (float) BLEND_STEPS), true);
        }
        int held = heldBand(previous);
        if (held > band && held < bands.size() && v >= bands.get(held).fromKmh - BAND_HYSTERESIS_KMH) {
            band = held;
        }
        if (band >= 0) {
            return new Pick(KEY_BAND + band, bands.get(band).look, true);
        }
        return new Pick(KEY_D, drive, wantsSpeed);
    }

    /** Band {@code i} fades into the next one: blending is on and both are solid colours. */
    private boolean blendable(int i) {
        return blend && i >= 0 && i + 1 < bands.size()
                && bands.get(i).look.effect() == LightProtocol.Effect.STATIC
                && bands.get(i + 1).look.effect() == LightProtocol.Effect.STATIC
                && bands.get(i + 1).fromKmh > bands.get(i).fromKmh;
    }

    /** {@code t} of the way from {@code a} to {@code b}, per channel and brightness. */
    static LightLook mix(LightLook a, LightLook b, float t) {
        int color = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int from = (a.color >> shift) & 0xFF;
            int to = (b.color >> shift) & 0xFF;
            color |= Math.round(from + (to - from) * t) << shift;
        }
        int brightness = Math.round(a.brightness + (b.brightness - a.brightness) * t);
        return new LightLook(color, brightness, LightProtocol.Effect.STATIC, a.speed);
    }

    private static int heldBand(String previous) {
        if (previous == null || !previous.startsWith(KEY_BAND)) {
            return -1;
        }
        String index = previous.substring(KEY_BAND.length());
        int cut = index.indexOf(BLEND);
        if (cut >= 0) {
            index = index.substring(0, cut);
        }
        try {
            return Integer.parseInt(index);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
