package com.kooo.evcam.input;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.overlay.MacroPadService;
import com.kooo.evcam.overlay.MacroPadSlots;
import com.kooo.evcam.overlay.OverlayCoordinator;
import com.kooo.evcam.ui.CamDialogs;
import com.kooo.evcam.ui.Notice;

/**
 * Sets up the Macro pad: the on / off switch and a big copy of the pad. Tap a button to choose its
 * action from the same tabs as Shortcuts, or clear it. Opened from the Home card, or by tapping an
 * empty button on the pad ({@link #EXTRA_SLOT} then opens the picker for that button straight away).
 */
public class MacroPadActivity extends AppCompatActivity {

    /** Which button to choose an action for as soon as the screen opens. */
    public static final String EXTRA_SLOT = "slot";

    private AppConfig config;
    private SwitchMaterial toggle;
    private LinearLayout grid;
    private String[] slots;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_macro_pad);
        config = new AppConfig(this);
        toggle = findViewById(R.id.macro_switch);
        grid = findViewById(R.id.macro_grid);
        findViewById(R.id.macro_close).setOnClickListener(v -> finish());
        toggle.setChecked(config.isMacroPadEnabled());
        toggle.setOnCheckedChangeListener((b, on) -> {
            if (!OverlayCoordinator.setMacroPadEnabled(this, on)) {
                Notice.makeText(this, R.string.dash_panel_need_overlay, android.widget.Toast.LENGTH_LONG).show();
                b.setChecked(false);
            }
        });
        slots = MacroPadSlots.parse(config.getMacroPadSlots());
        build();
        int slot = getIntent().getIntExtra(EXTRA_SLOT, -1);
        if (savedInstanceState == null && slot >= 0 && slot < MacroPadSlots.COUNT) {
            grid.post(() -> pick(slot));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        toggle.setChecked(config.isMacroPadEnabled());
    }

    private void build() {
        grid.removeAllViews();
        int gap = dp(12);
        for (int r = 0; r < MacroPadSlots.ROWS; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            if (r > 0) {
                rowParams.topMargin = gap;
            }
            grid.addView(row, rowParams);
            for (int c = 0; c < MacroPadSlots.COLUMNS; c++) {
                int i = r * MacroPadSlots.COLUMNS + c;
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                if (c > 0) {
                    p.setMarginStart(gap);
                }
                row.addView(button(i), p);
            }
        }
    }

    private MaterialButton button(int i) {
        MaterialButton b = new MaterialButton(new ContextThemeWrapper(this, R.style.ThemeOverlay_Cam_RailSmall),
                null, com.google.android.material.R.attr.materialButtonStyle);
        ShortcutAction action = ShortcutAction.fromKey(slots[i]);
        b.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_TOP);
        b.setGravity(Gravity.CENTER);
        b.setIconPadding(dp(10));
        b.setMaxLines(2);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        if (action == null) {
            b.setText(R.string.macro_pad_empty);
            b.setIconResource(R.drawable.ic_add);
            int dim = ContextCompat.getColor(this, R.color.text_secondary);
            b.setTextColor(dim);
            b.setIconTint(ColorStateList.valueOf(dim));
        } else {
            b.setText(action.labelRes);
            b.setIconResource(action.iconRes);
        }
        // After the icon is set, or the button's own icon size wins
        b.setIconSize(dp(36));
        b.setContentDescription(getString(R.string.macro_pad_button, i + 1));
        b.setOnClickListener(v -> pick(i));
        return b;
    }

    /** Choose the action for button {@code i}; Clear empties it. */
    private void pick(int i) {
        int pad = dp(24);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(pad, dp(8), pad, 0);
        LinearLayout tabs = new LinearLayout(this);
        LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabParams.bottomMargin = dp(12);
        body.addView(tabs, tabParams);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        body.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog[] shown = new AlertDialog[1];
        ActionTabs.fill(tabs, list, 52, ShortcutAction.fromKey(slots[i]), picked -> {
            if (shown[0] != null) {
                shown[0].dismiss();
            }
            if (picked == ShortcutAction.ANNOUNCE_PLAY) {
                com.kooo.evcam.announce.AnnouncePick.choose(this, (key, label) -> save(i, key));
            } else {
                save(i, picked.key);
            }
        });
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(getString(R.string.macro_pad_button, i + 1))
                .setView(body)
                .setNegativeButton(android.R.string.cancel, null);
        if (!slots[i].isEmpty()) {
            builder.setNeutralButton(R.string.macro_pad_clear, (dialog, which) -> save(i, ""));
        }
        shown[0] = CamDialogs.show(builder);
        // Wide enough for every tab on one line
        if (shown[0] != null && shown[0].getWindow() != null) {
            shown[0].getWindow().setLayout(Math.round(getResources().getDisplayMetrics().widthPixels * 0.7f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void save(int i, String key) {
        slots = MacroPadSlots.with(slots, i, key);
        config.setMacroPadSlots(MacroPadSlots.write(slots));
        MacroPadService.reload(this);
        build();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
