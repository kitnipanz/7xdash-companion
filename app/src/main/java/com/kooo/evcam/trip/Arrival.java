package com.kooo.evcam.trip;

/**
 * Battery at arrival from three numbers the car and the route give: battery %, the car's own
 * range estimate and the km still to drive. The car's range already allows for how it is being
 * driven, the air conditioning and the weather, so the battery left is the share of that range
 * not used up: battery x (range - km left) / range. Pure Java, see the test.
 */
public final class Arrival {

    /** Straight-line km times this is a fair guess at road km until the route service answers. */
    public static final double ROAD_FACTOR = 1.3;
    /** Below this battery % at arrival the panel turns orange. */
    public static final float LOW_PERCENT = 15f;

    public enum Level { OK, LOW, SHORT }

    private Arrival() {
    }

    public static final class Estimate {
        public final double kmLeft;
        /** Battery % on arrival; negative when the range runs out first. */
        public final float percent;
        /** Range left on arrival; negative = km short. */
        public final double spareKm;
        public final Level level;

        Estimate(double kmLeft, float percent, double spareKm, Level level) {
            this.kmLeft = kmLeft;
            this.percent = percent;
            this.spareKm = spareKm;
            this.level = level;
        }
    }

    /** Null when the car has not given a battery % and a range above zero yet. */
    public static Estimate estimate(Float batteryPercent, Float rangeKm, double kmLeft) {
        if (batteryPercent == null || rangeKm == null || rangeKm <= 0f || kmLeft < 0) {
            return null;
        }
        double spare = rangeKm - kmLeft;
        float percent = (float) (batteryPercent * spare / rangeKm);
        Level level = spare < 0 ? Level.SHORT : percent < LOW_PERCENT ? Level.LOW : Level.OK;
        return new Estimate(kmLeft, percent, spare, level);
    }

    /** Great-circle distance in km. */
    public static double straightKm(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /** Initial bearing from the first point to the second, degrees clockwise from north, 0..360. */
    public static double bearing(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lng2 - lng1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }
}
