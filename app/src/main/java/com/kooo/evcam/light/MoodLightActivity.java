package com.kooo.evcam.light;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.input.StickerHub;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Experimental screen: find a cheap Bluetooth RGB light, connect it, and set its color.
 *
 * <p>The link is open only while this screen is: these lights take one connection at a time,
 * and holding it would lock out their own phone app.</p>
 */
public class MoodLightActivity extends AppCompatActivity implements LightGroup.Listener {

    private static final int ASK = 48;
    private static final long SCAN_MS = 10_000L;
    /** Classic inquiry first. This radio often returns an empty LE scan until discovery has run. */
    private static final long DISCOVERY_MS = 2_000L;
    private static final int OTHER_LIMIT = 12;
    private static final int[] SWATCHES = {
            0xFF0000, 0xFF6000, 0xFFC000, 0x00FF00, 0x00FFFF, 0x0000FF, 0x8000FF, 0xFF00A0, 0xFFFFFF
    };

    private static final class Seen {
        final String address;
        String name;
        Integer rssi;
        boolean light;

        Seen(String address) {
            this.address = address;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable scanDone = this::stopScan;
    private final Runnable settleDone = this::beginDiscovery;
    private final Runnable discoveryDone = this::startLeScan;
    private final Map<String, Seen> seen = new LinkedHashMap<>();
    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) {
                return;
            }
            if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(intent.getAction())) {
                startLeScan();
                return;
            }
            BluetoothDevice device = deviceExtra(intent);
            short rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
            Integer value = rssi == Short.MIN_VALUE ? null : (int) rssi;
            runOnUiThread(() -> hear(device, null, value, null));
        }
    };
    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            runOnUiThread(() -> addResult(result));
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            runOnUiThread(() -> {
                for (ScanResult result : results) {
                    addResult(result);
                }
            });
        }

        @Override
        public void onScanFailed(int errorCode) {
            runOnUiThread(() -> {
                stopScan();
                status.setText(getString(R.string.sticker_fail, errorCode));
            });
        }
    };

    private AppConfig config;
    private LightGroup light;
    private TextView status;
    private TextView scanButton;
    private View preview;
    private SeekBar hue;
    private SeekBar brightness;
    private TextView brightnessLabel;
    private LinearLayout devices;
    private TextView empty;
    private BluetoothLeScanner scanner;
    private boolean scanning;
    private boolean discovering;
    private boolean leStarted;
    private boolean discoveryListening;
    private boolean ready;
    /** The light whose Connect asked for permission: connected, not scanned for, once it is granted. */
    private String connectAfterGrant;
    private int color;
    private int level;
    private GearLightRules rules;
    private MoodLightGroups groups;
    /** The group this screen shows and drives. */
    private MoodLightGroups.Group group;
    /** True while the switches are set from a group, so their listeners do not save. */
    private boolean binding;
    private final SpeedSimulator sim = new SpeedSimulator();
    /** This group's rules with Change with gear forced on, for Try it; rebuilt when they change. */
    private GearLightRules simRules;
    private String simKey;
    private LightLook simSent;
    private long simLast;
    private final Runnable simTick = this::simTick;
    private LinearLayout groupBar;
    private com.google.android.material.switchmaterial.SwitchMaterial gearSwitch;
    private com.google.android.material.switchmaterial.SwitchMaterial blendSwitch;
    private LinearLayout gearRows;
    private LinearLayout bandRows;
    private View gearRules;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mood_light);
        config = new AppConfig(this);
        status = findViewById(R.id.light_status);
        scanButton = findViewById(R.id.light_scan);
        preview = findViewById(R.id.light_preview);
        hue = findViewById(R.id.light_hue);
        brightness = findViewById(R.id.light_brightness);
        brightnessLabel = findViewById(R.id.light_brightness_label);
        devices = findViewById(R.id.light_devices);
        empty = findViewById(R.id.light_empty);
        groups = MoodLightGroups.load(config, getString(R.string.mood_light_group_default));
        group = groups.find(config.getMoodLightGroupShown());
        if (group == null) {
            group = groups.groups.get(0);
        }
        groupBar = findViewById(R.id.light_groups);
        color = group.color;
        level = group.brightness;
        rules = group.rules;
        gearRows = findViewById(R.id.light_gear_rows);
        bandRows = findViewById(R.id.light_band_rows);
        gearRules = findViewById(R.id.light_gear_rules);

        findViewById(R.id.light_close).setOnClickListener(v -> finish());
        scanButton.setOnClickListener(v -> {
            if (scanning) {
                stopScan();
            } else {
                connectAfterGrant = null;
                if (ensurePermission()) {
                    startScan();
                }
            }
        });
        findViewById(R.id.light_on).setOnClickListener(v -> {
            light.power(true);
            light.color(color, level);
        });
        findViewById(R.id.light_off).setOnClickListener(v -> light.power(false));
        addSwatches(findViewById(R.id.light_swatches));
        hue.setOnSeekBarChangeListener(new Slider() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    pick(LightProtocol.hue(progress));
                }
            }
        });
        brightness.setProgress(level);
        brightness.setOnSeekBarChangeListener(new Slider() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    level = progress;
                    send();
                }
            }
        });
        showColor();
        gearSwitch = findViewById(R.id.light_gear_switch);
        gearSwitch.setOnCheckedChangeListener((button, on) -> {
            if (!binding) {
                rules.enabled = on;
                saveRules();
            }
        });
        blendSwitch = findViewById(R.id.light_band_blend);
        blendSwitch.setOnCheckedChangeListener((button, on) -> {
            if (!binding) {
                rules.blend = on;
                saveRules();
            }
        });
        findViewById(R.id.light_band_add).setOnClickListener(v -> addBand());
        setupSim();
        setupPages();
        com.google.android.material.switchmaterial.SwitchMaterial master = findViewById(R.id.light_master);
        master.setChecked(config.isMoodLightOn());
        // This screen holds the lights, so it turns its own off; GearLight takes the setting when the screen closes
        master.setOnCheckedChangeListener((button, on) -> {
            GearLight.setOn(this, on);
            if (!on && ready) {
                light.power(false);
            }
            renderGroups();
        });
        findViewById(R.id.light_group_rename).setOnClickListener(v -> renameGroup());
        findViewById(R.id.light_group_delete).setOnClickListener(v -> deleteGroup());
        bindGroup();
    }

    // =================================================================== groups

    /** Show the open group: its colour, rules and lights. */
    private void bindGroup() {
        color = group.color;
        level = group.brightness;
        rules = group.rules;
        binding = true;
        gearSwitch.setChecked(rules.enabled);
        blendSwitch.setChecked(rules.blend);
        binding = false;
        brightness.setProgress(level);
        showColor();
        renderGroups();
        renderGear();
        simRules = null;
        simKey = null;
        renderSim();
    }

    // =================================================================== pages

    private static final int[] PAGES = {R.id.light_page_color, R.id.light_page_gear, R.id.light_page_try};
    private static final int[] PAGE_NAMES = {R.string.mood_light_tab_color, R.string.mood_light_tab_gear,
            R.string.mood_light_sim_title};

    private void setupPages() {
        LinearLayout tabs = findViewById(R.id.light_tabs);
        int gap = Math.round(8 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < PAGES.length; i++) {
            com.google.android.material.button.MaterialButton tab = (com.google.android.material.button.MaterialButton)
                    LayoutInflater.from(this).inflate(R.layout.item_group_button, tabs, false);
            tab.setText(PAGE_NAMES[i]);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                    tab.getLayoutParams().height, 1f);
            params.setMarginEnd(i < PAGES.length - 1 ? gap : 0);
            tab.setLayoutParams(params);
            int page = i;
            tab.setOnClickListener(v -> showPage(page));
            tabs.addView(tab);
        }
        showPage(Math.max(0, Math.min(PAGES.length - 1, config.getMoodLightPage())));
    }

    private void showPage(int page) {
        config.setMoodLightPage(page);
        LinearLayout tabs = findViewById(R.id.light_tabs);
        for (int i = 0; i < PAGES.length; i++) {
            boolean on = i == page;
            findViewById(PAGES[i]).setVisibility(on ? View.VISIBLE : View.GONE);
            com.google.android.material.button.MaterialButton tab =
                    (com.google.android.material.button.MaterialButton) tabs.getChildAt(i);
            tab.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, on ? R.color.energy : R.color.line)));
            tab.setTextColor(ContextCompat.getColor(this, on ? R.color.on_energy : R.color.text_primary));
        }
        if (page != 2) {
            // Leaving Try it lets go of the pedals
            sim.setThrottle(false);
            sim.setBrake(false);
        }
    }

    // =================================================================== try it

    private static final String[] SIM_GEARS = {"P", "R", "N", "D"};

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void setupSim() {
        LinearLayout gears = findViewById(R.id.light_sim_gears);
        int gap = Math.round(8 * getResources().getDisplayMetrics().density);
        for (String g : SIM_GEARS) {
            com.google.android.material.button.MaterialButton b = (com.google.android.material.button.MaterialButton)
                    LayoutInflater.from(this).inflate(R.layout.item_group_button, gears, false);
            b.setText(g);
            b.setTag(g);
            ((LinearLayout.LayoutParams) b.getLayoutParams()).setMarginEnd(gap);
            b.setOnClickListener(v -> {
                sim.setGear(g);
                renderSim();
            });
            gears.addView(b);
        }
        com.google.android.material.button.MaterialButton throttle = findViewById(R.id.light_sim_throttle);
        com.google.android.material.button.MaterialButton brake = findViewById(R.id.light_sim_brake);
        // On the card's grey a plain button vanishes: pedals in the accent and the recording red
        throttle.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.energy)));
        throttle.setTextColor(ContextCompat.getColor(this, R.color.on_energy));
        brake.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.recording)));
        brake.setTextColor(ContextCompat.getColor(this, R.color.on_energy));
        throttle.setOnTouchListener((v, e) -> pedal(v, e, true));
        brake.setOnTouchListener((v, e) -> pedal(v, e, false));
    }

    /** Held down = pedal pressed; the timer runs while anything moves. */
    private boolean pedal(View v, android.view.MotionEvent e, boolean throttle) {
        int action = e.getActionMasked();
        boolean down = action == android.view.MotionEvent.ACTION_DOWN;
        if (!down && action != android.view.MotionEvent.ACTION_UP && action != android.view.MotionEvent.ACTION_CANCEL) {
            return true;
        }
        v.setPressed(down);
        if (throttle) {
            sim.setThrottle(down);
        } else {
            sim.setBrake(down);
        }
        if (down) {
            startSim();
        }
        return true;
    }

    private void startSim() {
        handler.removeCallbacks(simTick);
        simLast = android.os.SystemClock.uptimeMillis();
        handler.post(simTick);
    }

    private void simTick() {
        long now = android.os.SystemClock.uptimeMillis();
        sim.step(now - simLast);
        simLast = now;
        renderSim();
        if (sim.moving()) {
            handler.postDelayed(simTick, 50);
        }
    }

    private void renderSim() {
        if (simRules == null) {
            simRules = GearLightRules.parse(rules.toJson());
            simRules.enabled = true;
        }
        float speed = sim.speedKmh();
        GearLightRules.Pick pick = simRules.pick(sim.gear(), speed, simKey);
        simKey = pick.key;
        LinearLayout gears = findViewById(R.id.light_sim_gears);
        for (int i = 0; i < gears.getChildCount(); i++) {
            com.google.android.material.button.MaterialButton b =
                    (com.google.android.material.button.MaterialButton) gears.getChildAt(i);
            boolean on = sim.gear().equals(b.getTag());
            b.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, on ? R.color.energy : R.color.line)));
            b.setTextColor(ContextCompat.getColor(this, on ? R.color.on_energy : R.color.text_primary));
        }
        ((TextView) findViewById(R.id.light_sim_speed)).setText(String.valueOf(Math.round(speed)));
        ((TextView) findViewById(R.id.light_sim_rule)).setText(simLabel(pick));
        if (pick.look != null) {
            ((EffectSwatchView) findViewById(R.id.light_sim_strip)).show(pick.look);
            if (ready && !pick.look.equals(simSent)) {
                simSent = pick.look.copy();
                light.show(pick.look);
            }
        }
    }

    /** Which rule is showing, in the words of the rows above. */
    private String simLabel(GearLightRules.Pick pick) {
        if (pick.key == null) {
            return getString(R.string.mood_light_sim_n);
        }
        String label;
        if (pick.key.startsWith("band:")) {
            String rest = pick.key.substring("band:".length());
            int cut = rest.indexOf('~');
            int index = Integer.parseInt(cut < 0 ? rest : rest.substring(0, cut));
            int from = simRules.bands.get(index).fromKmh;
            if (cut >= 0 && index + 1 < simRules.bands.size()) {
                int step = Integer.parseInt(rest.substring(cut + 1));
                label = getString(R.string.mood_light_sim_blend, from, simRules.bands.get(index + 1).fromKmh,
                        step * 100 / GearLightRules.BLEND_STEPS);
            } else {
                label = getString(R.string.mood_light_band, from);
            }
        } else {
            int i = java.util.Arrays.asList(GEAR_KEYS).indexOf(pick.key);
            label = i < 0 ? pick.key : getString(GEAR_LABELS[i]);
        }
        return pick.look == null ? getString(R.string.mood_light_sim_leave, label) : label;
    }

    private void renderGroups() {
        groupBar.removeAllViews();
        int gap = Math.round(8 * getResources().getDisplayMetrics().density);
        for (MoodLightGroups.Group g : groups.groups) {
            com.google.android.material.button.MaterialButton tab = groupButton(
                    getString(R.string.mood_light_tab, g.name, g.lights.size()), gap);
            if (g == group) {
                tab.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.energy)));
                tab.setTextColor(ContextCompat.getColor(this, R.color.on_energy));
            }
            tab.setOnClickListener(v -> openGroup(g));
            groupBar.addView(tab);
        }
        if (groups.groups.size() < MoodLightGroups.MAX_GROUPS) {
            com.google.android.material.button.MaterialButton add = groupButton(getString(R.string.mood_light_group_new), gap);
            add.setIconResource(R.drawable.ic_add);
            add.setOnClickListener(v -> newGroup());
            groupBar.addView(add);
        }
        TextView name = findViewById(R.id.light_group_name);
        TextView summary = findViewById(R.id.light_group_summary);
        name.setText(group.name);
        String gear = getString(!config.isMoodLightOn() ? R.string.mood_light_group_master_off
                : group.rules.enabled ? R.string.mood_light_group_gear_on : R.string.mood_light_group_gear_off);
        summary.setText(group.lights.isEmpty() ? getString(R.string.mood_light_group_empty)
                : getResources().getQuantityString(R.plurals.mood_light_group_summary,
                        group.lights.size(), group.lights.size(), gear));
        View delete = findViewById(R.id.light_group_delete);
        delete.setVisibility(groups.groups.size() > 1 ? View.VISIBLE : View.GONE);
    }

    private com.google.android.material.button.MaterialButton groupButton(String text, int gap) {
        com.google.android.material.button.MaterialButton b = (com.google.android.material.button.MaterialButton) LayoutInflater.from(this)
                .inflate(R.layout.item_group_button, groupBar, false);
        b.setText(text);
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) b.getLayoutParams();
        params.setMarginEnd(gap);
        return b;
    }

    private void openGroup(MoodLightGroups.Group g) {
        group = g;
        config.setMoodLightGroupShown(g.id);
        ready = false;
        if (light != null && canConnect()) {
            light.setAddresses(group.lights);
            ready = light.readyCount() > 0;
        }
        bindGroup();
        showStatus(null);
        showControls();
        render();
    }

    private void saveGroups() {
        groups.save(config);
        GearLight.reload();
        renderGroups();
        simRules = null;
        simKey = null;
        renderSim();
    }

    private void newGroup() {
        if (groups.groups.size() >= MoodLightGroups.MAX_GROUPS) {
            status.setText(getString(R.string.mood_light_group_max, MoodLightGroups.MAX_GROUPS));
            return;
        }
        askName(getString(R.string.mood_light_group_new),
                getString(R.string.mood_light_group_new_name, groups.groups.size() + 1), name -> {
                    MoodLightGroups.Group g = groups.add(name);
                    saveGroups();
                    openGroup(g);
                });
    }

    private void renameGroup() {
        askName(getString(R.string.mood_light_group_rename), group.name, name -> {
            group.name = name;
            saveGroups();
            renderGroups();
            render();
        });
    }

    private void deleteGroup() {
        if (groups.groups.size() <= 1) {
            status.setText(R.string.mood_light_group_last);
            return;
        }
        com.kooo.evcam.ui.CamDialogs.showDestructive(new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.mood_light_group_delete)
                .setMessage(getString(R.string.mood_light_group_delete_ask, group.name))
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    groups.remove(group.id);
                    saveGroups();
                    openGroup(groups.groups.get(0));
                })
                .setNegativeButton(android.R.string.cancel, null));
    }

    private interface Named {
        void named(String name);
    }

    private void askName(String title, String start, Named done) {
        android.widget.EditText field = com.kooo.evcam.ui.CamDialogs.input(this);
        field.setText(start);
        field.setSelectAllOnFocus(true);
        field.setHint(R.string.mood_light_group_name);
        field.setSingleLine(true);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 3, pad, 0);
        box.addView(field);
        com.kooo.evcam.ui.CamDialogs.show(new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(R.string.action_save, (dialog, which) -> {
                    String name = field.getText().toString().trim();
                    done.named(name.isEmpty() ? start : name);
                })
                .setNegativeButton(android.R.string.cancel, null));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // One connection at a time: take the light from the gear rules while this screen is up
        GearLight.pause(true);
        light = new LightGroup(this, this);
        ready = false;
        showControls();
        if (canConnect()) {
            light.setAddresses(group.lights);
        }
        render();
    }

    @Override
    protected void onPause() {
        sim.setThrottle(false);
        sim.setBrake(false);
        handler.removeCallbacks(simTick);
        stopScan();
        // Always give the radio back, even if the scan already ended.
        StickerHub.holdForScan(this, false);
        light.release();
        GearLight.pause(false);
        super.onPause();
    }

    // =================================================================== light

    @Override
    public void onGroupChanged(String address) {
        ready = light.readyCount() > 0;
        LightProtocol.Family family = light.family(address);
        if (family != null) {
            config.setMoodLightFamily(family.name());
        }
        showStatus(address);
        showControls();
        render();
    }

    /** One line for the whole group; a drop names its reason when nothing is up. */
    private void showStatus(String changed) {
        int size = light.size();
        int up = light.readyCount();
        if (size == 0) {
            status.setText(R.string.mood_light_pick);
        } else if (up > 0) {
            LightProtocol.Family family = light.family(light.addresses().get(0));
            if (size == 1 && family != null) {
                status.setText(getString(R.string.mood_light_ready, getString(familyLabel(family))));
            } else {
                status.setText(getString(R.string.mood_light_group_ready, up, size));
            }
        } else if (CarAmbientLight.isCar(changed) && light.state(changed) == LightGroup.STATE_DOWN) {
            status.setText(R.string.mood_light_car_missing);
        } else if (changed != null && light.state(changed) == LightGroup.STATE_DOWN) {
            showDown(light.reason(changed), light.code(changed));
        } else {
            status.setText(R.string.mood_light_connecting);
        }
    }

    private void showDown(int reason, int code) {
        switch (reason) {
            case MoodLight.FAIL_BLUETOOTH:
                status.setText(R.string.sticker_bluetooth_off);
                break;
            case MoodLight.FAIL_TIMEOUT:
                status.setText(R.string.mood_light_timeout);
                break;
            case MoodLight.FAIL_NOT_A_LIGHT:
                status.setText(R.string.mood_light_not_light);
                break;
            case MoodLight.FAIL_GATT:
                status.setText(getString(R.string.sticker_fail, code));
                break;
            default:
                status.setText(R.string.mood_light_down);
                break;
        }
    }

    private void addSwatches(LinearLayout row) {
        int size = getResources().getDimensionPixelSize(R.dimen.icon_button_size);
        for (int rgb : SWATCHES) {
            View swatch = new View(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.setMarginEnd(size / 4);
            swatch.setLayoutParams(params);
            swatch.setBackgroundResource(R.drawable.sticker_dot);
            swatch.setBackgroundTintList(ColorStateList.valueOf(0xFF000000 | rgb));
            swatch.setContentDescription(getString(R.string.mood_light_swatch,
                    String.format(Locale.US, "#%06X", rgb)));
            swatch.setOnClickListener(v -> pick(rgb));
            row.addView(swatch);
        }
    }

    private void pick(int rgb) {
        color = rgb;
        send();
    }

    private void send() {
        group.color = color;
        group.brightness = level;
        groups.save(config);
        showColor();
        if (ready) {
            light.color(color, level);
        }
    }

    private void showColor() {
        preview.setBackgroundTintList(ColorStateList.valueOf(0xFF000000 | color));
        brightnessLabel.setText(getString(R.string.mood_light_brightness, level));
    }

    /**
     * Colour and brightness stay usable without a light (they are saved for the group); only On and
     * Off need one, and a note says so instead of greying the whole page out.
     */
    private void showControls() {
        findViewById(R.id.light_connect_hint).setVisibility(ready ? View.GONE : View.VISIBLE);
        for (int id : new int[]{R.id.light_on, R.id.light_off}) {
            View b = findViewById(id);
            b.setEnabled(ready);
            b.setAlpha(ready ? 1f : 0.4f);
        }
    }

    private static int familyLabel(LightProtocol.Family family) {
        return family == LightProtocol.Family.TRIONES
                ? R.string.mood_light_family_triones
                : R.string.mood_light_family_elk;
    }

    private abstract static class Slider implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar bar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar bar) {
        }
    }

    // =================================================================== gear

    private static final String[] GEAR_KEYS = {
            GearLightRules.KEY_P, GearLightRules.KEY_R, GearLightRules.KEY_D, GearLightRules.KEY_D_STOPPED
    };
    private static final int[] GEAR_LABELS = {
            R.string.mood_light_gear_p, R.string.mood_light_gear_r,
            R.string.mood_light_gear_d, R.string.mood_light_gear_d0
    };

    private void saveRules() {
        saveGroups();
        renderGroups();
        renderGear();
    }

    private void renderGear() {
        gearRules.setAlpha(rules.enabled ? 1f : 0.5f);
        gearRows.removeAllViews();
        for (int i = 0; i < GEAR_KEYS.length; i++) {
            String key = GEAR_KEYS[i];
            int label = GEAR_LABELS[i];
            LightLook look = rules.look(key);
            int unset = GearLightRules.KEY_D_STOPPED.equals(key)
                    ? R.string.mood_light_gear_d0_unset : R.string.mood_light_gear_unset;
            addLookRow(gearRows, getString(label), look, unset, v -> editLook(getString(label), look, -1,
                    picked -> {
                        rules.setLook(key, picked);
                        saveRules();
                    }));
        }
        bandRows.removeAllViews();
        for (int i = 0; i < rules.bands.size(); i++) {
            GearLightRules.Band band = rules.bands.get(i);
            int index = i;
            String title = getString(R.string.mood_light_band, band.fromKmh);
            addLookRow(bandRows, title, band.look, R.string.mood_light_gear_unset,
                    v -> editBand(index, band));
        }
    }

    private void addLookRow(LinearLayout parent, String name, LightLook look, int unset, View.OnClickListener edit) {
        View line = LayoutInflater.from(this).inflate(R.layout.item_sticker, parent, false);
        TextView title = line.findViewById(R.id.sticker_name);
        TextView meta = line.findViewById(R.id.sticker_meta);
        TextView action = line.findViewById(R.id.sticker_action);
        View dot = line.findViewById(R.id.sticker_dot);
        title.setText(name);
        meta.setText(look == null ? getString(unset) : describe(look));
        // Swap the row's plain dot for one that plays the look
        EffectSwatchView playing = new EffectSwatchView(this);
        playing.setLayoutParams(dot.getLayoutParams());
        android.view.ViewGroup holder = (android.view.ViewGroup) dot.getParent();
        int at = holder.indexOfChild(dot);
        holder.removeViewAt(at);
        holder.addView(playing, at);
        playing.show(look == null ? LightProtocol.Effect.OFF : look.effect(),
                look == null ? 0 : look.color, look == null ? 0 : look.speed);
        action.setText(R.string.mood_light_gear_edit);
        action.setOnClickListener(edit);
        parent.addView(line);
    }

    private String describe(LightLook look) {
        LightProtocol.Effect effect = look.effect();
        String name = getString(effectLabel(effect));
        if (effect == LightProtocol.Effect.OFF) {
            return name;
        }
        return getString(R.string.mood_light_look_meta, name, look.brightness);
    }

    private static int effectLabel(LightProtocol.Effect effect) {
        switch (effect) {
            case BREATHE: return R.string.mood_light_effect_breathe;
            case BLINK: return R.string.mood_light_effect_blink;
            case FADE_ALL: return R.string.mood_light_effect_fade_all;
            case JUMP_ALL: return R.string.mood_light_effect_jump_all;
            case STROBE_ALL: return R.string.mood_light_effect_strobe_all;
            case OFF: return R.string.mood_light_effect_off;
            default: return R.string.mood_light_effect_static;
        }
    }

    private void addBand() {
        if (rules.bands.size() >= GearLightRules.MAX_BANDS) {
            status.setText(getString(R.string.mood_light_band_full, GearLightRules.MAX_BANDS));
            return;
        }
        int from = rules.bands.isEmpty() ? 60 : rules.bands.get(rules.bands.size() - 1).fromKmh + 20;
        editBand(-1, new GearLightRules.Band(Math.min(from, 250),
                new LightLook(0xFF6000, 80, LightProtocol.Effect.STATIC, 50)));
    }

    private void editBand(int index, GearLightRules.Band band) {
        String title = index < 0 ? getString(R.string.mood_light_band_add)
                : getString(R.string.mood_light_band, band.fromKmh);
        editLook(title, band.look, band.fromKmh, (picked, from) -> {
            if (picked == null) {
                if (index >= 0) {
                    rules.bands.remove(index);
                }
            } else if (index < 0) {
                rules.bands.add(new GearLightRules.Band(from, picked));
            } else {
                band.fromKmh = from;
                band.look = picked;
            }
            saveRules();
        });
    }

    private interface LookPicked {
        /** @param look null for "leave as is" */
        void picked(LightLook look);
    }

    private interface BandPicked {
        /** @param look null for "remove" */
        void picked(LightLook look, int fromKmh);
    }

    private void editLook(String title, LightLook start, int fromKmh, LookPicked done) {
        editLook(title, start, fromKmh, (look, from) -> done.picked(look));
    }

    /**
     * One look, previewed on the light as it changes when it is connected.
     *
     * @param fromKmh a speed colour's start, or -1 for a gear (its neutral button then means "leave as is")
     */
    private void editLook(String title, LightLook start, int fromKmh, BandPicked done) {
        LightLook look = start == null ? new LightLook(color, level, LightProtocol.Effect.STATIC, 50) : start.copy();
        boolean band = fromKmh >= 0;
        View body = LayoutInflater.from(this).inflate(R.layout.dialog_light_look, null, false);
        EffectSwatchView preview = body.findViewById(R.id.look_preview);
        TextView effectButton = body.findViewById(R.id.look_effect);
        SeekBar hueBar = body.findViewById(R.id.look_hue);
        SeekBar brightnessBar = body.findViewById(R.id.look_brightness);
        TextView brightnessText = body.findViewById(R.id.look_brightness_label);
        SeekBar speedBar = body.findViewById(R.id.look_speed);
        TextView speedText = body.findViewById(R.id.look_speed_label);
        TextView basicNote = body.findViewById(R.id.look_basic_note);
        android.widget.EditText from = body.findViewById(R.id.look_from);
        if (band) {
            body.findViewById(R.id.look_from_row).setVisibility(View.VISIBLE);
            from.setText(String.valueOf(fromKmh));
        }
        Runnable refresh = () -> {
            LightProtocol.Effect effect = look.effect();
            boolean off = effect == LightProtocol.Effect.OFF;
            boolean animated = !off && effect != LightProtocol.Effect.STATIC;
            preview.show(look);
            effectButton.setText(LightLook.ANIMATIONS
                    ? getString(R.string.mood_light_effect, getString(effectLabel(effect)))
                    : getString(effectLabel(effect)));
            brightnessText.setText(getString(R.string.mood_light_brightness, look.brightness));
            speedText.setText(getString(R.string.mood_light_effect_speed, look.speed));
            int speedShown = animated ? View.VISIBLE : View.GONE;
            speedText.setVisibility(speedShown);
            speedBar.setVisibility(speedShown);
            basicNote.setVisibility(effect == LightProtocol.Effect.BREATHE
                    || effect == LightProtocol.Effect.BLINK ? View.VISIBLE : View.GONE);
            if (ready) {
                light.show(look);
            }
        };
        LinearLayout swatches = body.findViewById(R.id.look_swatches);
        int size = getResources().getDimensionPixelSize(R.dimen.icon_button_size);
        for (int rgb : SWATCHES) {
            View swatch = new View(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.setMarginEnd(size / 4);
            swatch.setLayoutParams(params);
            swatch.setBackgroundResource(R.drawable.sticker_dot);
            swatch.setBackgroundTintList(ColorStateList.valueOf(0xFF000000 | rgb));
            swatch.setContentDescription(getString(R.string.mood_light_swatch,
                    String.format(Locale.US, "#%06X", rgb)));
            swatch.setOnClickListener(v -> {
                look.color = rgb;
                refresh.run();
            });
            swatches.addView(swatch);
        }
        hueBar.setOnSeekBarChangeListener(new Slider() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    look.color = LightProtocol.hue(progress);
                    refresh.run();
                }
            }
        });
        brightnessBar.setProgress(look.brightness);
        brightnessBar.setOnSeekBarChangeListener(new Slider() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    look.brightness = progress;
                    refresh.run();
                }
            }
        });
        speedBar.setProgress(look.speed);
        speedBar.setOnSeekBarChangeListener(new Slider() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    look.speed = progress;
                    refresh.run();
                }
            }
        });
        effectButton.setOnClickListener(v -> {
            if (!LightLook.ANIMATIONS) {
                // Only a colour or Off: one tap flips between them
                look.effect = (look.effect() == LightProtocol.Effect.OFF
                        ? LightProtocol.Effect.STATIC : LightProtocol.Effect.OFF).name();
                refresh.run();
                return;
            }
            pickEffect(look, picked -> {
                look.effect = picked.name();
                refresh.run();
            });
        });
        refresh.run();
        com.google.android.material.dialog.MaterialAlertDialogBuilder builder =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                        .setTitle(title)
                        .setView(body)
                        .setPositiveButton(R.string.action_save, (dialog, which) ->
                                done.picked(look, band ? parseKmh(from, fromKmh) : -1))
                        .setNeutralButton(band ? R.string.mood_light_band_remove : R.string.mood_light_gear_leave,
                                (dialog, which) -> done.picked(null, fromKmh))
                        .setNegativeButton(android.R.string.cancel, null)
                        // The preview changed the light: put this screen's own colour back
                        .setOnDismissListener(dialog -> {
                            if (ready) {
                                light.color(color, level);
                            }
                        });
        com.kooo.evcam.ui.CamDialogs.show(builder);
    }

    private interface EffectPicked {
        void picked(LightProtocol.Effect effect);
    }

    /** Effects as a list, each with a dot playing it in this look's colour and speed. */
    private void pickEffect(LightLook look, EffectPicked done) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        list.setPadding(pad, pad / 2, pad, 0);
        androidx.appcompat.app.AlertDialog[] shown = new androidx.appcompat.app.AlertDialog[1];
        for (LightProtocol.Effect effect : LightProtocol.Effect.values()) {
            if (!LightLook.ANIMATIONS && LightLook.animated(effect)) {
                continue;
            }
            View row = LayoutInflater.from(this).inflate(R.layout.item_effect_row, list, false);
            EffectSwatchView dot = row.findViewById(R.id.effect_dot);
            TextView name = row.findViewById(R.id.effect_name);
            dot.show(effect, look.color, look.speed);
            name.setText(effectLabel(effect));
            row.setSelected(effect == look.effect());
            name.setTextColor(ContextCompat.getColor(this,
                    effect == look.effect() ? R.color.text_primary : R.color.text_secondary));
            name.setTypeface(null, effect == look.effect() ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            row.setOnClickListener(v -> {
                if (shown[0] != null) {
                    shown[0].dismiss();
                }
                done.picked(effect);
            });
            list.addView(row);
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(list);
        shown[0] = com.kooo.evcam.ui.CamDialogs.show(new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.mood_light_effect_pick)
                .setView(scroll)
                .setNegativeButton(android.R.string.cancel, null));
    }

    private static int parseKmh(android.widget.EditText field, int fallback) {
        try {
            return Math.max(1, Math.min(250, Integer.parseInt(field.getText().toString().trim())));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // =================================================================== list

    private void render() {
        devices.removeAllViews();
        List<String> mine = group.lights;
        int shown = 0;
        ((TextView) findViewById(R.id.light_lights_title)).setText(getString(R.string.mood_light_lights_in, group.name));
        for (String address : mine) {
            Seen row = seen.get(address);
            addRow(address, row == null ? null : row.name, row == null ? null : row.rssi);
            shown++;
        }
        // Lights in other groups, then lights found by the scan
        for (MoodLightGroups.Group g : groups.groups) {
            if (g == group) {
                continue;
            }
            for (String address : g.lights) {
                Seen row = seen.get(address);
                addRow(address, row == null ? null : row.name, row == null ? null : row.rssi);
                shown++;
            }
        }
        // The car's own ambient light is always on offer, before the lights the scan found
        if (groups.owner(CarAmbientLight.ADDRESS) == null) {
            addRow(CarAmbientLight.ADDRESS, null, null);
            shown++;
        }
        for (Seen row : seen.values()) {
            if (row.light && groups.owner(row.address) == null) {
                addRow(row.address, row.name, row.rssi);
                shown++;
            }
        }
        int others = 0;
        for (Seen row : seen.values()) {
            if (row.light || groups.owner(row.address) != null || row.name == null || row.name.isEmpty()) {
                continue;
            }
            if (others == 0) {
                TextView header = new TextView(this);
                header.setText(R.string.mood_light_other);
                header.setTextAppearance(R.style.TextAppearance_Cam_Caption);
                header.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary));
                header.setPadding(16, 16, 16, 8);
                devices.addView(header);
            }
            addRow(row.address, row.name, row.rssi);
            if (++others >= OTHER_LIMIT) {
                break;
            }
        }
        empty.setVisibility(shown == 0 ? View.VISIBLE : View.GONE);
    }

    private void addRow(String address, String name, Integer rssi) {
        MoodLightGroups.Group owner = groups.owner(address);
        boolean inGroup = owner == group;
        View line = LayoutInflater.from(this).inflate(R.layout.item_sticker, devices, false);
        TextView title = line.findViewById(R.id.sticker_name);
        TextView meta = line.findViewById(R.id.sticker_meta);
        TextView action = line.findViewById(R.id.sticker_action);
        View dot = line.findViewById(R.id.sticker_dot);
        boolean car = CarAmbientLight.isCar(address);
        if (car) {
            title.setText(R.string.mood_light_car_name);
        } else {
            title.setText(name == null || name.isEmpty() ? getString(R.string.sticker_unnamed) : name);
        }
        String detail = car ? getString(R.string.mood_light_car_meta)
                : rssi == null ? address : getString(R.string.sticker_device_meta, address, rssi);
        int state = light == null ? -1 : light.state(address);
        if (inGroup) {
            int word = state == LightGroup.STATE_READY ? R.string.sticker_connected
                    : state == LightGroup.STATE_CONNECTING ? R.string.mood_light_connecting
                    : car ? R.string.mood_light_car_missing
                    : R.string.sticker_link_off;
            detail = detail + "  " + getString(word);
        } else if (owner != null) {
            detail = detail + "  " + getString(R.string.mood_light_in_group, owner.name);
        }
        meta.setText(detail);
        dot.setVisibility(inGroup ? View.VISIBLE : View.GONE);
        dot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this,
                inGroup && state == LightGroup.STATE_READY ? R.color.energy : R.color.text_tertiary)));
        action.setText(inGroup ? R.string.mood_light_remove
                : owner != null ? R.string.mood_light_move_here
                : group.lights.isEmpty() ? R.string.sticker_connect : R.string.mood_light_add);
        action.setOnClickListener(v -> {
            if (inGroup) {
                group.lights.remove(address);
                saveGroups();
                light.setAddresses(group.lights);
                ready = light.readyCount() > 0;
                showStatus(null);
                showControls();
                render();
            } else if (car) {
                // Not Bluetooth: no permission to ask, and it does not count towards the limit
                connectTo(address);
            } else if (owner == null && groups.lightCount() >= LightGroup.MAX_LIGHTS) {
                status.setText(getString(R.string.mood_light_group_full, LightGroup.MAX_LIGHTS));
            } else {
                connectAfterGrant = address;
                if (ensurePermission()) {
                    connectAfterGrant = null;
                    connectTo(address);
                }
            }
        });
        devices.addView(line);
    }

    /** Put this light in the open group (out of any other) and dial it; the others keep their links. */
    private void connectTo(String address) {
        stopScan();
        if (groups.owner(address) != null || CarAmbientLight.isCar(address)
                || groups.lightCount() < LightGroup.MAX_LIGHTS) {
            groups.moveLight(address, group);
            saveGroups();
        }
        light.setAddresses(group.lights);
        render();
    }

    // =================================================================== scan

    private boolean canConnect() {
        return Build.VERSION.SDK_INT < 31
                || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean ensurePermission() {
        List<String> missing = new ArrayList<>();
        for (String permission : needed()) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        if (missing.isEmpty()) {
            return true;
        }
        requestPermissions(missing.toArray(new String[0]), ASK);
        return false;
    }

    private static String[] needed() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            };
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != ASK) {
            return;
        }
        String waiting = connectAfterGrant;
        connectAfterGrant = null;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                status.setText(R.string.sticker_need_permission);
                return;
            }
        }
        if (waiting != null) {
            // The prompt paused the screen, which ended its link. Saving the address is what counts:
            // if this lands before onResume, onResume dials the saved light; if after, this dials it
            connectTo(waiting);
        } else {
            startScan();
        }
    }

    private void startScan() {
        BluetoothAdapter adapter = adapter();
        if (adapter == null || !adapter.isEnabled() || adapter.getBluetoothLeScanner() == null) {
            status.setText(R.string.sticker_bluetooth_off);
            return;
        }
        haltScan();
        scanning = true;
        scanButton.setText(R.string.sticker_scanning);
        status.setText(R.string.sticker_scanning);
        // This head unit hears no scan while a sticker link is up: free the radio first.
        StickerHub.holdForScan(this, true);
        handler.postDelayed(settleDone, 700);
    }

    private void beginDiscovery() {
        if (!scanning || discovering || leStarted) {
            return;
        }
        BluetoothAdapter adapter = adapter();
        if (adapter == null || !adapter.isEnabled()) {
            status.setText(R.string.sticker_bluetooth_off);
            stopScan();
            return;
        }
        listenDiscovery();
        discovering = true;
        boolean started = false;
        try {
            adapter.cancelDiscovery();
            started = adapter.startDiscovery();
        } catch (SecurityException e) {
            status.setText(R.string.sticker_need_permission);
        }
        if (!started) {
            startLeScan();
            return;
        }
        handler.postDelayed(discoveryDone, DISCOVERY_MS);
    }

    private void startLeScan() {
        if (!scanning || leStarted) {
            return;
        }
        leStarted = true;
        discovering = false;
        handler.removeCallbacks(discoveryDone);
        cancelClassic();
        BluetoothAdapter adapter = adapter();
        BluetoothLeScanner next = adapter == null ? null : adapter.getBluetoothLeScanner();
        if (next == null) {
            status.setText(R.string.sticker_bluetooth_off);
            stopScan();
            return;
        }
        scanner = next;
        try {
            scanner.startScan(null, new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0)
                    .build(), scanCallback);
        } catch (SecurityException e) {
            status.setText(R.string.sticker_need_permission);
            stopScan();
            return;
        }
        handler.postDelayed(scanDone, SCAN_MS);
    }

    private void stopScan() {
        boolean live = scanning || discovering || leStarted;
        haltScan();
        if (live) {
            StickerHub.holdForScan(this, false);
            if (!ready) {
                status.setText(R.string.mood_light_pick);
            }
        }
    }

    private void haltScan() {
        handler.removeCallbacks(settleDone);
        handler.removeCallbacks(discoveryDone);
        handler.removeCallbacks(scanDone);
        cancelClassic();
        unlistenDiscovery();
        if (scanner != null && leStarted) {
            try {
                scanner.stopScan(scanCallback);
            } catch (SecurityException ignored) {
                // scan permission gone
            }
        }
        scanning = false;
        discovering = false;
        leStarted = false;
        if (scanButton != null) {
            scanButton.setText(R.string.sticker_scan);
        }
    }

    private void listenDiscovery() {
        if (discoveryListening) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(discoveryReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(discoveryReceiver, filter);
        }
        discoveryListening = true;
    }

    private void unlistenDiscovery() {
        if (!discoveryListening) {
            return;
        }
        try {
            unregisterReceiver(discoveryReceiver);
        } catch (IllegalArgumentException ignored) {
            // already gone
        }
        discoveryListening = false;
    }

    private void cancelClassic() {
        BluetoothAdapter adapter = adapter();
        if (adapter == null) {
            return;
        }
        try {
            adapter.cancelDiscovery();
        } catch (SecurityException ignored) {
            // scan permission gone
        }
    }

    private void addResult(ScanResult result) {
        ScanRecord record = result.getScanRecord();
        String name = record == null ? null : record.getDeviceName();
        List<UUID> services = new ArrayList<>();
        if (record != null && record.getServiceUuids() != null) {
            for (ParcelUuid uuid : record.getServiceUuids()) {
                services.add(uuid.getUuid());
            }
        }
        hear(result.getDevice(), name, result.getRssi(), services);
    }

    private void hear(BluetoothDevice device, String advertised, Integer rssi, List<UUID> services) {
        if (device == null) {
            return;
        }
        // The background dials use what this scan heard (address type included)
        MoodLight.heard(device);
        String address = device.getAddress();
        Seen row = seen.get(address);
        if (row == null) {
            row = new Seen(address);
            seen.put(address, row);
        }
        String name = advertised;
        if (name == null || name.isEmpty()) {
            try {
                name = device.getName();
            } catch (SecurityException ignored) {
                // connect permission gone
            }
        }
        if (name != null && !name.isEmpty()) {
            row.name = name;
        }
        if (rssi != null) {
            row.rssi = rssi;
        }
        row.light = row.light || LightProtocol.looksLikeLight(row.name, services);
        render();
    }

    @SuppressWarnings("deprecation")
    private static BluetoothDevice deviceExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        }
        return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
    }

    private BluetoothAdapter adapter() {
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        return manager == null ? null : manager.getAdapter();
    }
}
