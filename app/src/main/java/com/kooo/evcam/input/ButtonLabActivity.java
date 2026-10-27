package com.kooo.evcam.input;

import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.R;
import com.kooo.evcam.audio.CarVolume;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.telemetry.CarAmbientLab;
import com.kooo.evcam.telemetry.Telemetry;
import com.kooo.evcam.ui.CamDialogs;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Experimental screen for {@link ButtonLab}: Listen, press the car's buttons one by one, and
 * each press shows up as a card with what it sent. A key can be pressed again and a car setting
 * sent again from its card, to see whether 7xDash can do the same.
 */
public class ButtonLabActivity extends AppCompatActivity implements ButtonLab.Listener {

    private static final String TELEMETRY_USER = "button-lab";

    private final HandlerThread thread = new HandlerThread("button-lab-send");
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private Handler work;
    private LinearLayout rows;
    private TextView status;
    private TextView empty;
    private MaterialButton listen;
    private int dp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_button_lab);
        thread.start();
        work = new Handler(thread.getLooper());
        dp = Math.round(getResources().getDisplayMetrics().density);
        rows = findViewById(R.id.btn_rows);
        status = findViewById(R.id.btn_status);
        empty = findViewById(R.id.btn_empty);
        listen = findViewById(R.id.btn_listen);
        findViewById(R.id.btn_close).setOnClickListener(v -> finish());
        listen.setOnClickListener(v -> {
            if (ButtonLab.isListening()) {
                stopListening();
            } else {
                ButtonLab.start(this, this);
                listen.setText(R.string.button_lab_stop);
            }
        });
        findViewById(R.id.btn_clear).setOnClickListener(v -> {
            rows.removeAllViews();
            empty.setVisibility(View.VISIBLE);
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        // The gear decides whether a car setting may be sent again (parked only)
        Telemetry.get().acquire(getApplicationContext(), TELEMETRY_USER);
    }

    @Override
    protected void onStop() {
        Telemetry.get().release(TELEMETRY_USER);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        // Not on stop: a button can open another screen, and its key or setting still counts
        ButtonLab.stopAll();
        thread.quitSafely();
        super.onDestroy();
    }

    /** Keys pressed while this screen is in front, in case the accessibility service is off. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        ButtonLab.onKey(event);
        return super.dispatchKeyEvent(event);
    }

    private void stopListening() {
        ButtonLab.stopAll();
        listen.setText(R.string.button_lab_listen);
        status.setText(R.string.button_lab_stopped);
    }

    // ================================================================= ButtonLab.Listener

    @Override
    public void status(String line) {
        status.setText(line);
    }

    @Override
    public void heard(ButtonLab.Heard h) {
        empty.setVisibility(View.GONE);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_pref_row_static);
        card.setPadding(20 * dp, 12 * dp, 16 * dp, 12 * dp);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = 10 * dp;

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(clock.format(new Date(h.at)) + "  ·  " + sourceLabel(h.source) + "  ·  " + h.title,
                R.style.TextAppearance_Cam_Body, R.color.text_primary));
        TextView detail = text(h.detail, R.style.TextAppearance_Cam_Caption, R.color.text_secondary);
        detail.setTextIsSelectable(true);
        texts.addView(detail);
        top.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView result = text("", R.style.TextAppearance_Cam_Caption, R.color.text_tertiary);
        result.setPadding(0, 6 * dp, 0, 0);
        result.setVisibility(View.GONE);

        if (h.source == ButtonLab.Source.KEY && h.keyCode != KeyEvent.KEYCODE_UNKNOWN) {
            top.addView(button(getString(R.string.button_lab_press_again), v -> pressAgain(h, result)));
        } else if (h.source == ButtonLab.Source.CAR) {
            addSend(top, h, h.before, result);
            addSend(top, h, h.now, result);
        }
        card.addView(top);
        card.addView(result);
        rows.addView(card, 0, cardParams);
    }

    // ================================================================= simulate

    private void pressAgain(ButtonLab.Heard h, TextView result) {
        CarVolume.key(this, h.keyCode);
        String line = getString(R.string.button_lab_pressed, KeyEvent.keyCodeToString(h.keyCode));
        BlackBox.note("button-lab: simulate " + KeyEvent.keyCodeToString(h.keyCode));
        show(result, line);
    }

    /** "Send 1": the value the setting had (or has), sent to the car after asking. */
    private void addSend(LinearLayout top, ButtonLab.Heard h, String value, TextView result) {
        Integer v = number(value);
        if (v == null) {
            return;
        }
        top.addView(button(getString(R.string.button_lab_send, v), b -> confirm(h, v, result)));
    }

    private void confirm(ButtonLab.Heard h, int value, TextView result) {
        CamDialogs.showDestructive(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.car_ambient_confirm_title)
                .setMessage(getString(R.string.car_ambient_confirm, value, h.setting.name, h.setting.hex()))
                .setPositiveButton(R.string.car_ambient_send, (d, w) -> {
                    show(result, getString(R.string.car_ambient_sending));
                    work.post(() -> {
                        String line = CarAmbientLab.set(getApplicationContext(), h.setting, value);
                        runOnUiThread(() -> show(result, line));
                    });
                })
                .setNegativeButton(android.R.string.cancel, null));
    }

    // ================================================================= views

    private void show(TextView result, String line) {
        result.setText(line);
        result.setVisibility(View.VISIBLE);
    }

    private String sourceLabel(ButtonLab.Source s) {
        switch (s) {
            case KEY:
                return getString(R.string.button_lab_source_key);
            case CAR:
                return getString(R.string.button_lab_source_car);
            default:
                return getString(R.string.button_lab_source_log);
        }
    }

    private static Integer number(String s) {
        try {
            return s == null ? null : Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
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
