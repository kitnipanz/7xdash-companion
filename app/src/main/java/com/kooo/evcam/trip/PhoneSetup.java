package com.kooo.evcam.trip;

import android.content.Context;

import com.kooo.evcam.R;
import com.kooo.evcam.share.LocalNetwork;

/**
 * What the iPhone needs to send places here: the page its camera opens (as a QR code) with the
 * Shortcut steps and a paste box ({@link DestinationServer}), and the address to send to. Shown on
 * the Navigation screen.
 */
public final class PhoneSetup {

    /** The page to put in the QR code; null when it cannot be reached (receiver off, no Wi-Fi). */
    public final String pageUrl;
    /** Under the code: the address to send to, or why there is no code. */
    public final String note;

    private PhoneSetup(String pageUrl, String note) {
        this.pageUrl = pageUrl;
        this.note = note;
    }

    public static PhoneSetup now(Context context) {
        if (!Trip.active(context)) {
            return new PhoneSetup(null, context.getString(R.string.nav_share_off));
        }
        LocalNetwork.Endpoint host = LocalNetwork.preferred(LocalNetwork.enumerate());
        if (host == null) {
            return new PhoneSetup(null, context.getString(R.string.arrival_no_network));
        }
        String url = DestinationServer.pageUrl(context, host.address);
        return new PhoneSetup(url, Trip.get().serverUp() ? DestinationServer.sendUrl(context, host.address)
                : context.getString(R.string.arrival_port_failed, DestinationServer.PORT));
    }
}
