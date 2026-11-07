package com.kooo.evcam.nav;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One driving route: the line on the map and its turn-by-turn steps. The line is the steps' own
 * lines joined end to end, so every step knows where it starts on it ({@link Step#startPoint}).
 * Each piece of the line carries Google's traffic there ({@link #traffic}), and the route its "via"
 * label and whether Google rates it best, for choosing among alternatives. Immutable. Pure Java.
 */
public final class NavRoute {

    /** One instruction: the maneuver at its start, then this many metres to the next one. */
    public static final class Step {
        public final Maneuver maneuver;
        /** "Turn left onto Sukhumvit Rd", in the language asked for. May be empty. */
        public final String instruction;
        public final double meters;
        /** Without traffic. */
        public final double seconds;
        /** Index into the route's points where this step starts. */
        public final int startPoint;
        /** Metres along the route where this step starts. */
        public final double startAlong;

        Step(Maneuver maneuver, String instruction, double meters, double seconds, int startPoint, double startAlong) {
            this.maneuver = maneuver;
            this.instruction = instruction == null ? "" : instruction;
            this.meters = meters;
            this.seconds = seconds;
            this.startPoint = startPoint;
            this.startAlong = startAlong;
        }
    }

    /** {@code [lat0, lng0, lat1, lng1, ...]}. */
    public final double[] points;
    /** Metres along the route at each point. */
    public final double[] along;
    public final List<Step> steps;
    /** The route's length on the line (what guidance counts down). */
    public final double meters;
    /** With traffic, as the Routes API gave it. */
    public final double seconds;
    /** Without traffic. */
    public final double staticSeconds;
    /** Traffic on each piece of the line, between point i and i + 1: {@link #FREE}, {@link #SLOW} or {@link #JAM}. */
    public final byte[] traffic;
    /** "ถ. พญาไท": the main road it takes, from Google; may be empty. */
    public final String label;
    /** Google's own pick among the routes it gave. */
    public final boolean best;

    public static final byte FREE = 0;
    public static final byte SLOW = 1;
    public static final byte JAM = 2;

    private NavRoute(double[] points, double[] along, List<Step> steps, double seconds, double staticSeconds,
                     byte[] traffic, String label, boolean best) {
        this.points = points;
        this.along = along;
        this.steps = Collections.unmodifiableList(steps);
        this.meters = along.length == 0 ? 0 : along[along.length - 1];
        this.seconds = seconds;
        this.staticSeconds = staticSeconds;
        this.traffic = traffic;
        this.label = label == null ? "" : label;
        this.best = best;
    }

    public int pointCount() {
        return points.length / 2;
    }

    public double lat(int i) {
        return points[2 * i];
    }

    public double lng(int i) {
        return points[2 * i + 1];
    }

    public double endLat() {
        return lat(pointCount() - 1);
    }

    public double endLng() {
        return lng(pointCount() - 1);
    }

    /**
     * The point {@code metres} along the route and the road's direction there: {lat, lng, bearing}.
     * Clamped to the start and the end.
     */
    public double[] pointAt(double metres) {
        int n = pointCount();
        if (n == 0) {
            return new double[] {0, 0, 0};
        }
        if (n == 1) {
            return new double[] {lat(0), lng(0), 0};
        }
        // the segment [i, i + 1] that holds it
        int lo = 0;
        int hi = n - 2;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (along[mid] <= metres) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        int i = lo;
        // skip zero-length pieces so the direction is the road's
        while (i < n - 2 && along[i + 1] - along[i] <= 0) {
            i++;
        }
        double len = along[i + 1] - along[i];
        double t = len <= 0 ? 0 : Math.max(0, Math.min(1, (metres - along[i]) / len));
        return new double[] {
                lat(i) + t * (lat(i + 1) - lat(i)),
                lng(i) + t * (lng(i + 1) - lng(i)),
                Geo.bearing(lat(i), lng(i), lat(i + 1), lng(i + 1))};
    }

    /** Traffic slows it by this much (1 = free flowing). */
    public double trafficFactor() {
        return staticSeconds > 0 && seconds > 0 ? seconds / staticSeconds : 1;
    }

    /** Builds a route one step at a time, from each step's own line. */
    public static final class Builder {
        private final List<double[]> lines = new ArrayList<>();
        private final List<Maneuver> maneuvers = new ArrayList<>();
        private final List<String> instructions = new ArrayList<>();
        private final List<double[]> sizes = new ArrayList<>();
        private double seconds;
        private double staticSeconds;
        /** {from metres, to metres, level}, along the route. */
        private final List<double[]> slowings = new ArrayList<>();
        private String label = "";
        private boolean best = true;

        /** Traffic from {@code fromMeters} to {@code toMeters} along the route; outside them it is free flowing. */
        public Builder traffic(double fromMeters, double toMeters, byte level) {
            if (level != FREE && toMeters > fromMeters) {
                slowings.add(new double[]{fromMeters, toMeters, level});
            }
            return this;
        }

        public Builder label(String label, boolean best) {
            this.label = label;
            this.best = best;
            return this;
        }

        public Builder step(Maneuver maneuver, String instruction, double meters, double seconds, double[] line) {
            if (line == null || line.length < 2) {
                return this;
            }
            lines.add(line);
            maneuvers.add(maneuver);
            instructions.add(instruction);
            sizes.add(new double[]{meters, seconds});
            return this;
        }

        public Builder duration(double seconds, double staticSeconds) {
            this.seconds = seconds;
            this.staticSeconds = staticSeconds;
            return this;
        }

        /** Null when no step had a line. */
        public NavRoute build() {
            int total = 0;
            for (double[] l : lines) {
                total += l.length;
            }
            if (total == 0) {
                return null;
            }
            double[] pts = new double[total];
            int n = 0;
            List<Step> steps = new ArrayList<>();
            List<Double> starts = new ArrayList<>();
            double stepSeconds = 0;
            for (int s = 0; s < lines.size(); s++) {
                double[] l = lines.get(s);
                int from = 0;
                // the joint is the end of the last step and the start of this one: keep it once
                if (n >= 2 && pts[n - 2] == l[0] && pts[n - 1] == l[1]) {
                    from = 2;
                }
                starts.add((double) Math.max(0, n / 2 - (from == 2 ? 1 : 0)));
                System.arraycopy(l, from, pts, n, l.length - from);
                n += l.length - from;
                stepSeconds += sizes.get(s)[1];
            }
            double[] points = new double[n];
            System.arraycopy(pts, 0, points, 0, n);
            int count = n / 2;
            double[] along = new double[count];
            for (int i = 1; i < count; i++) {
                along[i] = along[i - 1] + Geo.meters(points[2 * i - 2], points[2 * i - 1], points[2 * i], points[2 * i + 1]);
            }
            for (int s = 0; s < lines.size(); s++) {
                int start = starts.get(s).intValue();
                steps.add(new Step(maneuvers.get(s), instructions.get(s), sizes.get(s)[0], sizes.get(s)[1],
                        start, along[Math.min(start, count - 1)]));
            }
            double st = staticSeconds > 0 ? staticSeconds : stepSeconds;
            double sec = seconds > 0 ? seconds : st;
            byte[] traffic = new byte[Math.max(0, count - 1)];
            for (int i = 0; i < traffic.length; i++) {
                double mid = (along[i] + along[i + 1]) / 2;
                for (double[] w : slowings) {
                    if (mid >= w[0] && mid < w[1]) {
                        traffic[i] = (byte) Math.max(traffic[i], (int) w[2]);
                    }
                }
            }
            return new NavRoute(points, along, steps, sec, st, traffic, label, best);
        }
    }
}
