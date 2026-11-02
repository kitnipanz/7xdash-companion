package com.kooo.evcam.trip;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the place in whatever the phone shared: a Google Maps or Apple Maps link, the text the
 * share sheet wraps around it, or plain "lat, lng". Short links (maps.app.goo.gl, maps.apple/p)
 * carry no coordinates; {@link #firstUrl} is what {@link RouteLookup#resolve} follows first.
 * Pure Java, see the test.
 */
public final class DestinationLink {

    private DestinationLink() {
    }

    /** A place on the map. */
    public static final class Place {
        public final double lat;
        public final double lng;
        /** What the phone called it; may be empty. */
        public final String name;

        public Place(double lat, double lng, String name) {
            this.lat = lat;
            this.lng = lng;
            this.name = name == null ? "" : name;
        }

        /** "lat,lng,setAt,name": the form AppConfig keeps. */
        public String store(long setAt) {
            return String.format(Locale.US, "%.6f,%.6f,%d,%s", lat, lng, setAt, name.replace('\n', ' '));
        }

        /** The other way round; null when there is nothing (or nothing readable) stored. */
        public static Place restore(String stored) {
            if (stored == null || stored.isEmpty()) {
                return null;
            }
            String[] parts = stored.split(",", 4);
            if (parts.length < 3) {
                return null;
            }
            try {
                return new Place(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                        parts.length == 4 ? parts[3] : "");
            } catch (NumberFormatException e) {
                return null;
            }
        }

        public static long storedAt(String stored) {
            try {
                return Long.parseLong(stored.split(",", 4)[2]);
            } catch (RuntimeException e) {
                return 0L;
            }
        }
    }

    private static final String NUM = "(-?\\d{1,3}(?:\\.\\d+)?)";
    /** Google's pin: !3d<lat>!4d<lng>. Better than the @ map centre. */
    private static final Pattern PIN = Pattern.compile("!3d" + NUM + "!4d" + NUM);
    private static final Pattern AT = Pattern.compile("@" + NUM + "," + NUM);
    private static final Pattern PAIR = Pattern.compile("^\\s*" + NUM + "\\s*,\\s*" + NUM + "\\s*$");
    private static final Pattern PAIR_IN_TEXT = Pattern.compile("(?<![\\d.])" + NUM + "\\s*,\\s*" + NUM + "(?![\\d.])");
    private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>]+");
    private static final Pattern GOOGLE_PLACE_NAME = Pattern.compile("/maps/place/([^/@?]+)");
    /** Query keys that hold the destination, best first. */
    private static final String[] COORD_KEYS = {"destination", "daddr", "coordinate", "ll", "q", "query", "sll",
            "center"};
    private static final String[] NAME_KEYS = {"name", "q", "query", "address", "daddr", "destination"};

    /** The place in a shared text or link, or null when it has no coordinates of its own. */
    public static Place parse(String shared) {
        if (shared == null) {
            return null;
        }
        String text = shared.trim();
        if (text.isEmpty()) {
            return null;
        }
        String url = firstUrl(text);
        String name = nameOf(text, url);
        if (url != null) {
            Place p = fromUrl(url, name);
            if (p != null) {
                return p;
            }
        }
        // Plain "13.7563, 100.5018", or coordinates somewhere in the text (not inside the link)
        String rest = url == null ? text : text.replace(url, " ");
        Matcher m = PAIR.matcher(rest);
        if (m.find()) {
            return place(m.group(1), m.group(2), name);
        }
        m = PAIR_IN_TEXT.matcher(rest);
        while (m.find()) {
            Place p = place(m.group(1), m.group(2), name);
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    /** The first http(s) link in the text, or null. */
    public static String firstUrl(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = URL.matcher(text);
        return m.find() ? m.group() : null;
    }

    private static Place fromUrl(String url, String name) {
        Matcher m = PIN.matcher(url);
        if (m.find()) {
            Place p = place(m.group(1), m.group(2), name);
            if (p != null) {
                return p;
            }
        }
        for (String key : COORD_KEYS) {
            String value = query(url, key);
            if (value != null) {
                Matcher pair = PAIR.matcher(value);
                if (pair.find()) {
                    Place p = place(pair.group(1), pair.group(2), name);
                    if (p != null) {
                        return p;
                    }
                }
            }
        }
        m = AT.matcher(url);
        if (m.find()) {
            return place(m.group(1), m.group(2), name);
        }
        return null;
    }

    /** The place's name: from the link if it has one, else the first line of text that is not the link. */
    static String nameOf(String text, String url) {
        if (url != null) {
            Matcher m = GOOGLE_PLACE_NAME.matcher(url);
            if (m.find()) {
                String n = decode(m.group(1)).trim();
                if (!n.isEmpty() && !PAIR.matcher(n).find()) {
                    return n;
                }
            }
            for (String key : NAME_KEYS) {
                String n = query(url, key);
                if (n != null && !n.trim().isEmpty() && !PAIR.matcher(n).find()) {
                    return n.trim();
                }
            }
        }
        for (String line : text.split("\\r?\\n")) {
            String l = url == null ? line.trim() : line.replace(url, "").trim();
            if (!l.isEmpty() && !PAIR.matcher(l).find() && firstUrl(l) == null) {
                return l.length() > 80 ? l.substring(0, 80) : l;
            }
        }
        return "";
    }

    /** One query value, decoded; null when the key is not there. */
    static String query(String url, String key) {
        int q = url.indexOf('?');
        if (q < 0) {
            return null;
        }
        String query = url.substring(q + 1);
        int hash = query.indexOf('#');
        if (hash >= 0) {
            query = query.substring(0, hash);
        }
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            if (k.equalsIgnoreCase(key)) {
                return eq < 0 ? "" : decode(part.substring(eq + 1));
            }
        }
        return null;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return s;
        }
    }

    private static Place place(String lat, String lng, String name) {
        try {
            double a = Double.parseDouble(lat);
            double b = Double.parseDouble(lng);
            if (a < -90 || a > 90 || b < -180 || b > 180 || (a == 0 && b == 0)) {
                return null;
            }
            return new Place(a, b, name);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
