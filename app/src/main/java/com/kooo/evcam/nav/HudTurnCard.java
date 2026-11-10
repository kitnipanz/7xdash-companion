package com.kooo.evcam.nav;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The next-turn card as the HUD shows it: arrow, distance, road, and the trip (arrival time and
 * battery at arrival). Built at any height, so the same card goes on the HUD ({@link HudTurnWindow})
 * and, smaller, as the HUD preview on the Navigation maps. Its text always fits: tall Thai lines or a
 * small card shrink it ({@link #fitScale}) rather than cut the last line off. Its colours follow the
 * map's colours setting ({@link Theme}): black with an orange rim, white, or the space map's dark glass.
 */
final class HudTurnCard {

    private static final int ENERGY = 0xFFF0622D;

    /** The card's colours for one map colour scheme. */
    static final class Theme {
        final int[] background;
        final int rim;
        final int arrow;
        final int main;
        final int sub;

        Theme(int[] background, int rim, int arrow, int main, int sub) {
            this.background = background;
            this.rim = rim;
            this.arrow = arrow;
            this.main = main;
            this.sub = sub;
        }

        static final Theme DARK = new Theme(new int[] {0xD9101114, 0xD9101114}, ENERGY, Color.WHITE, Color.WHITE, 0xFFD0D2D6);
        static final Theme LIGHT = new Theme(new int[] {0xF2FFFFFF, 0xF2F3F4F6}, ENERGY, 0xFF1F2226, 0xFF1F2226, 0xFF5F6368);
        static final Theme SPACE = new Theme(new int[] {0xE6102434, 0xE6060C14}, 0xFF22E6D2, 0xFF22E6D2, Color.WHITE, 0xFFC8DCF0);

        /** The theme of a map colour setting: auto follows the head unit's day / night. */
        static Theme of(Context context, int scheme) {
            if (scheme == com.kooo.evcam.AppConfig.NAV_MAP_SPACE) {
                return SPACE;
            }
            return NavMapPainter.isDark(context, scheme) ? DARK : LIGHT;
        }
    }

    final LinearLayout view;
    private final ManeuverIcon.ArrowView arrow;
    private final TextView distance;
    private final TextView road;
    private final TextView trip;
    private final LinearLayout texts;
    private final float distancePx;
    private final float roadPx;
    private final float tripPx;
    /** What was last fitted, so a resize of the text doesn't fit it again for ever. */
    private String fitted = "";
    private final int height;
    private Theme theme;

    /** @param height the card's height in pixels; everything in it scales with it */
    HudTurnCard(Context context, int height) {
        view = new LinearLayout(context);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        this.height = height;
        int pad = Math.max(4, height / 20);
        view.setPadding(pad * 2, pad, pad * 2, pad);

        arrow = new ManeuverIcon.ArrowView(context);
        int icon = Math.round(height * 0.7f);
        view.addView(arrow, new LinearLayout.LayoutParams(icon, icon));

        texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        distancePx = height * 0.3f;
        roadPx = height * 0.15f;
        tripPx = height * 0.13f;
        distance = text(context, distancePx, Color.WHITE, true);
        distance.setEllipsize(TextUtils.TruncateAt.END);
        road = text(context, roadPx, 0xFFD0D2D6, false);
        road.setMaxLines(2);
        road.setEllipsize(TextUtils.TruncateAt.END);
        trip = text(context, tripPx, 0xFFD0D2D6, false);
        trip.setSingleLine(true);
        trip.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(distance);
        texts.addView(road);
        texts.addView(trip);
        LinearLayout.LayoutParams textsAt = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        textsAt.leftMargin = pad * 2;
        view.addView(texts, textsAt);
        view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fit());
        applyTheme();
    }

    /** Takes the colours of the map's colour setting now, when they changed. */
    private void applyTheme() {
        Context context = view.getContext();
        Theme now = Theme.of(context, new com.kooo.evcam.AppConfig(context).getNavMapScheme());
        if (now == theme) {
            return;
        }
        theme = now;
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, now.background);
        bg.setCornerRadius(height / 12f);
        bg.setStroke(Math.max(2, height / 60), now.rim);
        view.setBackground(bg);
        distance.setTextColor(now.main);
        road.setTextColor(now.sub);
    }

    void set(Navigator.HudLine line) {
        applyTheme();
        arrow.set(line.maneuver, theme.arrow);
        distance.setText(line.distance);
        road.setText(line.text);
        trip.setText(line.trip);
        trip.setVisibility(line.trip == null || line.trip.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
        trip.setTextColor(line.tripLevel == com.kooo.evcam.trip.Arrival.Level.SHORT ? 0xFFFF6B6B
                : line.tripLevel == com.kooo.evcam.trip.Arrival.Level.LOW ? ENERGY : theme.sub);
        fit();
    }

    /** Sizes the text so all of it fits the card: measured, since Thai lines are taller than Latin ones. */
    private void fit() {
        int width = texts.getWidth();
        int room = view.getHeight() - view.getPaddingTop() - view.getPaddingBottom();
        if (width <= 0 || room <= 0) {
            return;
        }
        String now = width + "x" + room + road.getText() + trip.getText() + trip.getVisibility();
        if (now.equals(fitted)) {
            return;
        }
        fitted = now;
        float scale = 1f;
        size(scale);
        int anyHeight = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED);
        int exactWidth = android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY);
        for (int i = 0; i < 4; i++) {
            texts.measure(exactWidth, anyHeight);
            float next = fitScale(texts.getMeasuredHeight(), room);
            if (next >= 1f) {
                break;
            }
            scale *= next;
            size(scale);
        }
    }

    private void size(float scale) {
        distance.setTextSize(TypedValue.COMPLEX_UNIT_PX, distancePx * scale);
        road.setTextSize(TypedValue.COMPLEX_UNIT_PX, roadPx * scale);
        trip.setTextSize(TypedValue.COMPLEX_UNIT_PX, tripPx * scale);
    }

    /** How much to shrink text {@code content} pixels tall to fit in {@code room}; 1 when it fits. */
    static float fitScale(int content, int room) {
        return content <= room || content <= 0 ? 1f : 0.98f * room / content;
    }

    private static TextView text(Context context, float px, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        view.setTextColor(color);
        view.setSingleLine(bold);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }
}
