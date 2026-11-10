package com.kooo.evcam.nav;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.trip.DestinationLink;
import com.kooo.evcam.trip.Trip;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Navigation for 7xDash web, through the Car Link: the state the car tells the hub
 * ({@code {"t":"nav",…}}: whether it takes places, where it is going, the trip, how the last place
 * went) and the places the web sends ({@code {"t":"place","text":…}}), which go the same way as
 * one shared from the iPhone ({@link Trip#receive}): a link, coordinates, or a place name.
 */
public final class NavRemote {

    private static final String TAG = "NavRemote";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nav-remote");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** The place last sent from the web and how it went; null before the first. */
    private static volatile JSONObject last;

    private NavRemote() {
    }

    /** Takes places from the web: Navigation taking shared places. */
    public static boolean supported(Context context) {
        return Navigator.active(context) && new AppConfig(context).isNavShareEnabled();
    }

    /** The {@code {"t":"nav",…}} message for the hub. */
    public static JSONObject status(Context context) {
        JSONObject out = new JSONObject();
        try {
            out.put("t", "nav");
            out.put("beta", true);
            boolean on = supported(context);
            out.put("supported", on);
            if (on) {
                DestinationLink.Place to = Navigator.get().snapshot().destination;
                if (to == null) {
                    to = Trip.get().status().destination;
                }
                out.put("to", to == null ? "" : NavMapPainter.placeName(to));
                TripLine trip = TripLine.now(context);
                out.put("trip", trip == null ? "" : trip.text);
            }
            JSONObject l = last;
            if (l != null) {
                out.put("last", l);
            }
        } catch (Exception ignored) {
            // a field that would not go in: send the rest
        }
        return out;
    }

    /** A place sent from 7xDash web. Looks it up off the main thread; {@code after} runs on the main thread. */
    public static void place(Context context, String text, Runnable after) {
        Context app = context.getApplicationContext();
        if (text == null || text.trim().isEmpty() || !supported(app)) {
            return;
        }
        String sent = text.trim();
        WORKER.execute(() -> {
            DestinationLink.Place p = Trip.get().receive(sent);
            JSONObject result = new JSONObject();
            try {
                result.put("text", sent.length() > 120 ? sent.substring(0, 120) : sent);
                result.put("ok", p != null);
                result.put("name", p == null ? "" : NavMapPainter.placeName(p));
                result.put("at", System.currentTimeMillis());
            } catch (Exception ignored) {
                // keep what went in
            }
            last = result;
            AppLog.i(TAG, "place from 7xDash web: " + (p == null ? "not found" : "found"));
            MAIN.post(after);
        });
    }
}
