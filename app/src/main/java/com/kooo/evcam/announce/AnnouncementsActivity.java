package com.kooo.evcam.announce;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.R;

import java.util.List;

/** Settings for announcements: add a phrase, pick the Sentry Mode one, play or delete each. */
public class AnnouncementsActivity extends AppCompatActivity {

    private static final int REQUEST_MIC = 41;

    private AnnouncementStore store;
    private Announcement pendingRecord;
    private LinearLayout rows;
    private EditText input;
    private TextView sentryValue;
    private TextView voice;
    private static volatile boolean downloading;

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
        findViewById(R.id.announce_record_new).setOnClickListener(v -> {
            Announcement added = store.add(input.getText().toString());
            if (added == null) {
                input.setError(getString(R.string.announce_record_name_first));
                return;
            }
            input.setText("");
            show();
            record(added);
        });
        input.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_DONE) {
                add();
                return true;
            }
            return false;
        });
        findViewById(R.id.announce_sentry).setOnClickListener(v -> pickSentry());
        voice = findViewById(R.id.announce_voice);
        voice.setOnClickListener(v -> downloadVoice());
        paintVoice();
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
            ((TextView) row.findViewById(R.id.announce_row_text)).setText(store.hasRecording(a.id)
                    ? getString(R.string.announce_in_your_voice, Labels.of(this, a)) : Labels.of(this, a));
            View mic = row.findViewById(R.id.announce_row_record);
            mic.setVisibility(a.isSound() ? View.GONE : View.VISIBLE);
            mic.setOnClickListener(v -> record(a));
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

    /** Records the driver saying the phrase; it then plays instead of the speech engine. */
    private void record(Announcement a) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            pendingRecord = a;
            ActivityCompat.requestPermissions(this, new String[] {Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
            return;
        }
        Announcer.stop();
        VoiceRecorder recorder = new VoiceRecorder();
        TextView level = new TextView(this);
        level.setTextAppearance(R.style.TextAppearance_Cam_Body);
        level.setTextColor(getColor(R.color.text_primary));
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        level.setPadding(pad, pad / 2, pad, 0);
        level.setText(getString(R.string.announce_record_level, 0f, 0));
        androidx.appcompat.app.AlertDialog dialog = com.kooo.evcam.ui.CamDialogs.show(
                new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                        .setTitle(getString(R.string.announce_record_title, Labels.of(this, a)))
                        .setView(level)
                        .setCancelable(false)
                        .setPositiveButton(R.string.announce_record_save, (d, w) -> {
                            boolean saved = recorder.saveTo(store.recording(a.id));
                            com.kooo.evcam.ui.Notice.makeText(this, saved ? R.string.announce_record_saved
                                    : R.string.announce_record_short, Toast.LENGTH_SHORT).show();
                            show();
                        })
                        .setNeutralButton(R.string.announce_record_remove, (d, w) -> {
                            recorder.stop();
                            store.recording(a.id).delete();
                            show();
                        })
                        .setNegativeButton(android.R.string.cancel, (d, w) -> recorder.stop()));
        if (!store.hasRecording(a.id)) {
            dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setVisibility(View.GONE);
        }
        boolean started = recorder.start((percent, elapsedMs) -> runOnUiThread(() -> {
            StringBuilder bars = new StringBuilder();
            for (int i = 0; i < 20; i++) {
                bars.append(i < percent / 5 ? '█' : '░');
            }
            level.setText(getString(R.string.announce_record_level, elapsedMs / 1000f,
                    VoiceRecorder.MAX_SECONDS) + "\n" + bars);
        }));
        if (!started) {
            dialog.dismiss();
            com.kooo.evcam.ui.Notice.makeText(this, R.string.megaphone_need_mic, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        Announcement waiting = pendingRecord;
        pendingRecord = null;
        if (requestCode == REQUEST_MIC && waiting != null && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            record(waiting);
        }
    }

    private void paintVoice() {
        if (downloading) {
            return;
        }
        voice.setText(OfflineVoice.isReady(this) ? getString(R.string.announce_voice_ready)
                : getString(R.string.announce_voice_get, OfflineVoice.DOWNLOAD_MB));
    }

    /** Fetches 7xDash's own English voice (once); Thai phrases are recorded instead. */
    private void downloadVoice() {
        if (downloading || OfflineVoice.isReady(this)) {
            return;
        }
        downloading = true;
        android.content.Context app = getApplicationContext();
        new Thread(() -> {
            boolean ok = OfflineVoice.download(app, (percent, unpacking) -> runOnUiThread(() ->
                    voice.setText(unpacking ? getString(R.string.announce_voice_unpacking)
                            : getString(R.string.announce_voice_downloading, percent))));
            downloading = false;
            runOnUiThread(() -> {
                paintVoice();
                com.kooo.evcam.ui.Notice.makeText(app, ok ? R.string.announce_voice_done
                        : R.string.announce_voice_failed, Toast.LENGTH_SHORT).show();
            });
        }, "announce-voice").start();
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
