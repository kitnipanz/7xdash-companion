package com.kooo.evcam.trip;

import android.content.Context;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * Where the phone sends the place, over the car's Wi-Fi (wireless CarPlay already puts the
 * iPhone on it). A fixed port and a secret path kept in AppConfig, so the iPhone Shortcut made
 * once keeps working:
 *
 * <ul>
 *   <li>{@code /t/<token>}: a page for the phone (the QR code in {@link ArrivalActivity} opens it)
 *   with a box to paste a link and the steps to make the Shortcut.</li>
 *   <li>{@code /t/<token>/go}: takes {@code link} (form, query or the plain body) and answers in one
 *   line the Shortcut can show.</li>
 * </ul>
 * Anything else is a 404, wrong token included.
 */
final class DestinationServer extends NanoHTTPD {

    static final int PORT = 7710;
    private static final String TAG = "DestinationServer";
    private static final int MAX_BODY = 16 * 1024;

    private final Context context;
    private final Trip trip;

    DestinationServer(Context context, Trip trip) {
        super(PORT);
        this.context = context.getApplicationContext();
        this.trip = trip;
    }

    /** The page's address on this host, e.g. http://192.168.1.1:7710/t/ab12cd34ef. */
    static String pageUrl(Context context, String host) {
        return String.format(Locale.US, "http://%s:%d/t/%s", host, PORT, new AppConfig(context).getArrivalToken());
    }

    /** The address the Shortcut posts to. */
    static String sendUrl(Context context, String host) {
        return pageUrl(context, host) + "/go";
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri() == null ? "" : session.getUri();
        String base = "/t/" + new AppConfig(context).getArrivalToken();
        if (uri.equals(base) || uri.equals(base + "/")) {
            return html(page(null));
        }
        if (!uri.equals(base + "/go")) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
        }
        String link = link(session);
        boolean fromPage = "page".equals(param(session, "from"));
        if (link == null || link.trim().isEmpty()) {
            String msg = context.getString(R.string.arrival_web_nothing);
            return fromPage ? html(page(msg)) : text(Response.Status.BAD_REQUEST, msg);
        }
        DestinationLink.Place p = trip.receive(link);
        String msg = p == null ? context.getString(R.string.arrival_web_no_place)
                : context.getString(R.string.arrival_web_set, p.name.isEmpty()
                        ? String.format(Locale.US, "%.5f, %.5f", p.lat, p.lng) : p.name);
        return fromPage ? html(page(msg)) : text(p == null ? Response.Status.BAD_REQUEST : Response.Status.OK, msg);
    }

    /** The link from the form, the query or the plain body. */
    private String link(IHTTPSession session) {
        Map<String, String> files = new HashMap<>();
        if (Method.POST.equals(session.getMethod()) || Method.PUT.equals(session.getMethod())) {
            try {
                long length = Long.parseLong(session.getHeaders().getOrDefault("content-length", "0"));
                if (length > MAX_BODY) {
                    return null;
                }
                session.parseBody(files);
            } catch (Exception e) {
                AppLog.w(TAG, "could not read the body: " + e.getMessage());
                return null;
            }
        }
        String link = param(session, "link");
        if (link == null) {
            link = param(session, "url");
        }
        if (link == null) {
            link = param(session, "text");
        }
        if (link == null) {
            // a plain text / JSON body lands here
            link = files.get("postData");
        }
        return link;
    }

    private static String param(IHTTPSession session, String key) {
        List<String> values = session.getParameters().get(key);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private String page(String message) {
        String send = "/t/" + new AppConfig(context).getArrivalToken() + "/go";
        String host = context.getString(R.string.arrival_web_host_hint);
        StringBuilder steps = new StringBuilder();
        for (int id : new int[] {R.string.arrival_step_1, R.string.arrival_step_2, R.string.arrival_step_3,
                R.string.arrival_step_4, R.string.arrival_step_5}) {
            steps.append("<li>").append(escape(context.getString(id))).append("</li>");
        }
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>7xDash</title><style>"
                + "body{margin:0;padding:20px 16px;background:#141518;color:#fff;"
                + "font-family:system-ui,-apple-system,sans-serif;line-height:1.5}"
                + "h1{font-size:20px;margin:0 0 4px}.beta{color:#ff7a1a;font-size:13px;font-weight:600}"
                + ".msg{margin:16px 0;padding:12px 14px;background:#24262b;border-radius:10px}"
                + "textarea{width:100%;box-sizing:border-box;height:90px;border-radius:10px;border:0;padding:10px;"
                + "font-size:16px;font-family:inherit;background:#24262b;color:#fff}"
                + "button{margin-top:10px;width:100%;padding:14px;border:0;border-radius:10px;background:#ff7a1a;"
                + "color:#fff;font-size:17px;font-weight:600}"
                + "h2{font-size:16px;margin:28px 0 6px}ol{padding-left:20px}li{margin:6px 0;color:#ddd}"
                + "code{display:block;margin:8px 0;padding:10px;background:#24262b;border-radius:8px;"
                + "word-break:break-all;user-select:all;-webkit-user-select:all;font-size:14px}"
                + ".hint{color:#8e9096;font-size:13px}"
                + "</style></head><body>"
                + "<h1>" + escape(context.getString(R.string.arrival_title)) + "</h1>"
                + "<div class=\"beta\">BETA</div>"
                + (message == null ? "" : "<div class=\"msg\">" + escape(message) + "</div>")
                + "<form method=\"post\" action=\"" + send + "\"><input type=\"hidden\" name=\"from\" value=\"page\">"
                + "<textarea name=\"link\" placeholder=\"" + escape(context.getString(R.string.arrival_web_paste))
                + "\"></textarea><button type=\"submit\">" + escape(context.getString(R.string.arrival_web_send))
                + "</button></form>"
                + "<h2>" + escape(context.getString(R.string.arrival_web_shortcut_title)) + "</h2>"
                + "<div class=\"hint\">" + escape(context.getString(R.string.arrival_web_shortcut_address)) + "</div>"
                + "<code id=\"u\"></code>"
                + "<ol>" + steps + "</ol>"
                + "<div class=\"hint\">" + escape(host) + "</div>"
                + "<script>document.getElementById('u').textContent=location.origin+'" + send + "';</script>"
                + "</body></html>";
    }

    private Response html(String body) {
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body);
    }

    private Response text(Response.Status status, String body) {
        return newFixedLengthResponse(status, "text/plain; charset=utf-8", body);
    }

    static String escape(String raw) {
        return raw == null ? "" : raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
