package com.kooo.evcam.audio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;
import com.kooo.evcam.R;
import com.kooo.evcam.ui.Notice;

import java.util.ArrayList;
import java.util.List;

/**
 * Fifteen vertical faders (EqBarView) side by side, like a mixing desk, with the real response
 * drawn over them and an optional live spectrum behind.
 *
 * <p>Presets: sound curves (Flat, Natural, Warm, …), genre curves (Rock, Pop, …) and five of the
 * user's own (My 1 … My 5, renamed by holding them). Tapping one applies it at once. Moving a
 * fader edits a "My" curve: on a My preset it edits that one; on a built-in it copies the result
 * into the last My preset used and switches to it, so the built-ins never change.</p>
 *
 * <p>Under the faders: tone and dynamics (bass, treble, loudness, compressor, pre-amp, balance),
 * the same for every preset. Header: spectrum, hold-for-A/B (B is the untouched sound) and a
 * floating mini EQ.</p>
 */
public class CabinEqFragment extends Fragment {

    static final CabinEq.Builtin[] SOUND = {
            CabinEq.Builtin.FLAT, CabinEq.Builtin.NATURAL, CabinEq.Builtin.WARM, CabinEq.Builtin.VOCAL,
            CabinEq.Builtin.BASS, CabinEq.Builtin.LIVE, CabinEq.Builtin.VSHAPE};
    static final CabinEq.Builtin[] GENRE = {
            CabinEq.Builtin.ROCK, CabinEq.Builtin.POP, CabinEq.Builtin.KPOP, CabinEq.Builtin.HIPHOP,
            CabinEq.Builtin.JAZZ, CabinEq.Builtin.CLASSICAL, CabinEq.Builtin.DANCE,
            CabinEq.Builtin.ACOUSTIC, CabinEq.Builtin.ELECTRONIC, CabinEq.Builtin.PODCAST};

    static int labelOf(CabinEq.Builtin b) {
        switch (b) {
            case NATURAL: return R.string.eq_natural;
            case WARM: return R.string.eq_warm;
            case VOCAL: return R.string.eq_vocal;
            case BASS: return R.string.eq_bass;
            case LIVE: return R.string.eq_live;
            case VSHAPE: return R.string.eq_vshape;
            case ROCK: return R.string.eq_rock;
            case POP: return R.string.eq_pop;
            case KPOP: return R.string.eq_kpop;
            case HIPHOP: return R.string.eq_hiphop;
            case JAZZ: return R.string.eq_jazz;
            case CLASSICAL: return R.string.eq_classical;
            case DANCE: return R.string.eq_dance;
            case ACOUSTIC: return R.string.eq_acoustic;
            case ELECTRONIC: return R.string.eq_electronic;
            case PODCAST: return R.string.eq_podcast;
            default: return R.string.eq_flat;
        }
    }

    private CabinEqStore store;
    private final EqBarView[] sliders = new EqBarView[CabinEq.BANDS];
    private final TextView[] db = new TextView[CabinEq.BANDS];
    private final List<TextView> chips = new ArrayList<>();
    private final List<String> chipKeys = new ArrayList<>();
    private LinearLayout myRow;
    private SwitchCompat enabled;
    private TextView status;
    private EqOverlayView curveView;
    private EqOverlayView spectrumView;
    private MaterialButton spectrumButton;
    private final EqSpectrum spectrum = new EqSpectrum();
    private boolean spectrumOn;
    private boolean painting;
    private EqTone tone;
    private ToneCell bass, treble, loudness, compressor, preamp, balance;

    private final ActivityResultLauncher<String> micPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    setSpectrum(true);
                } else {
                    Notice.makeText(requireContext(), R.string.eq_spectrum_need_mic, Toast.LENGTH_LONG).show();
                }
            });

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
        tone = store.tone();
        enabled = view.findViewById(R.id.eq_enabled);
        status = view.findViewById(R.id.eq_status);
        curveView = view.findViewById(R.id.eq_curve_view);
        spectrumView = view.findViewById(R.id.eq_spectrum_view);
        ((TextView) view.findViewById(R.id.eq_hint)).setText(
                getString(R.string.eq_hint) + " " + getString(R.string.eq_tips));
        view.findViewById(R.id.eq_back).setOnClickListener(v ->
                requireActivity().getOnBackPressedDispatcher().onBackPressed());
        ((TextView) view.findViewById(R.id.eq_scale_top)).setText(CabinEq.formatDb(CabinEq.GAIN_MAX));
        ((TextView) view.findViewById(R.id.eq_scale_mid)).setText(CabinEq.formatDb(0));
        ((TextView) view.findViewById(R.id.eq_scale_bottom)).setText(CabinEq.formatDb(CabinEq.GAIN_MIN));

        LinearLayout soundRow = view.findViewById(R.id.eq_builtin);
        for (CabinEq.Builtin b : SOUND) {
            addChip(soundRow, getString(labelOf(b)), CabinEqStore.BUILTIN + b.name(), false);
        }
        LinearLayout genreRow = view.findViewById(R.id.eq_genre);
        for (CabinEq.Builtin b : GENRE) {
            addChip(genreRow, getString(labelOf(b)), CabinEqStore.BUILTIN + b.name(), false);
        }
        myRow = view.findViewById(R.id.eq_my);
        for (int i = 0; i < CabinEq.PRESETS; i++) {
            addChip(myRow, myName(i), CabinEqStore.MY + i, true);
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
                paintCurve();
                if (fromUser && !painting) {
                    edited();
                }
            });
            bands.addView(column);
        }
        curveView.attach(sliders);
        spectrumView.attach(sliders);
        bands.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> paintCurve());

        buildTone(view.findViewById(R.id.eq_tone));

        enabled.setOnCheckedChangeListener((button, on) -> {
            if (painting) {
                return;
            }
            store.setEnabled(on);
            apply();
        });

        spectrumButton = view.findViewById(R.id.eq_spectrum);
        spectrumButton.setOnClickListener(v -> {
            if (spectrumOn) {
                setSpectrum(false);
            } else if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                setSpectrum(true);
            } else {
                micPermission.launch(Manifest.permission.RECORD_AUDIO);
            }
        });
        bindAb(view.findViewById(R.id.eq_ab));
        view.findViewById(R.id.eq_float).setOnClickListener(v -> EqFloatWindow.show(requireActivity()));

        paint(store.currentGains());
        painting = true;
        enabled.setChecked(store.enabled());
        painting = false;
        paintChips();
        if (store.enabled()) {
            apply();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (store == null) {
            return;
        }
        // the floating EQ may have changed things while this screen was away
        tone = store.tone();
        paintTone();
        paint(store.currentGains());
        paintChips();
        if (enabled != null && enabled.isChecked() != store.enabled()) {
            painting = true;
            enabled.setChecked(store.enabled());
            painting = false;
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        setSpectrum(false);
        CabinEqEngine.setBypassed(false);
    }

    private void apply() {
        boolean ok = CabinEqEngine.apply(store.enabled(), current(), tone);
        status.setVisibility(ok ? View.GONE : View.VISIBLE);
        if (ok && store.enabled()) {
            compressor.setEnabled(CabinEqEngine.hasDynamics());
        }
    }

    // ---- presets ----

    private void addChip(LinearLayout row, String label, String key, boolean renamable) {
        TextView chip = new TextView(requireContext());
        chip.setText(label);
        chip.setGravity(Gravity.CENTER);
        chip.setBackgroundResource(R.drawable.bg_segment);
        chip.setTextAppearance(R.style.TextAppearance_Cam_Body);
        int padH = Math.round(18 * getResources().getDisplayMetrics().density);
        chip.setPadding(padH, 0, padH, 0);
        chip.setMinWidth(Math.round(96 * getResources().getDisplayMetrics().density));
        chip.setOnClickListener(v -> choose(key));
        if (renamable) {
            chip.setOnLongClickListener(v -> {
                rename(CabinEqStore.myIndexOf(key), chip);
                return true;
            });
        }
        row.addView(chip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        chips.add(chip);
        chipKeys.add(key);
    }

    private String myName(int index) {
        String named = store.myName(index);
        return named.isEmpty() ? getString(R.string.eq_my, index + 1) : named;
    }

    private void rename(int index, TextView chip) {
        EditText input = new EditText(requireContext());
        input.setSingleLine(true);
        input.setText(store.myName(index));
        input.setHint(getString(R.string.eq_my, index + 1));
        input.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(16)});
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(requireContext());
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        com.kooo.evcam.ui.CamDialogs.show(new MaterialAlertDialogBuilder(requireContext(),
                R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.eq_rename_title)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    store.setMyName(index, input.getText().toString());
                    chip.setText(myName(index));
                })
                .setNegativeButton(android.R.string.cancel, null));
    }

    private void choose(String key) {
        store.choose(key);
        paint(store.gainsOf(key));
        paintChips();
        if (store.enabled()) {
            apply();
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
            apply();
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
        paintCurve();
    }

    private void paintCurve() {
        if (curveView != null && tone != null) {
            curveView.showCurve(tone.shapeSliders(current()));
        }
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

    // ---- spectrum, A/B ----

    private void setSpectrum(boolean on) {
        if (on && !spectrum.start(levels -> spectrumView.showLevels(levels))) {
            Notice.makeText(requireContext(), R.string.eq_spectrum_refused, Toast.LENGTH_LONG).show();
            on = false;
        }
        if (!on) {
            spectrum.stop();
            if (spectrumView != null) {
                spectrumView.showLevels(null);
            }
        }
        spectrumOn = on;
        if (spectrumButton != null) {
            spectrumButton.setActivated(on);
            spectrumButton.setAlpha(on ? 1f : 0.75f);
        }
    }

    /** Hold: B, the untouched sound. Let go: A, the curve. */
    @SuppressLint("ClickableViewAccessibility")
    private void bindAb(MaterialButton ab) {
        ab.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    CabinEqEngine.setBypassed(true);
                    ab.setText(R.string.eq_ab_on);
                    v.setPressed(true);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    CabinEqEngine.setBypassed(false);
                    ab.setText(R.string.eq_ab);
                    v.setPressed(false);
                    return true;
                default:
                    return true;
            }
        });
    }

    // ---- tone and dynamics ----

    /** One control in the tone row: name, value (tap resets) and a slider. */
    private final class ToneCell {
        final Slider slider;
        final TextView value;
        final java.util.function.IntFunction<String> format;
        final int reset;

        ToneCell(LinearLayout row, int label, int from, int to, int reset,
                 java.util.function.IntFunction<String> format,
                 java.util.function.IntConsumer changed) {
            this.format = format;
            this.reset = reset;
            float density = getResources().getDisplayMetrics().density;
            LinearLayout cell = new LinearLayout(requireContext());
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setBackgroundResource(R.drawable.bg_pref_row);
            int pad = Math.round(12 * density);
            cell.setPadding(pad, pad, pad, pad / 2);
            LinearLayout head = new LinearLayout(requireContext());
            head.setOrientation(LinearLayout.HORIZONTAL);
            TextView name = new TextView(requireContext());
            name.setText(label);
            name.setTextAppearance(R.style.TextAppearance_Cam_Body);
            name.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary));
            value = new TextView(requireContext());
            value.setTextAppearance(R.style.TextAppearance_Cam_Body);
            value.setTextColor(ContextCompat.getColor(requireContext(), R.color.energy_text));
            value.setGravity(Gravity.END);
            head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            head.addView(value);
            slider = new Slider(requireContext());
            slider.setValueFrom(from);
            slider.setValueTo(to);
            slider.setStepSize(1);
            slider.setLabelBehavior(com.google.android.material.slider.LabelFormatter.LABEL_GONE);
            slider.setTickVisible(false);
            slider.addOnChangeListener((s, v, fromUser) -> {
                value.setText(format.apply(Math.round(v)));
                if (fromUser) {
                    changed.accept(Math.round(v));
                }
            });
            value.setOnClickListener(v -> {
                slider.setValue(reset);
                changed.accept(reset);
            });
            cell.addView(head);
            cell.addView(slider);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            lp.setMarginEnd(Math.round(8 * density));
            row.addView(cell, lp);
        }

        void set(int v) {
            slider.setValue(v);
            value.setText(format.apply(v));
        }

        void setEnabled(boolean on) {
            slider.setEnabled(on);
            value.setAlpha(on ? 1f : 0.4f);
        }
    }

    private String dbText(int v) {
        return getString(R.string.eq_db, v > 0 ? "+" + v : Integer.toString(v));
    }

    private void buildTone(LinearLayout row) {
        bass = new ToneCell(row, R.string.eq_tone_bass, EqTone.TONE_MIN, EqTone.TONE_MAX, 0,
                this::dbText, v -> toneChanged(tone.withBass(v)));
        treble = new ToneCell(row, R.string.eq_tone_treble, EqTone.TONE_MIN, EqTone.TONE_MAX, 0,
                this::dbText, v -> toneChanged(tone.withTreble(v)));
        loudness = new ToneCell(row, R.string.eq_tone_loudness, 0, 100, 0,
                v -> v == 0 ? getString(R.string.eq_off) : Integer.toString(v),
                v -> toneChanged(tone.withLoudness(v)));
        compressor = new ToneCell(row, R.string.eq_tone_compressor, 0, 100, 0,
                v -> v == 0 ? getString(R.string.eq_off) : Integer.toString(v),
                v -> {
                    if (!CabinEqEngine.hasDynamics()) {
                        Notice.makeText(requireContext(), R.string.eq_compressor_refused,
                                Toast.LENGTH_SHORT).show();
                    }
                    toneChanged(tone.withCompressor(v));
                });
        preamp = new ToneCell(row, R.string.eq_tone_preamp, EqTone.PREAMP_MIN, EqTone.PREAMP_MAX, 0,
                this::dbText, v -> toneChanged(tone.withPreamp(v)));
        balance = new ToneCell(row, R.string.eq_tone_balance, -100, 100, 0,
                v -> v == 0 ? getString(R.string.eq_centre) : (v < 0 ? "L " + -v : "R " + v),
                v -> toneChanged(tone.withBalance(v)));
        paintTone();
    }

    private void paintTone() {
        if (bass == null) {
            return;
        }
        bass.set(tone.bass);
        treble.set(tone.treble);
        loudness.set(tone.loudness);
        compressor.set(tone.compressor);
        preamp.set(tone.preamp);
        balance.set(tone.balance);
    }

    private void toneChanged(EqTone next) {
        tone = next;
        store.setTone(next);
        paintCurve();
        if (store.enabled()) {
            apply();
        }
    }
}
