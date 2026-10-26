package com.kooo.evcam.input;

import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;

import java.util.List;

/** Saved button shortcuts. Add opens the press-then-pick flow. */
public class ShortcutListActivity extends AppCompatActivity {

    private AppConfig config;
    private LinearLayout rows;
    private TextView empty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_shortcut_list);
        config = new AppConfig(this);
        rows = findViewById(R.id.shortcut_rows);
        empty = findViewById(R.id.shortcut_empty);
        findViewById(R.id.shortcut_close).setOnClickListener(v -> finish());
        findViewById(R.id.shortcut_add).setOnClickListener(v ->
                startActivity(new Intent(this, ShortcutCaptureActivity.class)));
        android.widget.CompoundButton catcher = findViewById(R.id.shortcut_catch);
        catcher.setChecked(config.isShortcutCatchEverywhere());
        catcher.setOnCheckedChangeListener((button, on) -> {
            config.setShortcutCatchEverywhere(on);
            updateBanner();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        show(ShortcutBook.parse(config.getButtonShortcuts()));
        updateBanner();
    }

    private void updateBanner() {
        boolean everywhere = com.kooo.evcam.KeepAliveAccessibilityService.isRunning()
                || AccessibilityGate.isEnabled(this);
        findViewById(R.id.shortcut_banner).setVisibility(everywhere ? View.GONE : View.VISIBLE);
        findViewById(R.id.shortcut_catch).setVisibility(everywhere ? View.GONE : View.VISIBLE);
        TextView bannerText = findViewById(R.id.shortcut_banner_text);
        bannerText.setText(config.isShortcutCatchEverywhere()
                ? R.string.shortcut_banner_catcher : R.string.shortcut_banner);
    }

    private void show(List<Shortcut> items) {
        rows.removeAllViews();
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        for (int i = 0; i < items.size(); i++) {
            rows.addView(row(items.get(i), i));
        }
    }

    private View row(Shortcut item, int index) {
        View line = LayoutInflater.from(this).inflate(R.layout.item_shortcut_row, rows, false);
        TextView label = line.findViewById(R.id.shortcut_label);
        label.setText(getString(R.string.shortcut_row,
                buttonName(item) + " · " + getString(item.pressKind().labelRes),
                actionName(item)));
        line.findViewById(R.id.shortcut_edit).setOnClickListener(v -> editAction(item, index));
        line.findViewById(R.id.shortcut_delete).setOnClickListener(v -> {
            List<Shortcut> next = ShortcutBook.remove(ShortcutBook.parse(config.getButtonShortcuts()), index);
            config.setButtonShortcuts(ShortcutBook.write(next));
            show(next);
        });
        return line;
    }

    /** Pick a new action for this button and press; "Press type" switches tap / hold / double instead. */
    private void editAction(Shortcut item, int index) {
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(24 * density);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(pad, Math.round(8 * density), pad, 0);
        LinearLayout tabs = new LinearLayout(this);
        LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        tabParams.bottomMargin = Math.round(12 * density);
        body.addView(tabs, tabParams);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        body.addView(scroll, new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        String title = buttonName(item) + " · " + getString(item.pressKind().labelRes);
        androidx.appcompat.app.AlertDialog[] shown = new androidx.appcompat.app.AlertDialog[1];
        ActionTabs.fill(tabs, list, 52, ShortcutAction.fromKey(item.action), picked -> {
            if (shown[0] != null) {
                shown[0].dismiss();
            }
            if (picked == ShortcutAction.ANNOUNCE_PLAY) {
                com.kooo.evcam.announce.AnnouncePick.choose(this,
                        (key, label) -> replace(index, item, key, item.pressKind().key));
            } else {
                replace(index, item, picked.key, item.pressKind().key);
            }
        });
        shown[0] = com.kooo.evcam.ui.CamDialogs.show(new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(title)
                .setView(body)
                .setNeutralButton(R.string.shortcut_edit_press, (dialog, which) -> editPress(item, index))
                .setNegativeButton(android.R.string.cancel, null));
    }

    private void editPress(Shortcut item, int index) {
        PressKind[] kinds = PressKind.values();
        String[] names = new String[kinds.length];
        int checked = 0;
        for (int i = 0; i < kinds.length; i++) {
            names[i] = getString(kinds[i].labelRes);
            if (kinds[i] == item.pressKind()) {
                checked = i;
            }
        }
        com.kooo.evcam.ui.CamDialogs.show(new com.google.android.material.dialog.MaterialAlertDialogBuilder(
                this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.shortcut_edit_press)
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    dialog.dismiss();
                    replace(index, item, item.action, kinds[which].key);
                })
                .setNegativeButton(android.R.string.cancel, null));
    }

    /**
     * Swaps the shortcut at {@code index} for the same button with a new action or press. Another
     * shortcut already on that button and press gives way, as when adding one.
     */
    private void replace(int index, Shortcut item, String action, String press) {
        List<Shortcut> next = ShortcutBook.put(
                ShortcutBook.remove(ShortcutBook.parse(config.getButtonShortcuts()), index),
                new Shortcut(item.keyCode, item.scanCode, item.deviceName, action, press));
        config.setButtonShortcuts(ShortcutBook.write(next));
        if (StickerFrame.isSticker(item.deviceName)) {
            StickerHub.sync(this);
        } else {
            KeyCatcher.sync(this);
        }
        show(next);
    }

    private String buttonName(Shortcut item) {
        if (StickerFrame.isSticker(item.deviceName)) {
            int label = StickerFrame.controlLabel(item.keyCode);
            String control = label == 0 ? String.valueOf(item.keyCode) : getString(label);
            String tail = StickerNames.label(this, StickerFrame.address(item.deviceName));
            return tail.isEmpty() ? control : control + " " + tail;
        }
        if (item.keyCode != 0) {
            String name = KeyEvent.keyCodeToString(item.keyCode);
            String prefix = "KEYCODE_";
            if (name.startsWith(prefix)) {
                name = name.substring(prefix.length());
            }
            return name;
        }
        return getString(R.string.shortcut_scan, item.scanCode);
    }

    private String actionName(Shortcut item) {
        String announce = com.kooo.evcam.announce.AnnouncePick.label(this, item.action);
        if (announce != null) {
            return announce;
        }
        ShortcutAction action = ShortcutAction.fromKey(item.action);
        return action == null ? item.action : getString(action.labelRes);
    }
}
