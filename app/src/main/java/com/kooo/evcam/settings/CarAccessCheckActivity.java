package com.kooo.evcam.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.kooo.evcam.R;
import com.kooo.evcam.telemetry.CarAccessCheck;
import com.kooo.evcam.telemetry.Telemetry;

import java.util.ArrayList;
import java.util.List;

/**
 * Screen for {@link CarAccessCheck}: one button runs every check, the lines show what each way
 * into the car's data did for this install, Copy all puts the whole result on the clipboard.
 * Another button asks for the car permissions the user has to allow.
 */
public class CarAccessCheckActivity extends AppCompatActivity {

    private static final String TELEMETRY_USER = "car-access-check";
    private static final int ASK_CODE = 0x7CAC;

    private final HandlerThread thread = new HandlerThread("car-access-check");
    private Handler work;
    private LinearLayout rows;
    private TextView status;
    private List<CarAccessCheck.Line> last = new ArrayList<>();
    private boolean running;
    private int dp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_car_access_check);
        thread.start();
        work = new Handler(thread.getLooper());
        dp = Math.round(getResources().getDisplayMetrics().density);
        rows = findViewById(R.id.cac_rows);
        status = findViewById(R.id.cac_status);
        findViewById(R.id.cac_close).setOnClickListener(v -> finish());
        findViewById(R.id.cac_run).setOnClickListener(v -> check());
        findViewById(R.id.cac_ask).setOnClickListener(v -> ask());
        findViewById(R.id.cac_copy).setOnClickListener(v -> copyAll());
    }

    @Override
    protected void onStart() {
        super.onStart();
        // So the check can also say what 7xDash's own vehicle signal connection did
        Telemetry.get().acquire(getApplicationContext(), TELEMETRY_USER);
    }

    @Override
    protected void onStop() {
        Telemetry.get().release(TELEMETRY_USER);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        thread.quitSafely();
        super.onDestroy();
    }

    private void check() {
        if (running) {
            return;
        }
        running = true;
        status.setText(R.string.cac_running);
        work.post(() -> {
            List<CarAccessCheck.Line> lines;
            try {
                lines = CarAccessCheck.run(getApplicationContext());
            } catch (Throwable t) {
                lines = new ArrayList<>();
                lines.add(new CarAccessCheck.Line(CarAccessCheck.State.FAIL, "check", t.toString()));
            }
            List<CarAccessCheck.Line> result = lines;
            runOnUiThread(() -> {
                running = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                show(result);
            });
        });
    }

    private void show(List<CarAccessCheck.Line> lines) {
        last = lines;
        rows.removeAllViews();
        int ok = 0;
        int failed = 0;
        for (CarAccessCheck.Line l : lines) {
            if (l.state == CarAccessCheck.State.OK) {
                ok++;
            } else if (l.state == CarAccessCheck.State.FAIL) {
                failed++;
            }
            rows.addView(row(l));
        }
        status.setText(getString(R.string.cac_done, ok, failed));
    }

    private LinearLayout row(CarAccessCheck.Line l) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (l.state == CarAccessCheck.State.SECTION) {
            params.topMargin = 14 * dp;
            params.bottomMargin = 6 * dp;
            TextView title = text(l.label, R.style.TextAppearance_Cam_Body, R.color.text_primary);
            title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
            card.addView(title);
            card.setLayoutParams(params);
            return card;
        }
        params.bottomMargin = 6 * dp;
        card.setBackgroundResource(R.drawable.bg_pref_row_static);
        card.setPadding(16 * dp, 10 * dp, 16 * dp, 10 * dp);
        String mark;
        int color;
        switch (l.state) {
            case OK:
                mark = "✓ ";
                color = R.color.text_primary;
                break;
            case FAIL:
                mark = "✗ ";
                color = R.color.recording_text;
                break;
            case WARN:
                mark = "! ";
                color = R.color.energy_text;
                break;
            default:
                mark = "· ";
                color = R.color.text_secondary;
                break;
        }
        TextView title = text(mark + l.label, R.style.TextAppearance_Cam_Body, color);
        title.setTextIsSelectable(true);
        card.addView(title);
        if (!l.detail.isEmpty()) {
            TextView detail = text(l.detail, R.style.TextAppearance_Cam_Caption, R.color.text_secondary);
            detail.setTextIsSelectable(true);
            card.addView(detail);
        }
        card.setLayoutParams(params);
        return card;
    }

    private TextView text(String s, int appearance, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextAppearance(appearance);
        t.setTextColor(getColor(color));
        return t;
    }

    private void ask() {
        String[] ask = CarAccessCheck.permissionsToAsk(this);
        if (ask.length == 0) {
            status.setText(R.string.cac_ask_none);
            return;
        }
        requestPermissions(ask, ASK_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == ASK_CODE) {
            check();
        }
    }

    private void copyAll() {
        if (last.isEmpty()) {
            status.setText(R.string.cac_empty);
            return;
        }
        String all = CarAccessCheck.text(last);
        ClipboardManager clip = getSystemService(ClipboardManager.class);
        if (clip != null) {
            clip.setPrimaryClip(ClipData.newPlainText("Car access check", all));
        }
        status.setText(getString(R.string.cac_copied, all.length()));
    }
}
