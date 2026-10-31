package com.kooo.evcam.trip;

import android.content.Context;

import com.kooo.evcam.R;

import java.util.Locale;

/** The words for an estimate, shared by the Dash panel, the Home card and {@link ArrivalActivity}. */
public final class ArrivalText {

    private ArrivalText() {
    }

    /** "182 km": "~" in front while it is still the straight-line guess. */
    public static String km(Trip.Status s) {
        double km = s.estimate == null ? 0 : s.estimate.kmLeft;
        String n = km < 10 ? String.format(Locale.US, "%.1f", km) : String.format(Locale.US, "%.0f", km);
        return (s.roughKm ? "~" : "") + n + " km";
    }

    /** The big number: "23%" at arrival, or "Short 40 km". */
    public static String headline(Context context, Trip.Status s) {
        Arrival.Estimate e = s.estimate;
        if (e.level == Arrival.Level.SHORT) {
            return context.getString(R.string.arrival_short, Math.round(-e.spareKm));
        }
        return context.getString(R.string.arrival_percent, Math.round(Math.max(0f, e.percent)));
    }

    /** "182 km to go · 64 km spare". */
    public static String detail(Context context, Trip.Status s) {
        Arrival.Estimate e = s.estimate;
        if (e.level == Arrival.Level.SHORT) {
            return context.getString(R.string.arrival_detail_short, km(s));
        }
        return context.getString(R.string.arrival_detail, km(s), Math.round(e.spareKm));
    }

    /** One short line for the Dash panel and the Home card: "182 km · 23%", or "182 km · −40 km" when short. */
    public static String line(Context context, Trip.Status s) {
        if (s.destination == null) {
            return null;
        }
        if (s.estimate == null) {
            return context.getString(R.string.arrival_waiting_short);
        }
        Arrival.Estimate e = s.estimate;
        String end = e.level == Arrival.Level.SHORT ? "−" + Math.round(-e.spareKm) + " km"
                : Math.round(Math.max(0f, e.percent)) + "%";
        return km(s) + " · " + end;
    }

    /** The colour for {@link #line}: white when fine, orange when low, red when short. */
    public static int color(Trip.Status s, int ok, int low, int shortColor) {
        if (s.estimate == null || s.estimate.level == Arrival.Level.OK) {
            return ok;
        }
        return s.estimate.level == Arrival.Level.LOW ? low : shortColor;
    }
}
