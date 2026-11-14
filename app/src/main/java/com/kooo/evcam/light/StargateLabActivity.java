package com.kooo.evcam.light;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.R;
import com.kooo.evcam.telemetry.CarAmbientLab;
import com.kooo.evcam.telemetry.StargateLab;
import com.kooo.evcam.telemetry.Telemetry;
import com.kooo.evcam.ui.CamDialogs;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Experimental screen for {@link StargateLab}: can 7xDash drive the Stargate light curtain?
 * Find names, look at the car API and the light apps, Listen while changing things in the car's
 * Light Studio, and read or send one function by hand (parked only). Every line is captured to a
 * file and can be copied in one go.
 */
public class StargateLabActivity extends AppCompatActivity implements StargateLab.Listener {

    private static final String TELEMETRY_USER = "stargate-lab";
    private static final int MAX_ROWS = 400;

    private final HandlerThread thread = new HandlerThread("stargate-lab-work");
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private Handler work;
    private LinearLayout rows;
    private TextView status;
    private TextView empty;
    private TextView fileLine;
    private MaterialButton listen;
    private EditText words;
    private EditText anyFunction;
    private EditText anyZone;
    private EditText anyValue;
    private TextView anyResult;
    private int dp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stargate_lab);
        thread.start();
        work = new Handler(thread.getLooper());
        dp = Math.round(getResources().getDisplayMetrics().density);
        rows = findViewById(R.id.sg_rows);
        status = findViewById(R.id.sg_status);
        empty = findViewById(R.id.sg_empty);
        fileLine = findViewById(R.id.sg_file);
        listen = findViewById(R.id.sg_listen);
        words = findViewById(R.id.sg_words);
        words.setText(StargateLab.DEFAULT_WORDS);
        anyFunction = findViewById(R.id.sg_any_function);
        anyZone = findViewById(R.id.sg_any_zone);
        anyValue = findViewById(R.id.sg_any_value);
        anyResult = findViewById(R.id.sg_any_result);

        findViewById(R.id.sg_close).setOnClickListener(v -> finish());
        findViewById(R.id.sg_find).setOnClickListener(v -> {
            String text = words.getText().toString();
            look(getString(R.string.stargate_lab_finding), () -> StargateLab.find(getApplicationContext(), text));
        });
        findViewById(R.id.sg_api).setOnClickListener(v ->
                look(getString(R.string.stargate_lab_looking), () -> StargateLab.inspectApi(getApplicationContext())));
        findViewById(R.id.sg_apps).setOnClickListener(v ->
                look(getString(R.string.stargate_lab_looking), () -> StargateLab.inspectApps(getApplicationContext())));
        listen.setOnClickListener(v -> {
            if (StargateLab.isListening()) {
                stopListening();
            } else {
                StargateLab.start(this, this, words.getText().toString());
                listen.setText(R.string.button_lab_stop);
            }
        });
        findViewById(R.id.sg_copy).setOnClickListener(v -> copyAll());
        findViewById(R.id.sg_clear).setOnClickListener(v -> {
            rows.removeAllViews();
            empty.setVisibility(View.VISIBLE);
            StargateLab.clearCopy();
        });
        findViewById(R.id.sg_any_read).setOnClickListener(v -> readAny());
        findViewById(R.id.sg_any_send).setOnClickListener(v -> sendAny());
    }

    @Override
    protected void onStart() {
        super.onStart();
        // The gear decides whether Send is allowed (parked only)
        Telemetry.get().acquire(getApplicationContext(), TELEMETRY_USER);
    }

    @Override
    protected void onStop() {
        Telemetry.get().release(TELEMETRY_USER);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        // Not on stop: Listen keeps going while the car's Light Studio is in front
        StargateLab.stopAll();
        StargateLab.closeCapture();
        thread.quitSafely();
        super.onDestroy();
    }

    private void stopListening() {
        StargateLab.stopAll();
        listen.setText(R.string.stargate_lab_listen);
        status.setText(R.string.button_lab_stopped);
    }

    private interface Look {
        List<StargateLab.Heard> run();
    }

    /** One of the one-shot looks, off the main thread; its lines go on top. */
    private void look(String working, Look what) {
        status.setText(working);
        work.post(() -> {
            List<StargateLab.Heard> found = what.run();
            runOnUiThread(() -> {
                for (int i = found.size() - 1; i >= 0; i--) {
                    heard(found.get(i));
                }
                status.setText(StargateLab.isListening() ? getString(R.string.stargate_lab_still_listening)
                        : getString(R.string.stargate_lab_done, found.size()));
            });
        });
    }

    private void copyAll() {
        ClipboardManager clip = getSystemService(ClipboardManager.class);
        String all = StargateLab.copyAll();
        if (clip != null) {
            clip.setPrimaryClip(ClipData.newPlainText("Stargate lab", all));
        }
        status.setText(getString(R.string.stargate_lab_copied, all.length()));
    }

    // ================================================================= StargateLab.Listener

    @Override
    public void status(String line) {
        status.setText(line);
    }

    @Override
    public void heard(StargateLab.Heard h) {
        empty.setVisibility(View.GONE);
        String file = StargateLab.captureFile();
        fileLine.setText(file.isEmpty() ? getString(R.string.stargate_lab_no_file)
                : getString(R.string.stargate_lab_file, file));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.TOP);
        card.setBackgroundResource(R.drawable.bg_pref_row_static);
        card.setPadding(20 * dp, 12 * dp, 16 * dp, 12 * dp);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = 10 * dp;

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(clock.format(new Date(h.at)) + "  ·  " + sourceLabel(h.source) + "  ·  " + h.title,
                R.style.TextAppearance_Cam_Body, R.color.text_primary);
        title.setTextIsSelectable(true);
        texts.addView(title);
        if (h.detail != null && !h.detail.isEmpty()) {
            TextView detail = text(h.detail, R.style.TextAppearance_Cam_Caption, R.color.text_secondary);
            detail.setTextIsSelectable(true);
            texts.addView(detail);
        }
        card.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (h.id != null) {
            card.addView(button(getString(R.string.stargate_lab_use), v -> use(h)));
        }
        if (h.packageName != null) {
            card.addView(button(getString(R.string.stargate_lab_save_apk), v -> saveApk(h)));
        }
        rows.addView(card, 0, cardParams);
        while (rows.getChildCount() > MAX_ROWS) {
            rows.removeViewAt(rows.getChildCount() - 1);
        }
    }

    /** Puts the line's function (and zone) in the Read or send box. */
    private void use(StargateLab.Heard h) {
        anyFunction.setText(String.format(Locale.US, "0x%08X", h.id));
        anyZone.setText(h.zone == null ? "" : String.format(Locale.US, "0x%X", h.zone));
        anyResult.setText(getString(R.string.stargate_lab_used, h.title));
    }

    /** Copies the app's APK to the USB drive, for a closer look on a computer. */
    private void saveApk(StargateLab.Heard h) {
        status.setText(R.string.stargate_lab_saving_apk);
        work.post(() -> {
            String line = StargateLab.saveApk(getApplicationContext(), h.packageName);
            runOnUiThread(() -> status.setText(line));
        });
    }

    // ================================================================= read / send

    private static final class Typed {
        final int id;
        final Integer zone;

        Typed(int id, Integer zone) {
            this.id = id;
            this.zone = zone;
        }
    }

    private Typed typed() {
        Integer id = CarAmbientLab.parseFunction(anyFunction.getText().toString());
        String zoneText = anyZone.getText().toString().trim();
        Integer zone = zoneText.isEmpty() ? null : CarAmbientLab.parseNumber(zoneText);
        if (id == null) {
            runOnUiThread(() -> anyResult.setText(R.string.button_lab_any_no_function));
            return null;
        }
        if (!zoneText.isEmpty() && zone == null) {
            runOnUiThread(() -> anyResult.setText(R.string.button_lab_any_bad_zone));
            return null;
        }
        return new Typed(id, zone);
    }

    private void readAny() {
        anyResult.setText(R.string.button_lab_any_reading);
        work.post(() -> {
            Typed t = typed();
            if (t != null) {
                String line = CarAmbientLab.readAny(getApplicationContext(), t.id, t.zone);
                runOnUiThread(() -> anyResult.setText(line));
            }
        });
    }

    private void sendAny() {
        Integer value = CarAmbientLab.parseNumber(anyValue.getText().toString());
        if (value == null) {
            anyResult.setText(R.string.button_lab_any_no_value);
            return;
        }
        String what = anyFunction.getText().toString().trim();
        String zone = anyZone.getText().toString().trim();
        CamDialogs.showDestructive(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.car_ambient_confirm_title)
                .setMessage(getString(R.string.button_lab_any_confirm, value, what,
                        zone.isEmpty() ? getString(R.string.button_lab_any_no_zone) : zone))
                .setPositiveButton(R.string.car_ambient_send, (d, w) -> {
                    anyResult.setText(R.string.car_ambient_sending);
                    work.post(() -> {
                        Typed t = typed();
                        if (t != null) {
                            String line = CarAmbientLab.sendAny(getApplicationContext(), t.id, t.zone, value);
                            runOnUiThread(() -> anyResult.setText(line));
                        }
                    });
                })
                .setNegativeButton(android.R.string.cancel, null));
    }

    // ================================================================= views

    private String sourceLabel(StargateLab.Source s) {
        switch (s) {
            case NAME:
                return getString(R.string.stargate_lab_source_name);
            case API:
                return getString(R.string.stargate_lab_source_api);
            case APP:
                return getString(R.string.stargate_lab_source_app);
            case CAR:
                return getString(R.string.button_lab_source_car);
            case PUSH:
                return getString(R.string.stargate_lab_source_push);
            case CALLBACK:
                return getString(R.string.stargate_lab_source_callback);
            case LOG:
                return getString(R.string.button_lab_source_log);
            default:
                return getString(R.string.stargate_lab_source_note);
        }
    }

    private TextView text(String s, int appearance, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextAppearance(appearance);
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
}
