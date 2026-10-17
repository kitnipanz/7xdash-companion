package com.kooo.evcam.audio;

import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.kooo.evcam.R;
import com.kooo.evcam.ui.Notice;

import java.util.ArrayList;
import java.util.List;

/**
 * Fifteen vertical faders (EqBarView) side by side, like a mixing desk.
 *
 * <p>Presets: six built-in curves (Flat, Rock, Jazz, Hip-hop, Pop, K-Pop) and five of the user's
 * own (My 1 … My 5). Tapping one applies it at once. Moving a fader edits a "My" curve: on a My
 * preset it edits that one; on a built-in it copies the result into the last My preset used and
 * switches to it, so the built-ins never change. The curve is applied as a fader moves.</p>
 */
public class CabinEqFragment extends Fragment {

    private static final CabinEq.Builtin[] BUILTINS = CabinEq.Builtin.values();
    private static final int[] BUILTIN_LABELS = {
            R.string.eq_flat, R.string.eq_rock, R.string.eq_jazz,
            R.string.eq_hiphop, R.string.eq_pop, R.string.eq_kpop};

    private CabinEqStore store;
    private final EqBarView[] sliders = new EqBarView[CabinEq.BANDS];
    private final TextView[] db = new TextView[CabinEq.BANDS];
    private final List<TextView> chips = new ArrayList<>();
    private final List<String> chipKeys = new ArrayList<>();
    private SwitchCompat enabled;
    private TextView status;
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

        LinearLayout builtinRow = view.findViewById(R.id.eq_builtin);
        for (int i = 0; i < BUILTINS.length; i++) {
            addChip(builtinRow, getString(BUILTIN_LABELS[i]), CabinEqStore.BUILTIN + BUILTINS[i].name());
        }
        LinearLayout myRow = view.findViewById(R.id.eq_my);
        for (int i = 0; i < CabinEq.PRESETS; i++) {
            addChip(myRow, myName(i), CabinEqStore.MY + i);
        }

        LinearLayout bands = view.findViewById(R.id.eq_bands);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (int i = 0; i < CabinEq.BANDS; i++) {
            View column = inflater.inflate(R.layout.item_eq_band, bands, false);
            ((TextView) column.findViewById(R.id.eq_freq)).setText(CabinEq.label(CabinEq.CENTRES_HZ[i]));
            sliders[i] = column.findViewById(R.id.eq_slider);
            db[i] = column.findViewById(R.id.eq_db);
            int band = i;
            sliders[i].setOnChange((value, fromUser) -> {
                db[band].setText(CabinEq.formatDb(value));
                if (fromUser && !painting) {
                    edited();
                }
            });
            bands.addView(column);
        }

        enabled.setOnCheckedChangeListener((button, on) -> {
            if (painting) {
                return;
            }
            store.setEnabled(on);
            show(CabinEqEngine.apply(on, current()));
        });

        paint(store.currentGains());
        painting = true;
        enabled.setChecked(store.enabled());
        painting = false;
        paintChips();
        if (store.enabled()) {
            show(CabinEqEngine.apply(true, current()));
        }
    }

    private void addChip(LinearLayout row, String label, String key) {
        TextView chip = new TextView(requireContext());
        chip.setText(label);
        chip.setGravity(Gravity.CENTER);
        chip.setBackgroundResource(R.drawable.bg_segment);
        chip.setTextAppearance(R.style.TextAppearance_Cam_Body);
        int padH = Math.round(18 * getResources().getDisplayMetrics().density);
        chip.setPadding(padH, 0, padH, 0);
        chip.setMinWidth(Math.round(96 * getResources().getDisplayMetrics().density));
        chip.setOnClickListener(v -> choose(key));
        row.addView(chip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        chips.add(chip);
        chipKeys.add(key);
    }

    private String myName(int index) {
        return getString(R.string.eq_my, index + 1);
    }

    private void choose(String key) {
        store.choose(key);
        paint(store.gainsOf(key));
        paintChips();
        if (store.enabled()) {
            show(CabinEqEngine.apply(true, current()));
        }
    }

    /** A fader moved: save into a "My" preset (copying off a built-in first), then apply. */
    private void edited() {
        float[] gains = current();
        int my = CabinEqStore.myIndexOf(store.choice());
        if (my < 0) {
            my = store.lastMy();
            store.choose(CabinEqStore.MY + my);
            paintChips();
            Notice.makeText(requireContext(), getString(R.string.eq_saved_to, myName(my)),
                    Toast.LENGTH_SHORT).show();
        }
        store.setMy(my, gains);
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

    /** The chosen chip: solid Zeekr orange with white text (bg_segment reads activated). */
    private void paintChips() {
        String chosen = store.choice();
        for (int i = 0; i < chips.size(); i++) {
            boolean selected = chipKeys.get(i).equals(chosen);
            TextView chip = chips.get(i);
            chip.setActivated(selected);
            chip.setTextColor(ContextCompat.getColor(requireContext(),
                    selected ? R.color.on_energy : R.color.text_secondary));
        }
    }

    private void show(boolean ok) {
        status.setVisibility(ok ? View.GONE : View.VISIBLE);
    }
}
