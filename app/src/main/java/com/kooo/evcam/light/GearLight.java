package com.kooo.evcam.light;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Telemetry;
import com.kooo.evcam.telemetry.VehicleState;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * Sets the mood lights from the gear: P, R, D, D at 0 km/h, and colours from a speed up in D
 * ({@link GearLightRules}), each group by its own rules ({@link MoodLightGroups}).
 *
 * <p>Drives every light in a group alike ({@link LightGroup}). Holds a group's connections while
 * the gear is known (the car is awake) and its rules are on; lets go when the gear goes unknown.
 * The mood light screen takes every link for itself ({@link #pause}) and hands them back when it
 * closes. Gear is a core signal, read for as long as the process lives; speed is a float sensor,
 * so this registers as a full telemetry user only while a D rule of some group needs it.</p>
 *
 * <p>Beta: does nothing unless Beta features is on. Main thread only.</p>
 */
public final class GearLight implements Telemetry.Listener {

    private static final String TAG = "GearLight";
    private static final String USER = "gear light";
    /** Wait this long before dialling again after a light dropped or never answered. */
    private static final long REDIAL_MS = 15_000L;
    /** Switched off: time for the power-off packet to go out before the link closes. */
    private static final long OFF_THEN_CLOSE_MS = 600L;

    private static GearLight installed;

    /** One group's links and what was last shown on them. */
    private final class Slot {
        final String id;
        final LightGroup link;
        String pickedKey;
        LightLook shown;

        Slot(String id) {
            this.id = id;
            this.link = new LightGroup(app, address -> onGroupChanged(this, address));
        }
    }

    private final Context app;
    private final AppConfig config;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable redial = this::redial;
    private final Map<String, Slot> slots = new HashMap<>();
    /** Last pick per group, kept while its links are down too (hysteresis needs it). */
    private final Map<String, String> picked = new HashMap<>();

    private MoodLightGroups groups;
    private boolean paused;
    private boolean speedHeld;

    private GearLight(Context app) {
        this.app = app;
        this.config = new AppConfig(app);
        load();
    }

    private void load() {
        groups = MoodLightGroups.load(config, app.getString(R.string.mood_light_group_default));
    }

    /** Application start. Repeat calls do nothing. */
    public static void install(Context context) {
        if (installed != null) {
            return;
        }
        installed = new GearLight(context.getApplicationContext());
        Telemetry.get().addListener(installed);
        installed.update();
    }

    /** The groups, their rules or lights changed: read them again and show what fits now. */
    public static void reload() {
        if (installed == null) {
            return;
        }
        installed.load();
        for (Slot slot : installed.slots.values()) {
            slot.shown = null;
        }
        installed.update();
    }

    /**
     * The master switch (Mood light screen, home card, shortcut). Off turns the connected lights
     * off and stops every group following the gear; on picks them up again.
     */
    public static void setOn(Context context, boolean on) {
        AppConfig config = new AppConfig(context);
        config.setMoodLightOn(on);
        if (installed != null && !on) {
            for (Slot slot : installed.slots.values()) {
                LightGroup link = slot.link;
                link.power(false);
                installed.main.postDelayed(link::release, OFF_THEN_CLOSE_MS);
            }
            installed.slots.clear();
            installed.main.removeCallbacks(installed.redial);
        }
        reload();
    }

    /**
     * Shortcut and home card: flip the master switch and say which way it went. Beta: with Beta
     * features off it only says so.
     */
    public static void toggle(Context context) {
        AppConfig config = new AppConfig(context);
        if (!config.isBetaFeaturesEnabled()) {
            com.kooo.evcam.ui.Notice.makeText(context, R.string.mood_light_needs_beta,
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        boolean on = !config.isMoodLightOn();
        setOn(context, on);
        com.kooo.evcam.ui.Notice.makeText(context, on ? R.string.mood_light_turned_on : R.string.mood_light_turned_off,
                android.widget.Toast.LENGTH_SHORT).show();
    }

    /** The mood light screen wants every link (true) or is done with them (false). */
    public static void pause(boolean on) {
        if (installed == null || installed.paused == on) {
            return;
        }
        installed.paused = on;
        if (on) {
            installed.disconnectAll();
        }
        installed.update();
    }

    @Override
    public void onReadingsChanged(Readings readings) {
        update();
    }

    private void update() {
        VehicleState state = Telemetry.get().latest();
        // Beta: runs only while Settings › Interface › Beta features is on
        boolean running = !paused && state.gear != null && config.isBetaFeaturesEnabled()
                && config.isMoodLightOn() && canConnect();
        boolean wantsSpeed = false;
        for (MoodLightGroups.Group group : groups.groups) {
            GearLightRules.Pick pick = group.rules.pick(state.gear, state.speedKmh, picked.get(group.id));
            wantsSpeed |= pick.wantsSpeed;
            String before = picked.put(group.id, pick.key);
            boolean wanted = running && group.rules.enabled && !group.lights.isEmpty();
            if (!wanted) {
                drop(group.id);
                continue;
            }
            Slot slot = slots.get(group.id);
            if (slot == null) {
                slot = new Slot(group.id);
                slots.put(group.id, slot);
            }
            if (!group.lights.equals(slot.link.addresses())) {
                slot.link.setAddresses(group.lights);
            }
            if (!Objects.equals(before, pick.key)) {
                BlackBox.note("Mood light gear (" + group.name + "): " + before + " -> " + pick.key);
            }
            slot.pickedKey = pick.key;
            apply(slot, pick.look);
        }
        // Groups that were deleted
        Iterator<Map.Entry<String, Slot>> it = slots.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Slot> e = it.next();
            if (groups.find(e.getKey()) == null) {
                e.getValue().link.release();
                it.remove();
            }
        }
        holdSpeed(wantsSpeed && !paused);
    }

    private void apply(Slot slot, LightLook look) {
        if (slot.link.readyCount() == 0 || look == null || look.equals(slot.shown)) {
            return;
        }
        slot.shown = look.copy();
        slot.link.show(look);
    }

    private void holdSpeed(boolean on) {
        if (on == speedHeld) {
            return;
        }
        speedHeld = on;
        if (on) {
            Telemetry.get().acquire(app, USER);
        } else {
            Telemetry.get().release(USER);
        }
    }

    private void drop(String id) {
        Slot slot = slots.remove(id);
        if (slot != null) {
            slot.link.release();
        }
    }

    private void disconnectAll() {
        main.removeCallbacks(redial);
        for (Slot slot : slots.values()) {
            slot.link.release();
        }
        slots.clear();
    }

    private void redial() {
        for (Slot slot : slots.values()) {
            slot.link.redialDown();
        }
    }

    /** A light came up: send its group (and, harmlessly, the rest of it) the current look. One went down: dial it later. */
    private void onGroupChanged(Slot slot, String address) {
        if (slots.get(slot.id) != slot) {
            return;
        }
        int state = slot.link.state(address);
        AppLog.d(TAG, "light " + address + " (" + slot.id + ") state=" + state);
        if (state == LightGroup.STATE_READY) {
            slot.shown = null;
            update();
        } else if (state == LightGroup.STATE_DOWN) {
            main.removeCallbacks(redial);
            main.postDelayed(redial, REDIAL_MS);
        }
    }

    private boolean canConnect() {
        return Build.VERSION.SDK_INT < 31
                || ContextCompat.checkSelfPermission(app, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }
}
