package com.kooo.evcam.settings;

import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.input.Shortcut;
import com.kooo.evcam.input.ShortcutAction;
import com.kooo.evcam.input.ShortcutBook;
import com.kooo.evcam.input.SpeakCapture;
import com.kooo.evcam.input.SpeakPlan;

import java.util.ArrayList;
import java.util.List;

/** Button, volume, and speaker for talking outside. Defaults match the first outside bus. */
public class TapToSpeakFragment extends Fragment implements SpeakCapture.Listener {

    private AppConfig config;
    private TextView buttonValue;
    private TextView holdRow;
    private TextView onceRow;
    private LinearLayout secondsRow;
    private TextView volumeValue;
    private SeekBar volume;
    private LinearLayout speakers;
    private boolean listening;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_tap_to_speak, container, false);
        config = new AppConfig(requireContext());
        buttonValue = root.findViewById(R.id.tap_speak_button_value);
        holdRow = root.findViewById(R.id.tap_speak_hold);
        onceRow = root.findViewById(R.id.tap_speak_once);
        secondsRow = root.findViewById(R.id.tap_speak_seconds);
        volumeValue = root.findViewById(R.id.tap_speak_volume_value);
        volume = root.findViewById(R.id.tap_speak_volume);
        speakers = root.findViewById(R.id.tap_speak_speakers);
        root.findViewById(R.id.tap_speak_button_set).setOnClickListener(v -> arm());
        holdRow.setOnClickListener(v -> chooseMode(false));
        onceRow.setOnClickListener(v -> chooseMode(true));
        volume.setProgress(config.getSpeakVolume());
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    config.setSpeakVolume(progress);
                }
                showVolume();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        buildSeconds();
        paint();
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        paint();
    }

    @Override
    public void onPause() {
        listening = false;
        SpeakCapture.disarm();
        super.onPause();
    }

    @Override
    public void onSaved() {
        listening = false;
        if (isAdded()) {
            paint();
        }
    }

    private void arm() {
        listening = true;
        SpeakCapture.arm(this);
        paint();
    }

    private void chooseMode(boolean once) {
        config.setSpeakMode(once ? SpeakPlan.MODE_ONCE : SpeakPlan.MODE_HOLD);
        paint();
    }

    private void buildSeconds() {
        secondsRow.removeAllViews();
        for (int seconds : SpeakPlan.onceSeconds()) {
            TextView chip = new TextView(requireContext());
            chip.setText(getString(R.string.tap_speak_seconds, seconds));
            chip.setTextAppearance(R.style.TextAppearance_Cam_Body);
            chip.setBackgroundResource(R.drawable.bg_pref_row);
            int pad = dp(14);
            chip.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            params.setMarginEnd(dp(8));
            chip.setLayoutParams(params);
            chip.setOnClickListener(v -> {
                config.setSpeakSeconds(seconds);
                config.setSpeakMode(SpeakPlan.MODE_ONCE);
                paint();
            });
            chip.setTag(seconds);
            secondsRow.addView(chip);
        }
    }

    private void paint() {
        if (!isAdded()) {
            return;
        }
        buttonValue.setText(listening
                ? getString(R.string.tap_speak_button_wait)
                : buttonLabel());
        boolean once = SpeakPlan.isOnce(config.getSpeakMode());
        holdRow.setTextColor(getColor(once ? R.color.pref_summary : R.color.energy));
        onceRow.setTextColor(getColor(once ? R.color.energy : R.color.pref_summary));
        secondsRow.setVisibility(once ? View.VISIBLE : View.GONE);
        int chosen = config.getSpeakSeconds();
        for (int i = 0; i < secondsRow.getChildCount(); i++) {
            View child = secondsRow.getChildAt(i);
            boolean on = child.getTag() instanceof Integer && (Integer) child.getTag() == chosen;
            ((TextView) child).setTextColor(getColor(on ? R.color.energy : R.color.pref_title));
        }
        showVolume();
        showSpeakers();
    }

    private void showVolume() {
        volumeValue.setText(getString(R.string.tap_speak_volume_value, config.getSpeakVolume()));
    }

    private String buttonLabel() {
        List<Shortcut> items = ShortcutBook.parse(config.getButtonShortcuts());
        for (Shortcut item : items) {
            if (item != null && (ShortcutAction.HOLD_SPEAK.key.equals(item.action)
                    || ShortcutAction.TOGGLE_SPEAK.key.equals(item.action))) {
                String key = item.keyCode == 0
                        ? getString(R.string.shortcut_scan, item.scanCode)
                        : KeyEvent.keyCodeToString(item.keyCode);
                return getString(R.string.tap_speak_button_saved, key);
            }
        }
        return getString(R.string.tap_speak_button_none);
    }

    private void showSpeakers() {
        speakers.removeAllViews();
        String saved = config.getSpeakSpeaker();
        AudioManager manager = requireContext().getSystemService(AudioManager.class);
        AudioDeviceInfo[] devices = manager == null
                ? null : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        List<String> seen = new ArrayList<>();
        if (devices != null) {
            for (AudioDeviceInfo device : devices) {
                if (device == null || !device.isSink()) {
                    continue;
                }
                String address = device.getAddress();
                if (address == null || address.isEmpty() || seen.contains(address)) {
                    continue;
                }
                seen.add(address);
                CharSequence product = device.getProductName();
                String title = product == null || product.length() == 0
                        ? address : product + " · " + address;
                addSpeaker(title, address, saved.equals(address));
            }
        }
        if (!seen.contains(saved)) {
            addSpeaker(getString(R.string.tap_speak_speaker_default) + " · " + saved, saved, true);
        }
    }

    private void addSpeaker(String title, String address, boolean selected) {
        TextView row = new TextView(requireContext());
        row.setText(title);
        row.setTextAppearance(R.style.TextAppearance_Cam_Body);
        row.setTextColor(getColor(selected ? R.color.energy : R.color.pref_title));
        row.setBackgroundResource(R.drawable.bg_pref_row);
        int pad = dp(14);
        row.setPadding(dp(20), pad, dp(20), pad);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        row.setLayoutParams(params);
        row.setOnClickListener(v -> {
            config.setSpeakSpeaker(address);
            paint();
        });
        speakers.addView(row);
    }

    private int getColor(int id) {
        return requireContext().getColor(id);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
