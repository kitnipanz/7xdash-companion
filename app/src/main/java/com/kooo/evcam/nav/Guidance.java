package com.kooo.evcam.nav;

/**
 * Follows the car along a {@link NavRoute}: snaps each GPS fix to the line, says how far the next
 * turn is and how much is left, and notices when the car has left the route. One per route; not
 * thread-safe. Pure Java, see the test.
 */
public final class Guidance {

    /** Further from the line than this (or the GPS accuracy, if worse) counts as off the line... */
    static final double OFF_ROUTE_M = 45;
    /** ...and this many fixes in a row off the line means off the route. */
    static final int OFF_ROUTE_FIXES = 3;
    /** This close to the end, we are there. */
    static final double ARRIVED_M = 30;
    /** How far ahead of the last snap to look first; a full search only when that finds nothing near. */
    private static final double LOOK_AHEAD_M = 1_500;

    /** Where the car is on the route. */
    public static final class Progress {
        /** The fix pulled onto the line. */
        public final double lat;
        public final double lng;
        /** Direction of the line where the car is, 0..360. */
        public final double bearing;
        /** How far the fix was from the line. */
        public final double offMeters;
        /** Left the route: time to ask for a new one. */
        public final boolean offRoute;
        public final boolean arrived;
        /** The step the car is driving now. */
        public final int step;
        /** The step whose maneuver comes next, or -1 when the next thing is the destination. */
        public final int next;
        /** Metres to that maneuver (or the destination). */
        public final double toNext;
        public final double remainingMeters;
        /** With the route's traffic factor. */
        public final double remainingSeconds;
        /** The piece of the route's line the car is on: between points {@code segment} and {@code segment + 1}. */
        public final int segment;

        Progress(double lat, double lng, double bearing, double offMeters, boolean offRoute, boolean arrived,
                 int step, int next, double toNext, double remainingMeters, double remainingSeconds, int segment) {
            this.lat = lat;
            this.lng = lng;
            this.bearing = bearing;
            this.offMeters = offMeters;
            this.offRoute = offRoute;
            this.arrived = arrived;
            this.step = step;
            this.next = next;
            this.toNext = toNext;
            this.remainingMeters = remainingMeters;
            this.remainingSeconds = remainingSeconds;
            this.segment = segment;
        }
    }

    private final NavRoute route;
    private int segment;
    private int offFixes;

    public Guidance(NavRoute route) {
        this.route = route;
    }

    public NavRoute route() {
        return route;
    }

    /** A new GPS fix. {@code accuracyM} may be 0 when not known. */
    public Progress update(double lat, double lng, double accuracyM) {
        int segments = route.pointCount() - 1;
        if (segments < 1) {
            return new Progress(route.lat(0), route.lng(0), 0, 0, false, true, 0, -1, 0, 0, 0, 0);
        }
        // first near the last snap and ahead of it (no jumping back onto a parallel road we left);
        // the whole line only when that finds nothing close, e.g. after a GPS gap
        int from = Math.max(0, segment - 1);
        double[] best = search(lat, lng, from, ahead(from));
        if (best[2] > Math.max(OFF_ROUTE_M, accuracyM) * 2) {
            double[] all = search(lat, lng, 0, segments - 1);
            if (all[2] < best[2]) {
                best = all;
            }
        }
        int seg = (int) best[0];
        double t = best[1];
        double off = best[2];
        boolean offLine = off > Math.max(OFF_ROUTE_M, accuracyM);
        offFixes = offLine ? offFixes + 1 : 0;
        if (!offLine) {
            segment = seg;
        }

        double aLat = route.lat(seg);
        double aLng = route.lng(seg);
        double bLat = route.lat(seg + 1);
        double bLng = route.lng(seg + 1);
        double snapLat = aLat + t * (bLat - aLat);
        double snapLng = aLng + t * (bLng - aLng);
        double along = route.along[seg] + t * (route.along[seg + 1] - route.along[seg]);
        double remaining = Math.max(0, route.meters - along);
        boolean arrived = remaining < ARRIVED_M
                || Geo.meters(lat, lng, route.endLat(), route.endLng()) < ARRIVED_M;

        int step = stepAt(along);
        int next = step + 1 < route.steps.size() ? step + 1 : -1;
        double nextAt = next < 0 ? route.meters : route.steps.get(next).startAlong;
        return new Progress(snapLat, snapLng, Geo.bearing(aLat, aLng, bLat, bLng), off,
                offFixes >= OFF_ROUTE_FIXES, arrived, step, next, Math.max(0, nextAt - along),
                remaining, secondsLeft(step, along), seg);
    }

    /** The last segment to look at first: {@link #LOOK_AHEAD_M} on from segment {@code from}. */
    private int ahead(int from) {
        int last = route.pointCount() - 2;
        double until = route.along[from] + LOOK_AHEAD_M;
        int i = from;
        while (i < last && route.along[i + 1] < until) {
            i++;
        }
        return Math.min(last, i + 1);
    }

    /** The closest segment in [from, to]: {@code [segment, t, metres off]}. */
    private double[] search(double lat, double lng, int from, int to) {
        double[] best = {from, 0, Double.MAX_VALUE};
        for (int i = from; i <= to; i++) {
            double[] p = Geo.project(lat, lng, route.lat(i), route.lng(i), route.lat(i + 1), route.lng(i + 1));
            if (p[1] < best[2]) {
                best[0] = i;
                best[1] = p[0];
                best[2] = p[1];
            }
        }
        return best;
    }

    private int stepAt(double along) {
        int step = 0;
        for (int s = 1; s < route.steps.size(); s++) {
            if (route.steps.get(s).startAlong <= along) {
                step = s;
            } else {
                break;
            }
        }
        return step;
    }

    /** What is left of this step (its share of its time) plus the steps after it, with traffic. */
    private double secondsLeft(int step, double along) {
        if (route.steps.isEmpty() || route.steps.get(0).seconds <= 0) {
            // no step times: the route's own, in proportion to the distance left
            return route.seconds * Math.max(0, 1 - along / Math.max(1, route.meters));
        }
        NavRoute.Step s = route.steps.get(step);
        double end = step + 1 < route.steps.size() ? route.steps.get(step + 1).startAlong : route.meters;
        double length = end - s.startAlong;
        double left = length <= 0 ? 0 : s.seconds * Math.max(0, Math.min(1, (end - along) / length));
        for (int i = step + 1; i < route.steps.size(); i++) {
            left += route.steps.get(i).seconds;
        }
        return left * route.trafficFactor();
    }
}
