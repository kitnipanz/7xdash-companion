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
import com.kooo.evcam.ui.CamDialogs;

import java.util.List;

/**
 * Experimental screen: find out whether the car's own ambient light can be set through ECARX
 * ({@link CarAmbientLab}). Scan lists the ambient-light names the car's library has; a row's
 * Try sends one value after asking, and shows what the car reads back.
 */
public class CarAmbientLabActivity extends AppCompatActivity {

    private final HandlerThread thread = new HandlerThread("car-ambient-lab");
    private Handler work;
    private TextView status;
    private TextView last;
    private LinearLayout rows;
    private MaterialButton scan;
    private CarAmbientLab.Scan result;

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
        findViewById(R.id.amb_close).setOnClickListener(v -> finish());
        scan.setOnClickListener(v -> runScan());
    }

    @Override
    protected void onDestroy() {
        thread.quitSafely();
        super.onDestroy();
    }

    private void runScan() {
        scan.setEnabled(false);
        status.setText(R.string.car_ambient_scanning);
        work.post(() -> {
            CarAmbientLab.Scan found = CarAmbientLab.scan(getApplicationContext());
            runOnUiThread(() -> {
                result = found;
                scan.setEnabled(true);
                render();
            });
        });
    }

    private void render() {
        status.setText(result.status);
        rows.removeAllViews();
        if (result.found.isEmpty()) {
            TextView none = new TextView(this);
            none.setText(R.string.car_ambient_none);
            none.setTextAppearance(R.style.TextAppearance_Cam_Body);
            none.setTextColor(getColor(R.color.text_secondary));
            rows.addView(none);
            return;
        }
        for (CarAmbientLab.Constant k : result.found) {
            View line = LayoutInflater.from(this).inflate(R.layout.item_sticker, rows, false);
            ((TextView) line.findViewById(R.id.sticker_name)).setText(k.owner + "." + k.name);
            String meta = k.hex();
            if (k.looksLikeFunction()) {
                meta += "  ·  " + getString(R.string.car_ambient_read, k.supported, k.current);
            } else {
                meta += "  ·  " + getString(R.string.car_ambient_option);
            }
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
