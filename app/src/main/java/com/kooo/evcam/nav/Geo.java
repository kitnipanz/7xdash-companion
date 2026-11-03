package com.kooo.evcam.nav;

/** Distances, bearings and the decoder for Google's encoded polylines. Pure Java, see the test. */
public final class Geo {

    private static final double EARTH_M = 6_371_000.0;

    private Geo() {
    }

    /** Great-circle metres between two points. */
    public static double meters(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_M * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** Initial bearing from the first point to the second, 0..360 clockwise from north. */
    public static double bearing(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lng2 - lng1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /**
     * Where the point (lat, lng) falls on the segment a→b: {@code [t, metres off]}, t from 0 (at a)
     * to 1 (at b). Flat-earth around the point, which is exact enough for road segments.
     */
    public static double[] project(double lat, double lng, double aLat, double aLng, double bLat, double bLng) {
        double k = Math.cos(Math.toRadians(lat));
        double mPerDeg = Math.toRadians(EARTH_M);
        double ax = (aLng - lng) * k * mPerDeg;
        double ay = (aLat - lat) * mPerDeg;
        double bx = (bLng - lng) * k * mPerDeg;
        double by = (bLat - lat) * mPerDeg;
        double dx = bx - ax;
        double dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
        double px = ax + t * dx;
        double py = ay + t * dy;
        return new double[]{t, Math.sqrt(px * px + py * py)};
    }

    /**
     * Google's encoded polyline format (Routes API, Directions) as {@code [lat0, lng0, lat1, lng1, ...]}.
     * Stops at the first broken value instead of throwing.
     */
    public static double[] decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return new double[0];
        }
        double[] out = new double[encoded.length()];
        int n = 0;
        int index = 0;
        int lat = 0;
        int lng = 0;
        int len = encoded.length();
        while (index < len) {
            int[] r = next(encoded, index);
            if (r == null) {
                break;
            }
            lat += r[0];
            index = r[1];
            r = next(encoded, index);
            if (r == null) {
                break;
            }
            lng += r[0];
            index = r[1];
            out[n++] = lat / 1e5;
            out[n++] = lng / 1e5;
        }
        double[] trimmed = new double[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    /** One signed value and where the next one starts, or null when the string ends mid-value. */
    private static int[] next(String s, int index) {
        int result = 0;
        int shift = 0;
        int b;
        do {
            if (index >= s.length() || shift > 30) {
                return null;
            }
            b = s.charAt(index++) - 63;
            result |= (b & 0x1f) << shift;
            shift += 5;
        } while (b >= 0x20);
        return new int[]{(result & 1) != 0 ? ~(result >> 1) : (result >> 1), index};
    }
}
