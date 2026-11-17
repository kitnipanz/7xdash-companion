package com.kooo.evcam.overlay;

import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.ui.CamDialogs;
import com.kooo.evcam.ui.Notice;

/**
 * Sets up the gauges under the Dash panel: the on / off switch and the three gauges; tap one to
 * choose what it shows. Opened from Settings, or by tapping a gauge ({@link #EXTRA_SLOT} then opens
 * the list for that gauge straight away).
 */
public class DashGaugesActivity extends AppCompatActivity {

    /** Which gauge to choose for as soon as the screen opens. */
    public static final String EXTRA_SLOT = "slot";

    private AppConfig config;
    private SwitchMaterial toggle;
    private LinearLayout row;
    private DashGauge[] gauges;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dash_gauges);
        config = new AppConfig(this);
        toggle = findViewById(R.id.gauges_switch);
        row = findViewById(R.id.gauges_row);
        findViewById(R.id.gauges_close).setOnClickListener(v -> finish());
        toggle.setChecked(config.isDashGaugesEnabled());
        toggle.setOnCheckedChangeListener((b, on) -> {
            if (!OverlayCoordinator.setDashGaugesEnabled(this, on)) {
                Notice.makeText(this, R.string.dash_panel_need_overlay, android.widget.Toast.LENGTH_LONG).show();
                b.setChecked(false);
            }
        });
        gauges = DashGauge.parse(config.getDashGaugeSlots());
        build();
        int slot = getIntent().getIntExtra(EXTRA_SLOT, -1);
        if (savedInstanceState == null && slot >= 0 && slot < DashGauge.COUNT) {
            row.post(() -> pick(slot));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        toggle.setChecked(config.isDashGaugesEnabled());
    }

    private void build() {
        row.removeAllViews();
        for (int i = 0; i < DashGauge.COUNT; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (i > 0) {
                p.setMarginStart(dp(12));
            }
            row.addView(button(i), p);
        }
    }

    private MaterialButton button(int i) {
        MaterialButton b = new MaterialButton(new ContextThemeWrapper(this, R.style.ThemeOverlay_Cam_RailSmall),
                null, com.google.android.material.R.attr.materialButtonStyle);
        b.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_TOP);
        b.setGravity(Gravity.CENTER);
        b.setIconPadding(dp(10));
        b.setMaxLines(2);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setText(getString(gauges[i].labelRes) + "\n" + getString(gauges[i].unitRes));
        b.setIconResource(R.drawable.ic_automation_speed);
        b.setIconSize(dp(36));
        b.setContentDescription(getString(R.string.gauges_gauge, i + 1));
        b.setOnClickListener(v -> pick(i));
        return b;
    }

    /** Choose what gauge {@code i} shows. */
    private void pick(int i) {
        DashGauge[] all = DashGauge.values();
        String[] names = new String[all.length];
        int checked = -1;
        for (int k = 0; k < all.length; k++) {
            names[k] = getString(all[k].labelRes) + " (" + getString(all[k].unitRes) + ")";
            if (all[k] == gauges[i]) {
                checked = k;
            }
        }
        CamDialogs.show(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(getString(R.string.gauges_gauge, i + 1))
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    dialog.dismiss();
                    save(i, all[which]);
                })
                .setNegativeButton(android.R.string.cancel, null));
    }

    private void save(int i, DashGauge gauge) {
        gauges[i] = gauge;
        config.setDashGaugeSlots(DashGauge.write(gauges));
        DashPanelService.gaugesChanged();
        GaugesService.reload();
        build();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
