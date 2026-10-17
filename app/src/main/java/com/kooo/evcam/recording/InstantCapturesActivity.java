package com.kooo.evcam.recording;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.R;
import com.kooo.evcam.ui.CamDialogs;
import com.kooo.evcam.zeekr.RecordingTimeline;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Plays clips in the instant captures folder, and deletes one or all of them. */
public class InstantCapturesActivity extends AppCompatActivity {

    private LinearLayout rows;
    private View body;
    private TextView empty;
    private TextView now;
    private View deleteAll;
    private ImageView playGlyph;
    private VideoView player;
    private File playing;
    private boolean reopen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_instant_captures);
        rows = findViewById(R.id.instant_rows);
        body = findViewById(R.id.instant_body);
        empty = findViewById(R.id.instant_empty);
        now = findViewById(R.id.instant_now);
        deleteAll = findViewById(R.id.instant_delete_all);
        playGlyph = findViewById(R.id.instant_play);
        player = findViewById(R.id.instant_player);
        findViewById(R.id.instant_close).setOnClickListener(v -> finish());
        deleteAll.setOnClickListener(v -> confirmDeleteAll());
        findViewById(R.id.instant_touch).setOnClickListener(v -> toggle());
        player.setOnPreparedListener(mp -> {
            playGlyph.setVisibility(View.GONE);
            player.start();
        });
        player.setOnCompletionListener(mp -> playGlyph.setVisibility(View.VISIBLE));
        player.setOnErrorListener((mp, what, extra) -> {
            playGlyph.setVisibility(View.VISIBLE);
            Toast.makeText(this, R.string.instant_captures_play_failed, Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reopen = true;
        show();
    }

    @Override
    protected void onPause() {
        player.stopPlayback();
        super.onPause();
    }

    private void show() {
        File dir = InstantClips.folder(this);
        List<File> clips = InstantClips.list(dir);
        rows.removeAllViews();
        boolean none = clips.isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        empty.setText(dir == null ? R.string.instant_captures_no_usb : R.string.instant_captures_empty);
        body.setVisibility(none ? View.GONE : View.VISIBLE);
        deleteAll.setEnabled(!none);
        if (playing != null && (dir == null || !InstantClips.owned(dir, playing))) {
            playing = null;
        }
        for (File clip : clips) {
            rows.addView(row(dir, clip));
        }
        if (playing != null && reopen) {
            play(playing);
        } else if (playing == null && !clips.isEmpty()) {
            play(clips.get(0));
        } else {
            highlight();
        }
        reopen = false;
    }

    private View row(File dir, File clip) {
        View line = LayoutInflater.from(this).inflate(R.layout.item_instant_clip, rows, false);
        line.setTag(clip);
        TextView label = line.findViewById(R.id.instant_label);
        TextView meta = line.findViewById(R.id.instant_meta);
        label.setText(clockOf(clip));
        meta.setText(metaOf(clip));
        line.findViewById(R.id.instant_open).setOnClickListener(v -> play(clip));
        line.findViewById(R.id.instant_delete).setOnClickListener(v -> confirmDelete(dir, clip));
        boolean on = clip.equals(playing);
        line.setBackgroundResource(on ? R.drawable.bg_editor_card_on : R.drawable.bg_editor_card);
        return line;
    }

    private void play(File clip) {
        playing = clip;
        now.setText(clockOf(clip));
        highlight();
        playGlyph.setVisibility(View.GONE);
        player.setVideoURI(Uri.fromFile(clip));
    }

    private void toggle() {
        if (playing == null) {
            return;
        }
        if (player.isPlaying()) {
            player.pause();
            playGlyph.setVisibility(View.VISIBLE);
        } else {
            playGlyph.setVisibility(View.GONE);
            player.start();
        }
    }

    private void highlight() {
        for (int i = 0; i < rows.getChildCount(); i++) {
            View child = rows.getChildAt(i);
            boolean on = playing != null && playing.equals(child.getTag());
            child.setBackgroundResource(on ? R.drawable.bg_editor_card_on : R.drawable.bg_editor_card);
        }
    }

    private void confirmDelete(File dir, File clip) {
        CamDialogs.showDestructive(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.instant_captures_delete_title)
                .setMessage(clockOf(clip))
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    if (clip.equals(playing)) {
                        player.stopPlayback();
                        playing = null;
                    }
                    InstantClips.delete(dir, clip);
                    show();
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    private void confirmDeleteAll() {
        File dir = InstantClips.folder(this);
        int count = InstantClips.list(dir).size();
        if (count == 0) {
            return;
        }
        CamDialogs.showDestructive(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.instant_captures_delete_all_title)
                .setMessage(getString(R.string.instant_captures_delete_all_msg, count))
                .setPositiveButton(R.string.instant_captures_delete_all, (dialog, which) -> {
                    player.stopPlayback();
                    playing = null;
                    InstantClips.deleteAll(dir);
                    show();
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    private static String clockOf(File clip) {
        long start = RecordingTimeline.parseStartEpochMs(clip.getName());
        if (start < 0) {
            return clip.getName();
        }
        return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(start));
    }

    private static String metaOf(File clip) {
        long start = RecordingTimeline.parseStartEpochMs(clip.getName());
        String when = start < 0
                ? ""
                : new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date(start));
        String size = sizeOf(clip.length());
        if (when.isEmpty()) {
            return size;
        }
        return when + "  ·  " + size;
    }

    private static String sizeOf(long bytes) {
        if (bytes < 1024L * 1024L) {
            return Math.max(1L, bytes / 1024L) + " KB";
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
