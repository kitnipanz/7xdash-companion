package com.kooo.evcam.light;

import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.R;
import com.kooo.evcam.telemetry.CarAmbientLab;
import com.kooo.evcam.telemetry.Telemetry;
import com.kooo.evcam.ui.CamDialogs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Experimental screen: find out which car lights 7xDash can set through ECARX ({@link CarAmbientLab}):
 * ambient, rear cabin, foot and hazard lights. Scan lists the light names the car's library has.
 * Watch reads every car setting again and again and lists the ones the car's own buttons change, so
 * a light can be found even when its name gives nothing away. A row's Try sends one value after
 * asking, and shows what the car reads back.
 */
public class CarAmbientLabActivity extends AppCompatActivity {

    private final HandlerThread thread = new HandlerThread("car-ambient-lab");
    private Handler work;
    private TextView status;
    private TextView last;
    private LinearLayout rows;
    private MaterialButton scan;
    private MaterialButton watch;
    private CarAmbientLab.Scan result;

    /** How long after the first read a change counts as noise (clocks, sensors) rather than a button. */
    private static final long SETTLE_MS = 3000L;
    private static final long PASS_GAP_MS = 300L;
    private static final String TELEMETRY_USER = "car-light-lab";
    private volatile boolean watching;
    /** Seen changing since Watch started, in order; worker thread writes, UI reads a copy. */
    private final Map<Integer, CarAmbientLab.Constant> changes = new LinkedHashMap<>();
    private List<CarAmbientLab.Constant> shownChanges = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_car_ambient_lab);
        thread.start();
        work = new Handler(thread.getLooper());
        status = findViewById(R.id.amb_status);
        last = findViewById(R.id.amb_last);
        rows = findViewById(R.id.amb_rows);
        scan = findViewById(R.id.amb_scan);
        watch = findViewById(R.id.amb_watch);
        findViewById(R.id.amb_close).setOnClickListener(v -> finish());
        scan.setOnClickListener(v -> runScan());
        watch.setOnClickListener(v -> {
            if (watching) {
                stopWatch();
            } else {
                startWatch();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        // The gear decides whether hazard lights may be sent (parked only)
        Telemetry.get().acquire(getApplicationContext(), TELEMETRY_USER);
    }

    @Override
    protected void onStop() {
        stopWatch();
        Telemetry.get().release(TELEMETRY_USER);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        watching = false;
        thread.quitSafely();
        super.onDestroy();
    }

    private void startWatch() {
        if (result == null || result.functions.isEmpty() || !result.carReady) {
            return;
        }
        watching = true;
        scan.setEnabled(false);
        watch.setText(R.string.car_ambient_stop);
        List<CarAmbientLab.Constant> list = result.functions;
        status.setText(getString(R.string.car_ambient_watch_settling, list.size()));
        synchronized (changes) {
            changes.clear();
        }
        shownChanges = new ArrayList<>();
        render();
        work.post(() -> {
            String[] base = CarAmbientLab.readAll(getApplicationContext(), list);
            if (base == null) {
                runOnUiThread(this::stopWatch);
                return;
            }
            Set<Integer> noisy = new HashSet<>();
            long settleUntil = android.os.SystemClock.uptimeMillis() + SETTLE_MS;
            boolean[] told = {false};
            Runnable pass = new Runnable() {
                @Override
                public void run() {
                    if (!watching) {
                        return;
                    }
                    String[] now = CarAmbientLab.readAll(getApplicationContext(), list);
                    if (now == null) {
                        return;
                    }
                    boolean settling = android.os.SystemClock.uptimeMillis() < settleUntil;
                    List<CarAmbientLab.Constant> moved = CarAmbientLab.changed(list, base, now);
                    synchronized (changes) {
                        for (CarAmbientLab.Constant k : moved) {
                            if (settling) {
                                noisy.add(k.value);
                            } else if (!noisy.contains(k.value)) {
                                changes.put(k.value, k);
                            }
                        }
                        // A light switched back still shows, with what it reads now
                        for (int i = 0; i < list.size(); i++) {
                            CarAmbientLab.Constant k = list.get(i);
                            if (changes.containsKey(k.value)) {
                                k.current = now[i];
                            }
                        }
                    }
                    List<CarAmbientLab.Constant> copy;
                    synchronized (changes) {
                        copy = new ArrayList<>(changes.values());
                    }
                    boolean announce = !settling && !told[0];
                    told[0] |= announce;
                    int ignored = noisy.size();
                    runOnUiThread(() -> {
                        if (!watching) {
                            return;
                        }
                        if (announce) {
                            status.setText(getString(R.string.car_ambient_watch_ready, list.size(), ignored));
                        }
                        shownChanges = copy;
                        render();
                    });
                    work.postDelayed(this, PASS_GAP_MS);
                }
            };
            work.post(pass);
        });
    }

    private void stopWatch() {
        if (!watching) {
            return;
        }
        watching = false;
        watch.setText(R.string.car_ambient_watch);
        scan.setEnabled(true);
        if (result != null) {
            status.setText(result.status);
        }
    }

    private void runScan() {
        scan.setEnabled(false);
        status.setText(R.string.car_ambient_scanning);
        work.post(() -> {
            CarAmbientLab.Scan found = CarAmbientLab.scan(getApplicationContext());
            runOnUiThread(() -> {
                result = found;
                scan.setEnabled(true);
                watch.setEnabled(found.carReady && !found.functions.isEmpty());
                shownChanges = new ArrayList<>();
                render();
            });
        });
    }

    private void render() {
        if (!watching) {
            status.setText(result.status);
        }
        rows.removeAllViews();
        if (watching || !shownChanges.isEmpty()) {
            TextView title = new TextView(this);
            title.setText(R.string.car_ambient_changed_title);
            title.setTextAppearance(R.style.TextAppearance_Cam_Body);
            title.setTextColor(getColor(R.color.text_primary));
            rows.addView(title);
            if (shownChanges.isEmpty()) {
                TextView none = new TextView(this);
                none.setText(R.string.car_ambient_changed_none);
                none.setTextAppearance(R.style.TextAppearance_Cam_Body);
                none.setTextColor(getColor(R.color.text_secondary));
                rows.addView(none);
            }
            for (CarAmbientLab.Constant k : shownChanges) {
                addRow(k, getString(R.string.car_ambient_changed, k.before, k.current));
            }
            TextView all = new TextView(this);
            all.setText(R.string.car_ambient_names_title);
            all.setTextAppearance(R.style.TextAppearance_Cam_Body);
            all.setTextColor(getColor(R.color.text_primary));
            all.setPadding(0, Math.round(16 * getResources().getDisplayMetrics().density), 0, 0);
            rows.addView(all);
        }
        if (result.found.isEmpty()) {
            TextView none = new TextView(this);
            none.setText(R.string.car_ambient_none);
            none.setTextAppearance(R.style.TextAppearance_Cam_Body);
            none.setTextColor(getColor(R.color.text_secondary));
            rows.addView(none);
            return;
        }
        for (CarAmbientLab.Constant k : result.found) {
            String meta = k.looksLikeFunction()
                    ? getString(R.string.car_ambient_read, k.supported, k.current)
                    : getString(R.string.car_ambient_option);
            addRow(k, meta);
        }
    }

    /** One name: owner.name, then its group, number and {@code detail}, and Try for a function. */
    private void addRow(CarAmbientLab.Constant k, String detail) {
        View line = LayoutInflater.from(this).inflate(R.layout.item_sticker, rows, false);
        ((TextView) line.findViewById(R.id.sticker_name)).setText(k.owner + "." + k.name);
        String meta = kindLabel(k) + "  ·  " + k.hex() + "  ·  " + detail;
        ((TextView) line.findViewById(R.id.sticker_meta)).setText(meta);
        TextView action = line.findViewById(R.id.sticker_action);
        if (k.looksLikeFunction() && result.carReady) {
            action.setText(R.string.car_ambient_try);
            action.setOnClickListener(v -> askValue(k));
        } else {
            action.setVisibility(View.GONE);
        }
        rows.addView(line);
    }

    private String kindLabel(CarAmbientLab.Constant k) {
        if (k.kind == null) {
            return getString(R.string.car_ambient_kind_watched);
        }
        switch (k.kind) {
            case HAZARD:
                return getString(R.string.car_ambient_kind_hazard);
            case FOOT:
                return getString(R.string.car_ambient_kind_foot);
            case CABIN:
                return getString(R.string.car_ambient_kind_cabin);
            default:
                return getString(R.string.car_ambient_kind_ambient);
        }
    }

    /** A value to send: one of the options that go with this name, or a number. */
    private void askValue(CarAmbientLab.Constant k) {
        List<CarAmbientLab.Constant> options = CarAmbientLab.optionsFor(k, result.neighbours);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        body.setPadding(pad, pad / 3, pad, 0);
        TextView hint = new TextView(this);
        hint.setText(getString(R.string.car_ambient_now, k.current));
        hint.setTextAppearance(R.style.TextAppearance_Cam_Body);
        hint.setTextColor(getColor(R.color.text_secondary));
        body.addView(hint);
        EditText field = CamDialogs.input(this);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        field.setHint(R.string.car_ambient_value_hint);
        body.addView(field);
        androidx.appcompat.app.AlertDialog[] shown = new androidx.appcompat.app.AlertDialog[1];
        for (CarAmbientLab.Constant option : options) {
            MaterialButton b = (MaterialButton) LayoutInflater.from(this)
                    .inflate(R.layout.item_group_button, body, false);
            b.setText(option.name + " = " + option.value);
            b.setOnClickListener(v -> {
                shown[0].dismiss();
                confirm(k, option.value);
            });
            body.addView(b);
        }
        shown[0] = CamDialogs.show(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(k.name)
                .setView(body)
                .setPositiveButton(R.string.car_ambient_send, (d, w) -> {
                    try {
                        confirm(k, Integer.parseInt(field.getText().toString().trim()));
                    } catch (NumberFormatException e) {
                        last.setVisibility(View.VISIBLE);
                        last.setText(R.string.car_ambient_bad_value);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null));
    }

    /** Sending goes to the car: ask once more, then send off the main thread. */
    private void confirm(CarAmbientLab.Constant k, int value) {
        CamDialogs.showDestructive(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.car_ambient_confirm_title)
                .setMessage(getString(R.string.car_ambient_confirm, value, k.name, k.hex()))
                .setPositiveButton(R.string.car_ambient_send, (d, w) -> send(k, value))
                .setNegativeButton(android.R.string.cancel, null));
    }

    private void send(CarAmbientLab.Constant k, int value) {
        last.setVisibility(View.VISIBLE);
        last.setText(R.string.car_ambient_sending);
        work.post(() -> {
            String line = CarAmbientLab.set(getApplicationContext(), k, value);
            runOnUiThread(() -> {
                last.setText(line);
                render();
            });
        });
    }
}
