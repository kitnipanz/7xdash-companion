package com.kooo.evcam.announce;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.ui.CamDialogs;

import java.util.ArrayList;
import java.util.List;

/** Speak notifications: on or off, which speaker, how loud, and a test. */
public final class NoticeVoiceDialog {

    private static final int VOLUME_MIN = 10;

    private NoticeVoiceDialog() {
    }

    public static void show(Context context, Runnable changed) {
        AppConfig config = new AppConfig(context);
        int pad = dp(context, 24);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, dp(context, 8), pad, 0);
        SwitchMaterial on = new SwitchMaterial(context);
        on.setText(R.string.notice_speak_switch);
        on.setTextAppearance(R.style.TextAppearance_Cam_Row);
        on.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        on.setChecked(config.isNoticeSpeakOn());
        box.addView(on);
        box.addView(words(context, context.getString(R.string.notice_speak_hint)));

        box.addView(words(context, context.getString(R.string.notice_speak_volume)));
        SeekBar volume = new SeekBar(context);
        volume.setMax(100 - VOLUME_MIN);
        volume.setProgress(config.getNoticeVolume() - VOLUME_MIN);
        volume.setContentDescription(context.getString(R.string.notice_speak_volume));
        box.addView(volume);
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    config.setNoticeVolume(progress + VOLUME_MIN);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        box.addView(words(context, context.getString(R.string.notice_speak_speaker)));
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        box.addView(list);
        paint(context, config, list);
        on.setOnCheckedChangeListener((b, checked) -> config.setNoticeSpeakOn(checked));

        ScrollView scroll = new ScrollView(context);
        scroll.addView(box);
        AlertDialog dialog = CamDialogs.show(new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.notice_speak_title)
                .setView(scroll)
                .setNeutralButton(R.string.notice_speak_test, null)
                .setPositiveButton(R.string.nav_hud_adjust_done, null));
        if (dialog == null) {
            return;
        }
        dialog.setOnDismissListener(d -> changed.run());
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> test(context, config));
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(Math.round(context.getResources().getDisplayMetrics().widthPixels * 0.6f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private static void test(Context context, AppConfig config) {
        if (config.getNoticeSpeaker().isEmpty()) {
            Toast.makeText(context, R.string.notice_speak_pick, Toast.LENGTH_SHORT).show();
            return;
        }
        String phrase = context.getString(R.string.notice_speak_test_phrase);
        Announcement a = Announcement.phrase("notice", phrase);
        Announcer.say(context, phrase, config.getNoticeSpeaker(), config.getNoticeVolume(), failure ->
                // Not Notice: it would try to say this too
                Toast.makeText(context, a.isThai() && failure == R.string.announce_record_needed
                        ? R.string.notice_speak_no_thai : failure, Toast.LENGTH_LONG).show());
    }

    /** Output speakers by address, ones that sound like the headrest first. */
    private static List<String> speakers(Context context, String picked) {
        List<String> found = new ArrayList<>();
        AudioManager manager = context.getSystemService(AudioManager.class);
        AudioDeviceInfo[] devices = manager == null ? null : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        if (devices != null) {
            for (AudioDeviceInfo device : devices) {
                String address = device == null || !device.isSink() ? null : device.getAddress();
                if (address != null && !address.isEmpty() && !found.contains(address)) {
                    found.add(address);
                }
            }
        }
        if (!picked.isEmpty() && !found.contains(picked)) {
            found.add(picked);
        }
        found.sort((a, b) -> Boolean.compare(NoticeVoice.soundsLikeHeadrest(b), NoticeVoice.soundsLikeHeadrest(a)));
        return found;
    }

    private static void paint(Context context, AppConfig config, LinearLayout list) {
        list.removeAllViews();
        String picked = config.getNoticeSpeaker();
        List<String> speakers = speakers(context, picked);
        if (speakers.isEmpty()) {
            list.addView(words(context, context.getString(R.string.notice_speak_none)));
        }
        for (String address : speakers) {
            boolean selected = address.equals(picked);
            TextView row = new TextView(context);
            row.setText((selected ? "\u2713  " : "") + address);
            row.setTextAppearance(R.style.TextAppearance_Cam_Body);
            row.setTextColor(ContextCompat.getColor(context, selected ? R.color.energy : R.color.pref_title));
            row.setBackgroundResource(R.drawable.bg_pref_row);
            int pad = dp(context, 14);
            row.setPadding(dp(context, 20), pad, dp(context, 20), pad);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(context, 8);
            row.setOnClickListener(v -> {
                config.setNoticeSpeaker(address);
                paint(context, config, list);
            });
            list.addView(row, params);
        }
    }

    private static TextView words(Context context, String value) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextAppearance(R.style.TextAppearance_Cam_Body);
        view.setTextColor(ContextCompat.getColor(context, R.color.text_secondary));
        view.setPadding(0, dp(context, 8), 0, dp(context, 4));
        return view;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
