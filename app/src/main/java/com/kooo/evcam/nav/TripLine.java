package com.kooo.evcam.nav;

import android.content.Context;

import com.kooo.evcam.trip.Arrival;
import com.kooo.evcam.trip.ArrivalText;
import com.kooo.evcam.trip.Trip;

/**
 * The one-line trip for the Dash panel and the Home card: "4.4 km · 23%" (road km left, battery at
 * arrival), or "4.4 km · −40 km" when the range runs out first. From Navigation while it has a
 * route; otherwise from the place last shared from the iPhone ({@link Trip}, which estimates it
 * without Google), so the line is there even with no key or no route.
 */
public final class TripLine {

    public final String text;
    /** OK, LOW (orange) or SHORT (red); OK while the battery is not known yet. */
    public final Arrival.Level level;

    private TripLine(String text, Arrival.Level level) {
        this.text = text;
        this.level = level;
    }

    /** Null when there is no trip. */
    public static TripLine now(Context context) {
        Navigator.Snapshot s = Navigator.get().snapshot();
        boolean navTrip = s.route != null && (s.phase == Navigator.Phase.PREVIEW
                || s.phase == Navigator.Phase.GUIDING || s.phase == Navigator.Phase.ARRIVED);
        if (navTrip) {
            double meters = s.progress != null ? s.progress.remainingMeters : s.route.meters;
            double seconds = s.progress != null ? s.progress.remainingSeconds : s.route.seconds;
            String km = NavText.distance(context, meters);
            if (s.battery == null) {
                return new TripLine(km + " · " + NavText.eta(context, seconds), Arrival.Level.OK);
            }
            return new TripLine(km + " · " + battery(s.battery), s.battery.level);
        }
        Trip.Status t = Trip.get().status();
        String line = ArrivalText.line(context, t);
        if (line == null) {
            return null;
        }
        return new TripLine(line, t.estimate == null ? Arrival.Level.OK : t.estimate.level);
    }

    /** "23%", or "−40 km" when short. */
    static String battery(Arrival.Estimate e) {
        return e.level == Arrival.Level.SHORT ? "−" + Math.round(-e.spareKm) + " km"
                : Math.round(Math.max(0f, e.percent)) + "%";
    }

    /** {@code ok} when fine, {@code low} when low, {@code shortColor} when short. */
    public int color(int ok, int low, int shortColor) {
        return level == Arrival.Level.SHORT ? shortColor : level == Arrival.Level.LOW ? low : ok;
    }
}
