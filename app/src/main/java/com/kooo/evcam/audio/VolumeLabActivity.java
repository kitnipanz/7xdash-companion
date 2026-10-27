package com.kooo.evcam.audio;

import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.kooo.evcam.R;

import java.util.List;

/**
 * Experimental screen: can 7xDash change the car's main volume? One card per way
 * ({@link VolumeLab.Way}), each with Read, Up and Down and the result of the last press.
 */
public class VolumeLabActivity extends AppCompatActivity {

    private final HandlerThread thread = new HandlerThread("volume-lab");
    private Handler work;
    private LinearLayout rows;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_volume_lab);
        // The volume key way then turns media, not the ring volume, when nothing is playing
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        thread.start();
        work = new Handler(thread.getLooper());
        rows = findViewById(R.id.vol_rows);
        findViewById(R.id.vol_close).setOnClickListener(v -> finish());
        addWay(VolumeLab.Way.CAR_GROUP, R.string.volume_lab_car_group, R.string.volume_lab_car_group_desc);
        addWay(VolumeLab.Way.STREAM, R.string.volume_lab_stream, R.string.volume_lab_stream_desc);
        addWay(VolumeLab.Way.KEY, R.string.volume_lab_key, R.string.volume_lab_key_desc);
        addWay(VolumeLab.Way.ECARX, R.string.volume_lab_ecarx, R.string.volume_lab_ecarx_desc);
    }

    @Override
    protected void onDestroy() {
        thread.quitSafely();
        super.onDestroy();
    }

    private void addWay(VolumeLab.Way way, int title, int desc) {
        int dp = Math.round(getResources().getDisplayMetrics().density);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_pref_row_static);
        card.setPadding(20 * dp, 14 * dp, 16 * dp, 14 * dp);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = 12 * dp;

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(getString(title), R.style.TextAppearance_Cam_Body, R.color.text_primary));
        texts.addView(text(getString(desc), R.style.TextAppearance_Cam_Caption, R.color.text_secondary));
        top.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView result = text(getString(R.string.volume_lab_waiting), R.style.TextAppearance_Cam_Body,
                R.color.text_secondary);
        result.setPadding(0, 10 * dp, 0, 0);
        result.setTextIsSelectable(true);
        TextView extra = text("", R.style.TextAppearance_Cam_Caption, R.color.text_tertiary);
        extra.setPadding(0, 8 * dp, 0, 0);
        extra.setTextIsSelectable(true);
        extra.setVisibility(View.GONE);

        top.addView(button(R.string.volume_lab_read, v -> run(way, 0, result, extra)));
        top.addView(button(R.string.volume_lab_down, v -> run(way, -1, result, extra)));
        top.addView(button(R.string.volume_lab_up, v -> run(way, 1, result, extra)));
        card.addView(top);
        card.addView(result);
        card.addView(extra);
        rows.addView(card, cardParams);
    }

    /** {@code direction} 0 reads; +1/-1 steps. The key way presses on the main thread, the rest off it. */
    private void run(VolumeLab.Way way, int direction, TextView result, TextView extra) {
        result.setText(R.string.volume_lab_working);
        if (way == VolumeLab.Way.KEY && direction != 0) {
            String sent = VolumeLab.key(rows, direction);
            // Give the system a moment to apply it, then show what the volume reads
            work.postDelayed(() -> {
                String after = VolumeLab.read(getApplicationContext(), way);
                runOnUiThread(() -> result.setText(sent + "\n" + after));
            }, 400);
            return;
        }
        work.post(() -> {
            String line = direction == 0
                    ? VolumeLab.read(getApplicationContext(), way)
                    : VolumeLab.step(getApplicationContext(), way, direction);
            List<String> scan = way == VolumeLab.Way.ECARX ? VolumeLab.scan(getApplicationContext()) : null;
            runOnUiThread(() -> {
                result.setText(line);
                if (scan != null) {
                    extra.setText(String.join("\n", scan));
                    extra.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private TextView text(String s, int appearance, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextAppearance(appearance);
        t.setTextColor(getColor(color));
        return t;
    }

    private MaterialButton button(int label, View.OnClickListener click) {
        int dp = Math.round(getResources().getDisplayMetrics().density);
        MaterialButton b = new MaterialButton(
                new android.view.ContextThemeWrapper(this, R.style.ThemeOverlay_Cam_RailSmall), null,
                com.google.android.material.R.attr.materialButtonStyle);
        b.setText(label);
        b.setOnClickListener(click);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(96 * dp, 52 * dp);
        p.setMarginStart(10 * dp);
        b.setLayoutParams(p);
        return b;
    }
}
