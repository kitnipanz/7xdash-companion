package com.kooo.evcam.nav;

import android.content.Context;
import android.text.format.DateFormat;

import com.kooo.evcam.R;

import java.util.Date;
import java.util.Locale;

/** What the Navigation screen and the HUD say. */
public final class NavText {

    private NavText() {
    }

    /** "350 m", "1.2 km", "12 km". */
    /** "64% battery at arrival", "Short by 40 km: charge on the way", or waiting for the car. */
    public static String battery(Context c, com.kooo.evcam.trip.Arrival.Estimate e) {
        if (e == null) {
            return c.getString(R.string.nav_battery_unknown);
        }
        if (e.level == com.kooo.evcam.trip.Arrival.Level.SHORT) {
            return c.getString(R.string.nav_battery_short, (int) Math.ceil(-e.spareKm));
        }
        return c.getString(R.string.nav_battery, Math.round(e.percent));
    }

    public static String distance(Context c, double meters) {
        if (meters < 1_000) {
            int m = meters < 100 ? (int) (Math.round(meters / 10) * 10) : (int) (Math.round(meters / 50) * 50);
            if (m < 1_000) {
                return c.getString(R.string.nav_m, m);
            }
        }
        double km = meters / 1000;
        return km < 10 ? c.getString(R.string.nav_km_1, String.format(Locale.getDefault(), "%.1f", km))
                : c.getString(R.string.nav_km, (int) Math.round(km));
    }

    /** "1 h 5 min", "12 min". */
    public static String duration(Context c, double seconds) {
        int min = (int) Math.max(1, Math.round(seconds / 60));
        return min < 60 ? c.getString(R.string.nav_min, min)
                : c.getString(R.string.nav_h_min, min / 60, min % 60);
    }

    /** The clock time of arrival, "14:35". */
    public static String eta(Context c, double seconds) {
        return DateFormat.getTimeFormat(c).format(new Date(System.currentTimeMillis() + (long) (seconds * 1000)));
    }

    /** The banner's first line: Google's instruction without its second sentence. */
    public static String headline(Context c, NavRoute route, int next) {
        if (next < 0) {
            return c.getString(R.string.nav_arrive);
        }
        String s = route.steps.get(next).instruction;
        int nl = s.indexOf('\n');
        s = nl < 0 ? s : s.substring(0, nl);
        return s.isEmpty() ? c.getString(R.string.nav_continue) : s;
    }

    /** The banner's second line: what Google added after it ("Toll road", "Destination on the left"), or "". */
    public static String detail(NavRoute route, int next) {
        if (next < 0) {
            return "";
        }
        String s = route.steps.get(next).instruction;
        int nl = s.indexOf('\n');
        return nl < 0 ? "" : s.substring(nl + 1).replace('\n', ' ').trim();
    }

}
