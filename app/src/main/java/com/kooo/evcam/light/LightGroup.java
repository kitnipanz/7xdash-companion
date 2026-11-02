package com.kooo.evcam.light;

import android.content.Context;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every saved mood light, driven as one: each command goes to each light that is up. One
 * {@link MoodLight} link per light, so a slow or missing light does not hold up the others.
 *
 * <p>Main thread only; {@link Listener} is called there too.</p>
 */
public final class LightGroup {

    /** Each Bluetooth light is its own GATT link; this head unit's radio has room for a few. The car's own light does not count. */
    public static final int MAX_LIGHTS = 4;

    public static final int STATE_CONNECTING = 0;
    public static final int STATE_READY = 1;
    public static final int STATE_DOWN = 2;

    public interface Listener {
        /** A light came up, went down or started dialling. */
        void onGroupChanged(String address);
    }

    private final class Member implements MoodLight.Listener {
        final String address;
        final LightLink light;
        int state = STATE_CONNECTING;
        LightProtocol.Family family;
        /** {@code MoodLight.FAIL_*} of the last drop, 0 for a plain disconnect */
        int reason;
        int code;
        /** Dials in a row that did not bring the light up; 0 once it is up. */
        int misses;

        Member(String address) {
            this.address = address;
            this.light = CarAmbientLight.isCar(address)
                    ? new CarAmbientLight(app, this)
                    : new MoodLight(app, this);
        }

        private boolean current() {
            return members.get(address) == this;
        }

        @Override
        public void onLightConnecting() {
            if (current()) {
                state = STATE_CONNECTING;
                listener.onGroupChanged(address);
            }
        }

        @Override
        public void onLightReady(LightProtocol.Family found) {
            if (current()) {
                if (misses > 0) {
                    com.kooo.evcam.blackbox.BlackBox.note("Mood light " + address + " up after "
                            + misses + " missed dial" + (misses == 1 ? "" : "s"));
                }
                misses = 0;
                state = STATE_READY;
                family = found;
                listener.onGroupChanged(address);
            }
        }

        @Override
        public void onLightDown(int why, int status) {
            if (current()) {
                if (misses == 0 || misses % 10 == 0) {
                    // First miss of a run, then every tenth: enough to see the pattern, not a flood
                    com.kooo.evcam.blackbox.BlackBox.note("Mood light " + address + " down: reason "
                            + why + " status " + status + " (miss " + (misses + 1) + ")");
                }
                misses++;
                state = STATE_DOWN;
                reason = why;
                code = status;
                listener.onGroupChanged(address);
            }
        }
    }

    private final Context app;
    private final Listener listener;
    private final Map<String, Member> members = new LinkedHashMap<>();

    public LightGroup(Context context, Listener listener) {
        this.app = context.getApplicationContext();
        this.listener = listener;
    }

    /** Dial lights that are new, drop lights that are gone; the rest keep their links. */
    public void setAddresses(List<String> addresses) {
        Iterator<Map.Entry<String, Member>> it = members.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Member> e = it.next();
            if (!addresses.contains(e.getKey())) {
                e.getValue().light.release();
                it.remove();
            }
        }
        for (String address : addresses) {
            if (!members.containsKey(address)) {
                Member m = new Member(address);
                members.put(address, m);
                m.light.connect(address);
            }
        }
    }

    /**
     * Dial again every light that is down. After a few misses every other dial waits for the
     * light to show up (it may power up after the head unit) instead of giving up after seconds.
     */
    public void redialDown() {
        for (Member m : members.values()) {
            if (m.state == STATE_DOWN) {
                redial(m);
            }
        }
    }

    /** A background scan heard this light: dial it now if it is down. */
    public void heard(String address) {
        Member m = members.get(address);
        if (m != null && m.state == STATE_DOWN) {
            redial(m);
        }
    }

    private void redial(Member m) {
        m.state = STATE_CONNECTING;
        m.light.redial(m.address, waitsForIt(m.misses));
    }

    /** Pure: the 3rd, 5th, 7th… dial in a row waits for the light; the rest are quick dials. */
    static boolean waitsForIt(int misses) {
        return misses >= 3 && misses % 2 == 1;
    }

    /** The most dials in a row any light here has missed; 0 when all are up or dialling for the first time. */
    public int mostMisses() {
        int most = 0;
        for (Member m : members.values()) {
            most = Math.max(most, m.misses);
        }
        return most;
    }

    /** Bluetooth lights that are down (the car's own light has no radio and is left out). */
    public List<String> downLights() {
        List<String> out = new ArrayList<>();
        for (Member m : members.values()) {
            if (m.state == STATE_DOWN && !CarAmbientLight.isCar(m.address)) {
                out.add(m.address);
            }
        }
        return out;
    }

    public List<String> addresses() {
        return new ArrayList<>(members.keySet());
    }

    /** One of {@code STATE_*}, or -1 when the light is not in the group. */
    public int state(String address) {
        Member m = members.get(address);
        return m == null ? -1 : m.state;
    }

    public LightProtocol.Family family(String address) {
        Member m = members.get(address);
        return m == null ? null : m.family;
    }

    /** {@code MoodLight.FAIL_*} of this light's last drop. */
    public int reason(String address) {
        Member m = members.get(address);
        return m == null ? 0 : m.reason;
    }

    public int code(String address) {
        Member m = members.get(address);
        return m == null ? 0 : m.code;
    }

    public int readyCount() {
        int ready = 0;
        for (Member m : members.values()) {
            if (m.state == STATE_READY) {
                ready++;
            }
        }
        return ready;
    }

    public int size() {
        return members.size();
    }

    public void power(boolean on) {
        for (Member m : members.values()) {
            m.light.power(on);
        }
    }

    public void color(int rgb, int brightness) {
        for (Member m : members.values()) {
            m.light.color(rgb, brightness);
        }
    }

    public void show(LightLook look) {
        for (Member m : members.values()) {
            m.light.show(look);
        }
    }

    /** Close every link. The group is spent after this. */
    public void release() {
        for (Member m : members.values()) {
            m.light.release();
        }
        members.clear();
    }
}
