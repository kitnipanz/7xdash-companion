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
            String label = store.hasRecording(a.id)
                    ? getString(R.string.announce_in_your_voice, Labels.of(this, a)) : Labels.of(this, a);
            if (a.isTuned()) {
                label = playsLikeTape(a) ? getString(R.string.announce_tune_badge_tape, label, a.speed)
                        : getString(R.string.announce_tune_badge_voice, label, a.speed, a.pitch);
                if (!playsLikeTape(a) && a.voice != Announcement.VOICE_STANDARD) {
                    label += "  ·  " + voiceName(a.voice);
                }
            }
            ((TextView) row.findViewById(R.id.announce_row_text)).setText(label);
            row.findViewById(R.id.announce_row_tune).setOnClickListener(v -> tune(a));
            View mic = row.findViewById(R.id.announce_row_record);
            mic.setVisibility(a.isSound() ? View.GONE : View.VISIBLE);
            mic.setOnClickListener(v -> record(a));
            View play = row.findViewById(R.id.announce_row_play);
            // Tap checks it inside the car; long-press plays it outside, as it will be heard
            play.setOnClickListener(v -> Announcer.preview(this, a, "settings"));
            play.setOnLongClickListener(v -> {
                Announcer.play(this, a, "settings, outside");
                com.kooo.evcam.ui.Notice.makeText(this, R.string.announce_play_outside, Toast.LENGTH_SHORT).show();
                return true;
            });
            row.findViewById(R.id.announce_row_delete).setOnClickListener(v -> {
                store.remove(a.id);
                show();
            });
            rows.addView(row);
        }
        Announcement sentry = store.find(store.sentryId());
        sentryValue.setText(sentry == null ? getString(R.string.announce_sentry_none) : Labels.of(this, sentry));
    }

    /** A recording or a sound has no separate pitch: speed changes both, like a tape. */
    private boolean playsLikeTape(Announcement a) {
        return a.isSound() || store.hasRecording(a.id);
    }

    /** Speed and pitch for one announcement, with a preview; saved only on Save. */
    private void tune(Announcement a) {
        boolean tape = playsLikeTape(a);
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(24 * density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView speedLabel = tuneLabel();
        com.google.android.material.slider.Slider speed = tuneSlider(a.speed);
        TextView pitchLabel = tuneLabel();
        com.google.android.material.slider.Slider pitch = tuneSlider(tape ? 100 : a.pitch);
        int[] voice = {a.voice};
        Runnable[] repaintVoice = {() -> { }};
        if (!tape && !a.isThai()) {
            TextView voiceRow = tuneLabel();
            voiceRow.setBackgroundResource(R.drawable.bg_pref_row);
            int rowPad = Math.round(14 * density);
            voiceRow.setPadding(rowPad, rowPad, rowPad, rowPad);
            Runnable paintVoice = () -> voiceRow.setText(OfflineVoice.isReady(this)
                    ? getString(R.string.announce_voice_pick, voiceName(voice[0]))
                    : getString(R.string.announce_voice_needs));
            paintVoice.run();
            repaintVoice[0] = paintVoice;
            voiceRow.setOnClickListener(v -> {
                if (OfflineVoice.isReady(this)) {
                    pickVoice(voice[0], picked -> {
                        voice[0] = picked;
                        paintVoice.run();
                        Announcer.preview(this, a.withTune(Math.round(speed.getValue()),
                                Math.round(pitch.getValue()), picked), "tune");
                    });
                }
            });
            box.addView(voiceRow);
        }
        box.addView(speedLabel);
        box.addView(speed);
        if (tape) {
            TextView note = tuneLabel();
            note.setTextColor(getColor(R.color.text_secondary));
            note.setText(R.string.announce_tune_tape);
            box.addView(note);
        } else {
            box.addView(pitchLabel);
            box.addView(pitch);
        }
        Runnable paint = () -> {
            speedLabel.setText(getString(R.string.announce_tune_speed, Math.round(speed.getValue())));
            pitchLabel.setText(getString(R.string.announce_tune_pitch, Math.round(pitch.getValue())));
        };
        paint.run();
        speed.addOnChangeListener((s, value, fromUser) -> paint.run());
        pitch.addOnChangeListener((s, value, fromUser) -> paint.run());
        androidx.appcompat.app.AlertDialog dialog = com.kooo.evcam.ui.CamDialogs.show(
                new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                        .setTitle(getString(R.string.announce_tune_title) + ": " + Labels.of(this, a))
                        .setView(box)
                        .setPositiveButton(R.string.announce_tune_save, (d, w) -> {
                            Announcer.stop();
                            store.setTune(a.id, Math.round(speed.getValue()), Math.round(pitch.getValue()),
                                    voice[0]);
                            show();
                        })
                        .setNeutralButton(R.string.announce_tune_preview, null)
                        .setNegativeButton(R.string.announce_tune_reset, null)
                        .setOnDismissListener(d -> Announcer.stop()));
        // Play and Reset keep the dialog open
        dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener(v ->
                Announcer.preview(this, a.withTune(Math.round(speed.getValue()), Math.round(pitch.getValue()),
                        voice[0]), "tune"));
        dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).setOnClickListener(v -> {
            speed.setValue(100);
            pitch.setValue(100);
            voice[0] = Announcement.VOICE_STANDARD;
            repaintVoice[0].run();
        });
    }

    private String voiceName(int voice) {
        for (int[] v : OfflineVoice.VOICES) {
            if (v[0] == voice) {
                return getString(v[1]);
            }
        }
        return getString(R.string.announce_voice_standard);
    }

    /** One of 7xDash's English voices; each pick plays a sample. */
    private void pickVoice(int current, java.util.function.IntConsumer picked) {
        String[] names = new String[OfflineVoice.VOICES.length + 1];
        names[0] = getString(R.string.announce_voice_standard);
        int checked = 0;
        for (int i = 0; i < OfflineVoice.VOICES.length; i++) {
            names[i + 1] = getString(OfflineVoice.VOICES[i][1]);
            if (OfflineVoice.VOICES[i][0] == current) {
                checked = i + 1;
            }
        }
        com.kooo.evcam.ui.CamDialogs.show(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.announce_voice_title)
                .setSingleChoiceItems(names, checked, (d, which) ->
                        picked.accept(which == 0 ? Announcement.VOICE_STANDARD : OfflineVoice.VOICES[which - 1][0]))
                .setPositiveButton(android.R.string.ok, null));
    }

    private TextView tuneLabel() {
        TextView label = new TextView(this);
        label.setTextAppearance(R.style.TextAppearance_Cam_Body);
        label.setTextColor(getColor(R.color.text_primary));
        label.setPadding(0, Math.round(8 * getResources().getDisplayMetrics().density), 0, 0);
        return label;
    }

    private com.google.android.material.slider.Slider tuneSlider(int value) {
        com.google.android.material.slider.Slider slider = new com.google.android.material.slider.Slider(this);
        slider.setValueFrom(Announcement.MIN_TUNE);
        slider.setValueTo(Announcement.MAX_TUNE);
        slider.setStepSize(5);
        slider.setValue(Math.round(value / 5f) * 5);
        slider.setLabelFormatter(v -> Math.round(v) + "%");
        return slider;
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
