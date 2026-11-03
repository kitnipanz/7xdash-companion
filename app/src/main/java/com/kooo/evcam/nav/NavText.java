package com.kooo.evcam.nav;

import android.content.Context;
import android.text.format.DateFormat;

import com.kooo.evcam.R;

import java.util.Date;
import java.util.Locale;

/** What the Navigation screen, the HUD and the voice say. */
public final class NavText {

    private NavText() {
    }

    /** "350 m", "1.2 km", "12 km". */
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

    /** The sentence to speak for a prompt. */
    public static String spoken(Context c, NavRoute route, VoicePlan.Prompt p) {
        String what = p.next < 0 ? c.getString(R.string.nav_say_destination)
                : route.steps.get(p.next).instruction.replace('\n', ' ').replace("  ", " ").trim();
        if (what.isEmpty()) {
            what = c.getString(R.string.nav_continue);
        }
        if (p.stage == VoicePlan.Stage.NOW) {
            return p.next < 0 ? c.getString(R.string.nav_say_arrived) : what;
        }
        String far = p.meters >= 1_000
                ? c.getString(R.string.nav_say_km, p.meters % 1000 == 0
                ? String.valueOf(p.meters / 1000) : String.format(Locale.getDefault(), "%.1f", p.meters / 1000.0))
                : c.getString(R.string.nav_say_m, p.meters);
        return c.getString(R.string.nav_say_in, far, lowerFirst(what));
    }

    /** "Turn left" → "turn left" after "In 300 metres, ". Leaves Thai (no case) alone. */
    private static String lowerFirst(String s) {
        if (s.length() < 2 || !Character.isUpperCase(s.charAt(0)) || Character.isUpperCase(s.charAt(1))) {
            return s;
        }
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
