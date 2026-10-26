package com.kooo.evcam.light;

/**
 * One light in a {@link LightGroup}: a Bluetooth mood light ({@link MoodLight}) or the car's own
 * ambient light ({@link CarAmbientLight}). Calls return at once; the work runs on the link's thread
 * and {@link MoodLight.Listener} hears back on the main thread.
 */
interface LightLink {
    void connect(String address);

    void power(boolean on);

    void color(int rgb, int brightness);

    void show(LightLook look);

    /** Close the link. The object is spent after this. */
    void release();
}
