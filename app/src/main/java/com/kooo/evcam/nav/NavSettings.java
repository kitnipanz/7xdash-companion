package com.kooo.evcam.nav;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.trip.Trip;

/**
 * The navigation settings, wired to {@link AppConfig}: the same box in the full screen's dialog and
 * in the floating panel ({@link NavFloatSettings}). What only the full screen can do stays there.
 */
final class NavSettings {

    /** Where the box is shown: what to redraw when a setting changes. */
    interface Host {
        void hudChanged();

        void schemeChanged(int scheme);

        void carColorChanged();

        /** Null where they can't open: Place on the HUD, Set up iPhone and the HUD preview need the full screen. */
        default Runnable hudPlacer() {
            return null;
        }

        default Runnable phoneSetup() {
            return null;
        }
    }

    private NavSettings() {
    }

    static View build(Context context, Host host) {
        AppConfig config = new AppConfig(context);
        View box = LayoutInflater.from(context).inflate(R.layout.dialog_nav_settings, null);
        SwitchMaterial hud = box.findViewById(R.id.nav_hud_switch);
        hud.setChecked(config.isNavHudEnabled());
        hud.setOnCheckedChangeListener((b, on) -> {
            config.setNavHudEnabled(on);
            Navigator.get().relayoutHud();
            host.hudChanged();
        });
        SwitchMaterial hudMap = box.findViewById(R.id.nav_hud_map_switch);
        hudMap.setChecked(config.isNavHudMapEnabled());
        hudMap.setOnCheckedChangeListener((b, on) -> {
            config.setNavHudMapEnabled(on);
            Navigator.get().relayoutHud();
        });

        MaterialButtonToggleGroup scheme = box.findViewById(R.id.nav_scheme_group);
        int[] schemeIds = {R.id.nav_scheme_auto, R.id.nav_scheme_light, R.id.nav_scheme_dark, R.id.nav_scheme_space};
        scheme.check(schemeIds[config.getNavMapScheme()]);
        scheme.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked) {
                return;
            }
            int chosen = id == R.id.nav_scheme_light ? AppConfig.NAV_MAP_LIGHT
                    : id == R.id.nav_scheme_dark ? AppConfig.NAV_MAP_DARK
                    : id == R.id.nav_scheme_space ? AppConfig.NAV_MAP_SPACE : AppConfig.NAV_MAP_AUTO;
            config.setNavMapScheme(chosen);
            host.schemeChanged(chosen);
        });
        buildSwatches(context, config, box.findViewById(R.id.nav_car_swatches), host);

        SwitchMaterial preview = box.findViewById(R.id.nav_hud_preview_switch);
        preview.setChecked(config.isNavHudPreviewEnabled());
        preview.setOnCheckedChangeListener((b, on) -> {
            config.setNavHudPreviewEnabled(on);
            host.hudChanged();
        });
        SwitchMaterial share = box.findViewById(R.id.nav_share_switch);
        share.setChecked(config.isNavShareEnabled());
        share.setOnCheckedChangeListener((b, on) -> {
            config.setNavShareEnabled(on);
            // the receiver runs for Navigation too: start or stop it
            Trip.get().apply(context);
        });

        Runnable placer = host.hudPlacer();
        Runnable phone = host.phoneSetup();
        wire(box.findViewById(R.id.nav_hud_adjust), placer);
        wire(box.findViewById(R.id.nav_phone_setup), phone);
        if (placer == null) {
            preview.setVisibility(View.GONE);
        }
        return box;
    }

    private static void wire(View button, Runnable action) {
        if (action == null) {
            button.setVisibility(View.GONE);
        } else {
            button.setOnClickListener(v -> action.run());
        }
    }

    /** The car colour as four round swatches with their names; the chosen one has an orange ring. */
    private static void buildSwatches(Context context, AppConfig config, LinearLayout row, Host host) {
        int[] names = {R.string.nav_car_forest_green, R.string.nav_car_silver, R.string.nav_car_black,
                R.string.nav_car_white};
        float d = context.getResources().getDisplayMetrics().density;
        row.removeAllViews();
        for (int i = 0; i < names.length; i++) {
            int index = i;
            CarIcon.Color color = CarIcon.Color.of(i);
            LinearLayout cell = new LinearLayout(context);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            int pad = Math.round(6 * d);
            cell.setPadding(pad, pad, pad, pad);
            View dot = new View(context);
            GradientDrawable fill = new GradientDrawable();
            fill.setShape(GradientDrawable.OVAL);
            fill.setColors(new int[] {color.light, color.edge});
            fill.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            fill.setGradientRadius(28 * d);
            boolean chosen = config.getNavCarColor() == i;
            fill.setStroke(Math.round((chosen ? 4 : 1) * d), chosen ? ContextCompat.getColor(context, R.color.energy)
                    : ContextCompat.getColor(context, R.color.line));
            dot.setBackground(fill);
            int size = Math.round(48 * d);
            cell.addView(dot, new LinearLayout.LayoutParams(size, size));
            TextView name = new TextView(context);
            name.setText(names[i]);
            name.setTextAppearance(R.style.TextAppearance_Cam_Caption);
            name.setTextColor(ContextCompat.getColor(context, chosen ? R.color.text_primary : R.color.text_secondary));
            name.setGravity(Gravity.CENTER);
            name.setPadding(0, Math.round(4 * d), 0, 0);
            cell.addView(name);
            TypedValue ripple = new TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
            cell.setBackgroundResource(ripple.resourceId);
            cell.setContentDescription(context.getString(names[i]));
            cell.setOnClickListener(v -> {
                config.setNavCarColor(index);
                buildSwatches(context, config, row, host);
                host.carColorChanged();
            });
            row.addView(cell, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        }
    }
}
