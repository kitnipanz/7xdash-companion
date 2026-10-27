package com.kooo.evcam.analytics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GA4 Measurement Protocol request bodies. Pure (no Android), so it is unit tested.
 *
 * <p>Limits from the Measurement Protocol: 25 events per request, event and parameter names up to
 * 40 characters of letters, digits and underscores starting with a letter, parameter values up to
 * 100 characters, user property values up to 36.</p>
 */
final class AnalyticsPayload {

    static final int MAX_EVENTS = 25;
    static final int MAX_NAME = 40;
    static final int MAX_VALUE = 100;
    static final int MAX_USER_VALUE = 36;
    /** GA4 only counts an event toward active users with some engagement time. */
    static final int ENGAGEMENT_MS = 100;

    /** One queued event: name, parameters, and when it happened (sent as timestamp_micros). */
    static final class Event {
        final String name;
        final Map<String, String> params;
        final long atMs;

        Event(String name, Map<String, String> params, long atMs) {
            this.name = name;
            this.params = params;
            this.atMs = atMs;
        }
    }

    private AnalyticsPayload() {
    }

    /** A valid GA4 name: lower case, anything else becomes '_', starts with a letter, at most 40 characters. */
    static String name(String raw) {
        if (raw == null) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toLowerCase(Locale.ROOT).toCharArray()) {
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
            sb.append(ok ? c : '_');
        }
        while (sb.length() > 0 && !Character.isLetter(sb.charAt(0))) {
            sb.deleteCharAt(0);
        }
        if (sb.length() == 0) {
            return "unknown";
        }
        return sb.length() > MAX_NAME ? sb.substring(0, MAX_NAME) : sb.toString();
    }

    static String value(String raw, int max) {
        if (raw == null) {
            return "";
        }
        return raw.length() > max ? raw.substring(0, max) : raw;
    }

    /** Minutes bucketed so a duration is a small set of values: 0, 1, 5, 15, 30, 60, 120 (2 h or more). */
    static String minutesBucket(long ms) {
        long min = Math.max(0, ms) / 60_000L;
        long[] edges = {1, 5, 15, 30, 60, 120};
        long bucket = 0;
        for (long edge : edges) {
            if (min >= edge) {
                bucket = edge;
            }
        }
        return String.valueOf(bucket);
    }

    /** Packages whose frames are 7xDash's own code (the AirPlay bridge included). */
    private static final String[] APP_PACKAGES = {"com.kooo.evcam.", "io.github.jqssun.airplay."};
    static final int MAX_FRAME_PARAMS = 5;

    /**
     * Where a crash happened, for the app_crash event: the deepest cause's stack, 7xDash frames
     * only (the top 3 frames of any code when none is ours), as {@code Class.method:line} joined
     * by {@code " < "} and cut into up to {@link #MAX_FRAME_PARAMS} values of
     * {@link #MAX_VALUE} characters. Code locations only: never the exception's message.
     */
    static List<String> crashFrames(Throwable t) {
        List<String> out = new java.util.ArrayList<>();
        if (t == null) {
            return out;
        }
        Throwable root = t;
        for (int i = 0; root.getCause() != null && root.getCause() != root && i < 10; i++) {
            root = root.getCause();
        }
        StackTraceElement[] stack = root.getStackTrace();
        List<String> frames = new java.util.ArrayList<>();
        for (StackTraceElement e : stack) {
            if (isApp(e.getClassName())) {
                frames.add(frame(e));
            }
        }
        if (frames.isEmpty()) {
            for (int i = 0; i < Math.min(3, stack.length); i++) {
                frames.add(frame(stack[i]));
            }
        }
        StringBuilder chunk = new StringBuilder();
        for (String f : frames) {
            String piece = chunk.length() == 0 ? f : " < " + f;
            if (chunk.length() + piece.length() > MAX_VALUE) {
                if (chunk.length() > 0) {
                    out.add(chunk.toString());
                }
                if (out.size() == MAX_FRAME_PARAMS) {
                    return out;
                }
                chunk = new StringBuilder(value(f, MAX_VALUE));
            } else {
                chunk.append(piece);
            }
        }
        if (chunk.length() > 0 && out.size() < MAX_FRAME_PARAMS) {
            out.add(chunk.toString());
        }
        return out;
    }

    private static boolean isApp(String className) {
        for (String p : APP_PACKAGES) {
            if (className != null && className.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** "ShortcutPerformer.perform:118"; inner and lambda classes keep their $ part. */
    private static String frame(StackTraceElement e) {
        String cls = e.getClassName();
        String simple = cls.substring(cls.lastIndexOf('.') + 1);
        return simple + "." + e.getMethodName() + ":" + e.getLineNumber();
    }

    /**
     * The JSON body for one request.
     *
     * @param clientId  random install id
     * @param sessionId this process start, seconds
     * @param debug     events show in GA4 DebugView
     */
    static String build(String clientId, long sessionId, Map<String, String> userProps,
                        List<Event> events, boolean debug) {
        JsonObject body = new JsonObject();
        body.addProperty("client_id", clientId);
        if (userProps != null && !userProps.isEmpty()) {
            JsonObject props = new JsonObject();
            for (Map.Entry<String, String> p : userProps.entrySet()) {
                JsonObject v = new JsonObject();
                v.addProperty("value", value(p.getValue(), MAX_USER_VALUE));
                props.add(name(p.getKey()), v);
            }
            body.add("user_properties", props);
        }
        JsonArray list = new JsonArray();
        int count = Math.min(events.size(), MAX_EVENTS);
        for (int i = 0; i < count; i++) {
            Event e = events.get(i);
            JsonObject event = new JsonObject();
            event.addProperty("name", name(e.name));
            JsonObject params = new JsonObject();
            if (e.params != null) {
                for (Map.Entry<String, String> p : e.params.entrySet()) {
                    params.addProperty(name(p.getKey()), value(p.getValue(), MAX_VALUE));
                }
            }
            params.addProperty("session_id", String.valueOf(sessionId));
            params.addProperty("engagement_time_msec", ENGAGEMENT_MS);
            if (debug) {
                params.addProperty("debug_mode", 1);
            }
            event.add("params", params);
            event.addProperty("timestamp_micros", e.atMs * 1000L);
            list.add(event);
        }
        body.add("events", list);
        return body.toString();
    }
}
