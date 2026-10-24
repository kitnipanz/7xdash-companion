package com.kooo.evcam.announce;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.R;

import java.util.List;

/** Settings for announcements: add a phrase, pick the Sentry Mode one, play or delete each. */
public class AnnouncementsActivity extends AppCompatActivity {

    private AnnouncementStore store;
    private LinearLayout rows;
    private EditText input;
    private TextView sentryValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_announcements);
        store = new AnnouncementStore(this);
        rows = findViewById(R.id.announce_rows);
        input = findViewById(R.id.announce_input);
        sentryValue = findViewById(R.id.announce_sentry_value);
        findViewById(R.id.announce_back).setOnClickListener(v -> finish());
        findViewById(R.id.announce_stop_all).setOnClickListener(v -> Announcer.stop());
        findViewById(R.id.announce_add).setOnClickListener(v -> add());
        input.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_DONE) {
                add();
                return true;
            }
            return false;
        });
        findViewById(R.id.announce_sentry).setOnClickListener(v -> pickSentry());
        show();
    }

    private void add() {
        if (store.add(input.getText().toString()) != null) {
            input.setText("");
            show();
        }
    }

    private void show() {
        rows.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (Announcement a : store.all()) {
            View row = inflater.inflate(R.layout.item_announce_row, rows, false);
            ImageView icon = row.findViewById(R.id.announce_row_icon);
            icon.setImageResource(a.isSound() ? R.drawable.ic_speaker : R.drawable.ic_megaphone);
            ((TextView) row.findViewById(R.id.announce_row_text)).setText(Labels.of(this, a));
            row.findViewById(R.id.announce_row_play).setOnClickListener(v -> Announcer.play(this, a, "settings"));
            row.findViewById(R.id.announce_row_delete).setOnClickListener(v -> {
                store.remove(a.id);
                show();
            });
            rows.addView(row);
        }
        Announcement sentry = store.find(store.sentryId());
        sentryValue.setText(sentry == null ? getString(R.string.announce_sentry_none) : Labels.of(this, sentry));
    }

    private void pickSentry() {
        List<Announcement> all = store.all();
        String[] names = new String[all.size() + 1];
        names[0] = getString(R.string.announce_sentry_none);
        int checked = 0;
        for (int i = 0; i < all.size(); i++) {
            names[i + 1] = Labels.of(this, all.get(i));
            if (all.get(i).id.equals(store.sentryId())) {
                checked = i + 1;
            }
        }
        com.kooo.evcam.ui.CamDialogs.show(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.announce_sentry_title)
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    store.setSentryId(which == 0 ? "" : all.get(which - 1).id);
                    dialog.dismiss();
                    show();
                })
                .setNegativeButton(android.R.string.cancel, null));
    }
}
