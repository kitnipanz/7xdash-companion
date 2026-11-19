package com.kooo.evcam.audio;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;
import com.kooo.evcam.R;
import com.kooo.evcam.ui.Notice;

import java.util.ArrayList;
import java.util.List;

/**
 * A small EQ that floats over any app (the car's music player, maps…): on/off, presets, bass,
 * treble and hold-for-A/B. Drag it by its title; ✕ closes it. Changes are the same ones the full
 * EQ screen saves.
 */
public final class EqFloatWindow {

    private static View shown;
    private static WindowManager.LayoutParams params;

    private EqFloatWindow() {
    }

    /** Opens it, or asks for the draw-over-apps permission first. */
    public static void show(Activity activity) {
        Context app = activity.getApplicationContext();
        if (!com.kooo.evcam.overlay.OverlayAccess.canDraw(app)) {
            Notice.makeText(activity, R.string.eq_float_permission, Toast.LENGTH_LONG).show();
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + app.getPackageName())));
            } catch (RuntimeException ignored) {
                // no settings screen for it on this head unit
            }
            return;
        }
        if (shown != null) {
            return;
        }
        Context themed = new ContextThemeWrapper(app, R.style.Theme_Cam);
        WindowManager wm = app.getSystemService(WindowManager.class);
        View panel = build(themed, wm);
        float density = themed.getResources().getDisplayMetrics().density;
        params = new WindowManager.LayoutParams(
                Math.round(460 * density), ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = Math.round(80 * density);
        params.y = Math.round(120 * density);
        com.kooo.evcam.overlay.OverlayAccess.add(wm, panel, params);
        shown = panel;
    }

    public static void close(Context context) {
        if (shown == null) {
            return;
        }
        try {
            context.getApplicationContext().getSystemService(WindowManager.class).removeView(shown);
        } catch (RuntimeException ignored) {
            // already gone
        }
        shown = null;
        CabinEqEngine.setBypassed(false);
    }

    @SuppressLint("ClickableViewAccessibility")
    private static View build(Context c, WindowManager wm) {
        CabinEqStore store = new CabinEqStore(c);
        float d = c.getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * d);

        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad / 2, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ContextCompat.getColor(c, R.color.surface));
        bg.setCornerRadius(20 * d);
        bg.setStroke(Math.round(1 * d), ContextCompat.getColor(c, R.color.line));
        root.setBackground(bg);
        root.setElevation(12 * d);

        // title row: drag handle, on/off, close
        LinearLayout title = new LinearLayout(c);
        title.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(c);
        name.setText(R.string.eq_float_title);
        name.setTextAppearance(R.style.TextAppearance_Cam_Title);
        name.setTextColor(ContextCompat.getColor(c, R.color.text_primary));
        SwitchCompat on = new SwitchCompat(c);
        on.setChecked(store.enabled());
        on.setOnCheckedChangeListener((b, checked) -> {
            store.setEnabled(checked);
            CabinEqEngine.apply(checked, store.currentGains(), store.tone());
        });
        TextView close = new TextView(c);
        close.setText("✕");
        close.setTextAppearance(R.style.TextAppearance_Cam_Title);
        close.setTextColor(ContextCompat.getColor(c, R.color.text_secondary));
        close.setPadding(pad, pad / 2, 0, pad / 2);
        close.setOnClickListener(v -> close(c));
        title.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        title.addView(on);
        title.addView(close);
        title.setOnTouchListener(new View.OnTouchListener() {
            float startX;
            float startY;
            int originX;
            int originY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    startX = e.getRawX();
                    startY = e.getRawY();
                    originX = params.x;
                    originY = params.y;
                    return true;
                }
                if (e.getActionMasked() == MotionEvent.ACTION_MOVE && shown != null) {
                    params.x = originX + Math.round(e.getRawX() - startX);
                    params.y = originY + Math.round(e.getRawY() - startY);
                    try {
                        wm.updateViewLayout(shown, params);
                    } catch (RuntimeException ignored) {
                        // closed meanwhile
                    }
                    return true;
                }
                return e.getActionMasked() == MotionEvent.ACTION_UP;
            }
        });
        root.addView(title);

        // presets in one scrolling row
        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(c);
        chips.setBackgroundResource(R.drawable.bg_segment_track);
        chips.setPadding(Math.round(4 * d), Math.round(4 * d), Math.round(4 * d), Math.round(4 * d));
        List<TextView> all = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        Runnable paint = () -> {
            String chosen = store.choice();
            for (int i = 0; i < all.size(); i++) {
                boolean sel = keys.get(i).equals(chosen);
                all.get(i).setActivated(sel);
                all.get(i).setTextColor(ContextCompat.getColor(c, sel ? R.color.on_energy : R.color.text_secondary));
            }
        };
        List<String> labels = new ArrayList<>();
        for (CabinEq.Builtin b : CabinEqFragment.SOUND) {
            keys.add(CabinEqStore.BUILTIN + b.name());
            labels.add(c.getString(CabinEqFragment.labelOf(b)));
        }
        for (CabinEq.Builtin b : CabinEqFragment.GENRE) {
            keys.add(CabinEqStore.BUILTIN + b.name());
            labels.add(c.getString(CabinEqFragment.labelOf(b)));
        }
        for (int i = 0; i < CabinEq.PRESETS; i++) {
            keys.add(CabinEqStore.MY + i);
            String named = store.myName(i);
            labels.add(named.isEmpty() ? c.getString(R.string.eq_my, i + 1) : named);
        }
        for (int i = 0; i < keys.size(); i++) {
            TextView chip = new TextView(c);
            chip.setText(labels.get(i));
            chip.setGravity(Gravity.CENTER);
            chip.setBackgroundResource(R.drawable.bg_segment);
            chip.setTextAppearance(R.style.TextAppearance_Cam_Body);
            chip.setPadding(pad, 0, pad, 0);
            String key = keys.get(i);
            chip.setOnClickListener(v -> {
                store.choose(key);
                paint.run();
                if (store.enabled()) {
                    CabinEqEngine.apply(true, store.currentGains(), store.tone());
                }
            });
            chips.addView(chip, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    Math.round(44 * d)));
            all.add(chip);
        }
        paint.run();
        scroll.addView(chips);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollLp.topMargin = pad / 2;
        root.addView(scroll, scrollLp);

        root.addView(toneSlider(c, R.string.eq_tone_bass, store.tone().bass, v -> {
            EqTone t = store.tone().withBass(v);
            store.setTone(t);
            if (store.enabled()) {
                CabinEqEngine.apply(true, store.currentGains(), t);
            }
        }));
        root.addView(toneSlider(c, R.string.eq_tone_treble, store.tone().treble, v -> {
            EqTone t = store.tone().withTreble(v);
            store.setTone(t);
            if (store.enabled()) {
                CabinEqEngine.apply(true, store.currentGains(), t);
            }
        }));

        MaterialButton ab = new MaterialButton(new ContextThemeWrapper(c, R.style.Theme_Cam), null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        ab.setText(R.string.eq_ab);
        ab.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                CabinEqEngine.setBypassed(true);
                ab.setText(R.string.eq_ab_on);
            } else if (e.getActionMasked() == MotionEvent.ACTION_UP
                    || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                CabinEqEngine.setBypassed(false);
                ab.setText(R.string.eq_ab);
            }
            return true;
        });
        root.addView(ab, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(52 * d)));
        return root;
    }

    private static View toneSlider(Context c, int label, int value, java.util.function.IntConsumer changed) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(c);
        name.setText(label);
        name.setTextAppearance(R.style.TextAppearance_Cam_Body);
        name.setTextColor(ContextCompat.getColor(c, R.color.text_primary));
        name.setMinWidth(Math.round(90 * c.getResources().getDisplayMetrics().density));
        TextView shown = new TextView(c);
        shown.setTextAppearance(R.style.TextAppearance_Cam_Body);
        shown.setTextColor(ContextCompat.getColor(c, R.color.energy_text));
        shown.setMinWidth(Math.round(64 * c.getResources().getDisplayMetrics().density));
        shown.setGravity(Gravity.END);
        Slider slider = new Slider(c);
        slider.setValueFrom(EqTone.TONE_MIN);
        slider.setValueTo(EqTone.TONE_MAX);
        slider.setStepSize(1);
        slider.setValue(value);
        slider.setLabelBehavior(com.google.android.material.slider.LabelFormatter.LABEL_GONE);
        slider.setTickVisible(false);
        shown.setText(c.getString(R.string.eq_db, value > 0 ? "+" + value : Integer.toString(value)));
        slider.addOnChangeListener((s, v, fromUser) -> {
            int r = Math.round(v);
            shown.setText(c.getString(R.string.eq_db, r > 0 ? "+" + r : Integer.toString(r)));
            if (fromUser) {
                changed.accept(r);
            }
        });
        row.addView(name);
        row.addView(slider, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(shown);
        return row;
    }
}
