package com.kooo.evcam.zeekr;

import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.telemetry.BlindSpotWatch;
import com.kooo.evcam.telemetry.CarAmbientLab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Finds the car setting that says "a car is in the blind spot" on each side. The number is not
 * known for the 7X, so the driver teaches it: with a car beside them, the row that flips is the
 * one, and Left / Right remembers it with the value it reads while warning
 * ({@link AppConfig#setBlindSpotLearned}). From then on {@link BlindSpotWatch} reads it while
 * signalling: red edge on the side view, orange blink on the mood lights.
 *
 * <p>Shows the car settings whose names look like blind-spot ones; All shows every car setting
 * that changes while this screen is open, for when the name gives nothing away.</p>
 */
public class BlindSpotFinderActivity extends AppCompatActivity {

    private static final Pattern NAME = com.kooo.evcam.telemetry.BlindSpotSniffer.NAME;
    private static final long NAMED_POLL_MS = 250L;
    private static final long ALL_POLL_MS = 1000L;
    /** A row that changed this recently is lit. */
    private static final long LIT_MS = 3000L;

    private final HandlerThread thread = new HandlerThread("blind-spot-finder");
    private Handler work;
    private AppConfig config;
    private LinearLayout rows;
    private TextView status;
    private TextView learned;
    private MaterialButton allButton;
    private int dp;

    /** Read on the worker thread only. */
    private List<CarAmbientLab.Constant> named = new ArrayList<>();
    private List<CarAmbientLab.Constant> all = new ArrayList<>();
    private String[] namedBefore;
    private String[] allBefore;
    private volatile boolean showAll;
    private volatile boolean running;
    /** Main thread: one row per car setting shown, with when it last changed. */
    private final Map<Integer, Row> shown = new LinkedHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_blind_spot_finder);
        config = new AppConfig(this);
        dp = Math.round(getResources().getDisplayMetrics().density);
        thread.start();
        work = new Handler(thread.getLooper());
        rows = findViewById(R.id.bsf_rows);
        status = findViewById(R.id.bsf_status);
        learned = findViewById(R.id.bsf_learned);
        allButton = findViewById(R.id.bsf_all);
        findViewById(R.id.bsf_close).setOnClickListener(v -> finish());
        allButton.setOnClickListener(v -> {
            showAll = !showAll;
            allButton.setText(showAll ? R.string.bsd_finder_named : R.string.bsd_finder_all);
            allBefore = null;
        });
        findViewById(R.id.bsf_forget).setOnClickListener(v -> {
            config.setBlindSpotLearned(true, 0, 0);
            config.setBlindSpotLearned(false, 0, 0);
            BlackBox.note("盲区报警：学到的号码清掉了");
            showLearned();
        });
        showLearned();
    }

    @Override
    protected void onStart() {
        super.onStart();
        running = true;
        work.post(this::scan);
    }

    @Override
    protected void onStop() {
        running = false;
        work.removeCallbacksAndMessages(null);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        thread.quitSafely();
        super.onDestroy();
    }

    // ================================================================= worker

    private void scan() {
        CarAmbientLab.Scan scan = CarAmbientLab.scan(getApplicationContext());
        List<CarAmbientLab.Constant> found = new ArrayList<>();
        for (CarAmbientLab.Constant k : scan.functions) {
            if (NAME.matcher(k.name).find()) {
                found.add(k);
            }
        }
        named = found;
        all = scan.functions;
        String line = scan.carReady
                ? getString(R.string.bsd_finder_status, found.size(), scan.functions.size())
                : getString(R.string.bsd_finder_no_car, scan.status);
        runOnUiThread(() -> status.setText(line));
        if (scan.carReady) {
            work.post(this::poll);
        }
    }

    private void poll() {
        if (!running) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        String[] namedNow = CarAmbientLab.readAll(getApplicationContext(), named);
        List<Shown> out = new ArrayList<>();
        if (namedNow != null) {
            for (int i = 0; i < named.size(); i++) {
                boolean changed = namedBefore != null && !java.util.Objects.equals(namedBefore[i], namedNow[i]);
                out.add(new Shown(named.get(i), namedNow[i], changed ? now : 0L, true));
            }
            namedBefore = namedNow;
        }
        if (showAll) {
            String[] allNow = CarAmbientLab.readAll(getApplicationContext(), all);
            if (allNow != null) {
                if (allBefore != null) {
                    for (CarAmbientLab.Constant k : CarAmbientLab.changed(all, allBefore, allNow)) {
                        out.add(new Shown(k, k.current, now, false));
                    }
                }
                allBefore = allNow;
            }
        }
        runOnUiThread(() -> show(out, now));
        work.postDelayed(this::poll, showAll ? ALL_POLL_MS : NAMED_POLL_MS);
    }

    // ================================================================= screen

    private void show(List<Shown> list, long now) {
        if (list.isEmpty() && shown.isEmpty()) {
            return;
        }
        for (Shown s : list) {
            Row row = shown.get(s.k.value);
            if (row == null) {
                if (!s.named && s.changedAt == 0L) {
                    continue;
                }
                row = newRow(s.k);
                shown.put(s.k.value, row);
            }
            row.value.setText(getString(R.string.bsd_finder_value, s.value));
            row.current = s.value;
            if (s.changedAt != 0L) {
                row.changedAt = s.changedAt;
            }
        }
        for (Row row : shown.values()) {
            boolean lit = row.changedAt != 0L && now - row.changedAt < LIT_MS;
            row.card.setBackgroundResource(lit ? R.drawable.bg_pref_row_primary : R.drawable.bg_pref_row_static);
        }
    }

    private Row newRow(CarAmbientLab.Constant k) {
        Row row = new Row(k);
        row.card = new LinearLayout(this);
        row.card.setOrientation(LinearLayout.HORIZONTAL);
        row.card.setGravity(Gravity.CENTER_VERTICAL);
        row.card.setBackgroundResource(R.drawable.bg_pref_row_static);
        row.card.setPadding(20 * dp, 10 * dp, 16 * dp, 10 * dp);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(k.name, R.style.TextAppearance_Cam_Body, R.color.text_primary));
        texts.addView(text(k.hex() + "  ·  " + k.owner.substring(k.owner.lastIndexOf('.') + 1),
                R.style.TextAppearance_Cam_Caption, R.color.text_tertiary));
        row.value = text("", R.style.TextAppearance_Cam_Caption, R.color.text_secondary);
        texts.addView(row.value);
        row.card.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.card.addView(button(getString(R.string.bsd_finder_left), v -> learn(row, true)));
        row.card.addView(button(getString(R.string.bsd_finder_right), v -> learn(row, false)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = 10 * dp;
        rows.addView(row.card, p);
        return row;
    }

    /** Remember this setting for one side, with the value it reads right now (while the car warns). */
    private void learn(Row row, boolean left) {
        Integer value = parse(row.current);
        if (value == null) {
            com.kooo.evcam.ui.Notice.makeText(this, R.string.bsd_finder_not_number, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        config.setBlindSpotLearned(left, row.k.value, value);
        BlackBox.note("盲区报警学到" + (left ? "左" : "右") + "侧：" + row.k.name + " " + row.k.hex() + " = " + value);
        showLearned();
    }

    private void showLearned() {
        BlindSpotWatch.Learned l = config.getBlindSpotLearned();
        learned.setText(getString(R.string.bsd_finder_learned,
                side(l.leftId, l.leftValue), side(l.rightId, l.rightValue)));
    }

    private String side(int id, int value) {
        return id == 0 ? getString(R.string.bsd_finder_not_learned)
                : String.format(Locale.US, "0x%08X = %d", id, value);
    }

    static Integer parse(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private TextView text(String s, int style, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextAppearance(style);
        t.setTextColor(getColor(color));
        return t;
    }

    private MaterialButton button(String label, View.OnClickListener click) {
        MaterialButton b = new MaterialButton(
                new android.view.ContextThemeWrapper(this, R.style.ThemeOverlay_Cam_RailSmall), null,
                com.google.android.material.R.attr.materialButtonStyle);
        b.setText(label);
        b.setOnClickListener(click);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, 52 * dp);
        p.setMarginStart(10 * dp);
        b.setLayoutParams(p);
        return b;
    }

    private static final class Shown {
        final CarAmbientLab.Constant k;
        final String value;
        final long changedAt;
        final boolean named;

        Shown(CarAmbientLab.Constant k, String value, long changedAt, boolean named) {
            this.k = k;
            this.value = value;
            this.changedAt = changedAt;
            this.named = named;
        }
    }

    private static final class Row {
        final CarAmbientLab.Constant k;
        LinearLayout card;
        TextView value;
        String current;
        long changedAt;

        Row(CarAmbientLab.Constant k) {
            this.k = k;
        }
    }
}
