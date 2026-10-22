package com.kooo.evcam.parking;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks the floor and the bay out of what OCR read on the walls and pillars around the car.
 *
 * <p>Pure logic ({@code ParkingTextTest}). Car parks write these many ways: {@code B2},
 * {@code LEVEL 3}, {@code 4F}, {@code P3}, {@code ZONE C 045}, {@code A-12}. A line that looks
 * like a floor wins over one that only might be; the bay is the most bay-like token left over.</p>
 */
public final class ParkingText {

    /** What was found. Either part may be empty. */
    public static final class Spot {
        public final String floor;
        public final String bay;

        Spot(String floor, String bay) {
            this.floor = floor;
            this.bay = bay;
        }

        public boolean isEmpty() {
            return floor.isEmpty() && bay.isEmpty();
        }

        /** "B2 · A-12", "B2", "A-12" or "". */
        public String label() {
            if (!floor.isEmpty() && !bay.isEmpty()) {
                return floor + " · " + bay;
            }
            return floor.isEmpty() ? bay : floor;
        }
    }

    // "LEVEL 3", "FLOOR B1", "FL 2", "LV.4", "DECK 5"
    private static final Pattern FLOOR_WORD = Pattern.compile(
            "\\b(?:LEVEL|FLOOR|FLR|FL|LV|LVL|DECK|STOREY)\\.?\\s*([A-Z]?\\d{1,2}[A-Z]?)\\b");
    // "B2", "B1M", "UG", "LG2", "P3" (P alone is the parking sign, so P needs a digit)
    private static final Pattern FLOOR_CODE = Pattern.compile(
            "\\b((?:B|UG|LG|P|L)\\d{1,2}M?|UG|LG|GF|RF|ROOF)\\b");
    // "4F", "12F"
    private static final Pattern FLOOR_STOREY = Pattern.compile("\\b(\\d{1,2})F\\b");
    // "A-12", "C045", "B2-045", "ZONE C 12"
    private static final Pattern BAY_ZONED = Pattern.compile(
            "\\b(?:ZONE\\s*)?([A-Z]{1,2}\\d?)\\s?[-–.]?\\s?(\\d{1,4})\\b");
    // "NO. 125", "BAY 45", "LOT 12", "SPOT 7"
    private static final Pattern BAY_WORD = Pattern.compile(
            "\\b(?:NO|BAY|LOT|SPOT|SLOT|SPACE)\\.?\\s*([A-Z]?\\d{1,4})\\b");
    // A bare 2–4 digit number painted on the ground
    private static final Pattern BAY_NUMBER = Pattern.compile("^\\s*(\\d{2,4})\\s*$");

    private ParkingText() {
    }

    public static Spot pick(List<String> lines) {
        String floor = "";
        int floorScore = 0;
        String bay = "";
        int bayScore = 0;
        if (lines == null) {
            return new Spot("", "");
        }
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.toUpperCase(Locale.ROOT).trim();
            if (line.isEmpty()) {
                continue;
            }

            Matcher m = FLOOR_WORD.matcher(line);
            if (m.find() && floorScore < 3) {
                floor = m.group(1);
                floorScore = 3;
            }
            m = FLOOR_STOREY.matcher(line);
            if (m.find() && floorScore < 2) {
                floor = m.group(1) + "F";
                floorScore = 2;
            }
            m = FLOOR_CODE.matcher(line);
            if (m.find() && floorScore < 2 && !line.contains("-") && line.length() <= 6) {
                floor = m.group(1);
                floorScore = 2;
            }

            m = BAY_WORD.matcher(line);
            if (m.find() && bayScore < 3) {
                bay = m.group(1);
                bayScore = 3;
            }
            m = BAY_ZONED.matcher(line);
            while (m.find() && bayScore < 2) {
                String zone = m.group(1);
                if (isFloorToken(zone + m.group(2)) || FLOOR_WORD.matcher(line).find()) {
                    continue;
                }
                bay = zone + "-" + m.group(2);
                bayScore = 2;
            }
            m = BAY_NUMBER.matcher(line);
            if (m.find() && bayScore < 1) {
                bay = m.group(1);
                bayScore = 1;
            }
        }
        if (bay.equals(floor)) {
            bay = "";
        }
        return new Spot(floor, bay);
    }

    private static boolean isFloorToken(String token) {
        Matcher m = FLOOR_CODE.matcher(token);
        return m.matches() && token.length() <= 3;
    }
}
