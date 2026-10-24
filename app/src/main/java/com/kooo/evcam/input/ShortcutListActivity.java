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
        line.findViewById(R.id.shortcut_delete).setOnClickListener(v -> {
            List<Shortcut> next = ShortcutBook.remove(ShortcutBook.parse(config.getButtonShortcuts()), index);
            config.setButtonShortcuts(ShortcutBook.write(next));
            show(next);
        });
        return line;
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
