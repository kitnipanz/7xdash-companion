package com.kooo.evcam.audio;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.kooo.evcam.R;

/**
 * Fifteen vertical faders (EqBarView) side by side, like a mixing desk, and two presets.
 * The curve is applied as a fader moves.
 */
public class CabinEqFragment extends Fragment {

    private CabinEqStore store;
    private final EqBarView[] sliders = new EqBarView[CabinEq.BANDS];
    private final TextView[] db = new TextView[CabinEq.BANDS];
    private SwitchCompat enabled;
    private TextView status;
    private TextView preset0;
    private TextView preset1;
    private boolean painting;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_cabin_eq, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        store = new CabinEqStore(requireContext());
        enabled = view.findViewById(R.id.eq_enabled);
        status = view.findViewById(R.id.eq_status);
        view.findViewById(R.id.eq_back).setOnClickListener(v ->
                requireActivity().getOnBackPressedDispatcher().onBackPressed());
        ((TextView) view.findViewById(R.id.eq_scale_top)).setText(CabinEq.formatDb(CabinEq.GAIN_MAX));
        ((TextView) view.findViewById(R.id.eq_scale_mid)).setText(CabinEq.formatDb(0));
        ((TextView) view.findViewById(R.id.eq_scale_bottom)).setText(CabinEq.formatDb(CabinEq.GAIN_MIN));
        preset0 = view.findViewById(R.id.eq_preset_0);
        preset1 = view.findViewById(R.id.eq_preset_1);
        LinearLayout bands = view.findViewById(R.id.eq_bands);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (int i = 0; i < CabinEq.BANDS; i++) {
            View row = inflater.inflate(R.layout.item_eq_band, bands, false);
            TextView freq = row.findViewById(R.id.eq_freq);
            freq.setText(CabinEq.label(CabinEq.CENTRES_HZ[i]));
            sliders[i] = row.findViewById(R.id.eq_slider);
            db[i] = row.findViewById(R.id.eq_db);
            int band = i;
            sliders[i].setOnChange((value, fromUser) -> {
                db[band].setText(CabinEq.formatDb(value));
                if (fromUser && !painting) {
                    saveAndApply();
                }
            });
            bands.addView(row);
        }
        enabled.setOnCheckedChangeListener((button, on) -> {
            if (painting) {
                return;
            }
            store.setEnabled(on);
            show(CabinEqEngine.apply(on, current()));
        });
        view.findViewById(R.id.eq_flat).setOnClickListener(v -> {
            store.setPreset(store.slot(), CabinEq.flat());
            paint(store.preset(store.slot()));
            show(CabinEqEngine.apply(store.enabled(), CabinEq.flat()));
        });
        preset0.setOnClickListener(v -> selectSlot(0));
        preset1.setOnClickListener(v -> selectSlot(1));
        paint(store.preset(store.slot()));
        painting = true;
        enabled.setChecked(store.enabled());
        painting = false;
        paintSlots();
        if (store.enabled()) {
            show(CabinEqEngine.apply(true, current()));
        }
    }

    private void selectSlot(int slot) {
        store.setSlot(slot);
        paint(store.preset(slot));
        paintSlots();
        if (store.enabled()) {
            show(CabinEqEngine.apply(true, current()));
        }
    }

    private void saveAndApply() {
        float[] gains = current();
        store.setPreset(store.slot(), gains);
        if (store.enabled()) {
            show(CabinEqEngine.apply(true, gains));
        }
    }

    private float[] current() {
        float[] gains = new float[CabinEq.BANDS];
        for (int i = 0; i < CabinEq.BANDS; i++) {
            gains[i] = sliders[i].getValue();
        }
        return gains;
    }

    private void paint(float[] gains) {
        painting = true;
        for (int i = 0; i < CabinEq.BANDS; i++) {
            float gain = CabinEq.clamp(gains[i]);
            sliders[i].setValue(gain);
            db[i].setText(CabinEq.formatDb(gain));
        }
        painting = false;
    }

    private void paintSlots() {
        tint(preset0, store.slot() == 0);
        tint(preset1, store.slot() == 1);
    }

    /** 两段开关里选中的那一段：实心橙、白字（bg_segment 认 activated）。 */
    private void tint(TextView segment, boolean selected) {
        segment.setActivated(selected);
        segment.setTextColor(ContextCompat.getColor(requireContext(),
                selected ? R.color.on_energy : R.color.text_secondary));
    }

    private void show(boolean ok) {
        status.setVisibility(ok ? View.GONE : View.VISIBLE);
    }
}
