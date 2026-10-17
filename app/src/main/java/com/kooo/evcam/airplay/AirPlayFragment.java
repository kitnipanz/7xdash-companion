package com.kooo.evcam.airplay;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.kooo.evcam.R;

/** The AirPlay page: on/off, what the receiver is doing, and how to mirror from an iPhone. */
public class AirPlayFragment extends Fragment {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView state;
    private TextView name;
    private TextView steps;
    private ImageView icon;
    private SwitchCompat enabled;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            paint();
            handler.postDelayed(this, 1000);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_airplay, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        state = view.findViewById(R.id.airplay_state);
        name = view.findViewById(R.id.airplay_name);
        steps = view.findViewById(R.id.airplay_steps);
        icon = view.findViewById(R.id.airplay_icon);
        enabled = view.findViewById(R.id.airplay_enabled);
        view.findViewById(R.id.airplay_back).setOnClickListener(v ->
                requireActivity().getOnBackPressedDispatcher().onBackPressed());
        enabled.setChecked(AirPlayService.enabled(requireContext()));
        enabled.setOnCheckedChangeListener((b, on) -> {
            AirPlayService.setEnabled(requireContext(), on);
            handler.postDelayed(this::paint, 300);
        });
        paint();
    }

    @Override
    public void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override
    public void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    private void paint() {
        if (state == null || !isAdded()) {
            return;
        }
        boolean on = AirPlayService.isRunning();
        String deviceName = on ? AirPlayService.deviceName() : getString(R.string.airplay_device_name);
        String now = AirPlayService.state();
        state.setText(on ? (now.isEmpty() ? getString(R.string.airplay_state_starting) : now)
                : getString(R.string.airplay_state_off));
        name.setText(getString(R.string.airplay_name_line, deviceName));
        steps.setText(getString(R.string.airplay_steps, deviceName));
        icon.setImageTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(),
                on ? R.color.energy : R.color.text_secondary)));
    }
}
