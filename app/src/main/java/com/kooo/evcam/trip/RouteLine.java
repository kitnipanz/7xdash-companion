package com.kooo.evcam.trip;

import java.util.ArrayList;
import java.util.List;

/**
 * The road the route takes, as a line of points, and how far is left along it from where the car
 * is. This is how Google Maps counts km to go: the car's position is snapped onto the route line
 * and the distance is measured along the line from there to the end, not in a straight line and
 * not from the odometer. When the car is too far from the line it has left the route and a new
 * one is needed. Pure Java, see the test.
 */
final class RouteLine {

    /** Mean earth radius in metres, as in {@link Arrival#straightKm}. */
    private static final double R = 6_371_000.0;
    /** How far back from the last match a new match may be (GPS jitter at a junction). */
    private static final int BACK_SEGMENTS = 3;

    /** Where the car is on the line. */
    static final class Fix {
        /** Metres still to drive along the line to its end. */
        final double remainingM;
        /** Metres from the car to the nearest point of the line. */
        final double offRouteM;
        /** The segment it matched (index of its first point). */
        final int segment;

        Fix(double remainingM, double offRouteM, int segment) {
            this.remainingM = remainingM;
            this.offRouteM = offRouteM;
            this.segment = segment;
        }
    }

    private final double[] lat;
    private final double[] lng;
    /** Metres from point i to the end of the line. */
    private final double[] toEnd;
    /** The whole line's length as the route service gave it (may differ slightly from the points). */
    final double totalM;

    RouteLine(double[] lat, double[] lng, double totalM) {
        if (lat.length != lng.length || lat.length < 2) {
            throw new IllegalArgumentException("a route line needs at least two points");
        }
        this.lat = lat;
        this.lng = lng;
        int n = lat.length;
        toEnd = new double[n];
        for (int i = n - 2; i >= 0; i--) {
            toEnd[i] = toEnd[i + 1] + metres(lat[i], lng[i], lat[i + 1], lng[i + 1]);
        }
        // Scale so a fix at the start reads exactly what the route service said (its km are what
        // Google Maps shows; the decoded points are rounded to 1e-5°).
        double scale = totalM > 0 && toEnd[0] > 0 ? totalM / toEnd[0] : 1.0;
        if (scale != 1.0) {
            for (int i = 0; i < n; i++) {
                toEnd[i] *= scale;
            }
        }
        this.totalM = totalM > 0 ? totalM : toEnd[0];
    }

    int size() {
        return lat.length;
    }

    /**
     * Snaps the car onto the nearest point of the line, not looking more than a few segments behind
     * the last match: a road that passes close to itself (a loop, a U-turn, the two sides of a
     * highway) does not make the count jump back.
     *
     * @param after the segment of the last fix, or -1 for none
     */
    Fix locate(double carLat, double carLng, int after) {
        int from = after < 0 ? 0 : Math.max(0, after - BACK_SEGMENTS);
        double cosLat = Math.cos(Math.toRadians(carLat));
        double bestD = Double.MAX_VALUE;
        int best = -1;
        double bestT = 0;
        // first pass: the nearest segment from `from` on
        double[] d = new double[lat.length - 1];
        double[] t = new double[lat.length - 1];
        for (int i = 0; i < lat.length - 1; i++) {
            // local flat projection around the car, in metres: fine for one segment
            double ax = Math.toRadians(lng[i] - carLng) * cosLat * R;
            double ay = Math.toRadians(lat[i] - carLat) * R;
            double bx = Math.toRadians(lng[i + 1] - carLng) * cosLat * R;
            double by = Math.toRadians(lat[i + 1] - carLat) * R;
            double dx = bx - ax;
            double dy = by - ay;
            double len2 = dx * dx + dy * dy;
            double u = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
            double px = ax + u * dx;
            double py = ay + u * dy;
            d[i] = Math.sqrt(px * px + py * py);
            t[i] = u;
            if (i >= from && d[i] < bestD) {
                bestD = d[i];
                best = i;
                bestT = u;
            }
        }
        if (best < 0) {
            // `after` past the end: search the whole line
            for (int i = 0; i < d.length; i++) {
                if (d[i] < bestD) {
                    bestD = d[i];
                    best = i;
                    bestT = t[i];
                }
            }
        }
        double segLen = toEnd[best] - toEnd[best + 1];
        double remaining = toEnd[best + 1] + (1 - bestT) * segLen;
        return new Fix(remaining, bestD, best);
    }

    /** Great-circle metres between two points. */
    static double metres(double lat1, double lng1, double lat2, double lng2) {
        return Arrival.straightKm(lat1, lng1, lat2, lng2) * 1000.0;
    }

    /**
     * Decodes Google's encoded polyline (the format of both the Google Routes API and OSRM's
     * {@code geometries=polyline}), precision 1e-5. Null if it is not one.
     */
    static RouteLine decode(String encoded, double totalM) {
        if (encoded == null || encoded.isEmpty()) {
            return null;
        }
        List<double[]> points = new ArrayList<>();
        int index = 0;
        int len = encoded.length();
        long latE5 = 0;
        long lngE5 = 0;
        try {
            while (index < len) {
                long[] r = next(encoded, index);
                latE5 += r[0];
                index = (int) r[1];
                r = next(encoded, index);
                lngE5 += r[0];
                index = (int) r[1];
                points.add(new double[] {latE5 / 1e5, lngE5 / 1e5});
            }
        } catch (IndexOutOfBoundsException e) {
            return null;
        }
        if (points.size() < 2) {
            return null;
        }
        double[] la = new double[points.size()];
        double[] ln = new double[points.size()];
        for (int i = 0; i < la.length; i++) {
            la[i] = points.get(i)[0];
            ln[i] = points.get(i)[1];
            if (la[i] < -90 || la[i] > 90 || ln[i] < -180 || ln[i] > 180) {
                return null;
            }
        }
        return new RouteLine(la, ln, totalM);
    }

    /** One signed value and the index after it. */
    private static long[] next(String s, int index) {
        long result = 0;
        int shift = 0;
        int b;
        do {
            b = s.charAt(index++) - 63;
            if (b < 0 || shift > 60) {
                throw new IndexOutOfBoundsException("not a polyline");
            }
            result |= (long) (b & 0x1f) << shift;
            shift += 5;
        } while (b >= 0x20);
        long value = (result & 1) != 0 ? ~(result >> 1) : (result >> 1);
        return new long[] {value, index};
    }
}
