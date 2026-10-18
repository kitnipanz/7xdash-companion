package com.kooo.evcam.airplay;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.TrafficStats;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Process;

import androidx.annotation.Nullable;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Locale;

/**
 * Network lines for the AirPlay log: which network the car uses for internet (its own mobile
 * data, a Wi-Fi router, or a phone's hotspot), where the iPhone is, and how much data a cast or
 * a mirroring session moved.
 *
 * <p>Casting downloads the video over the car's internet; mirroring only moves the iPhone's
 * screen over the local Wi-Fi. These lines show which one happened and over what.</p>
 */
final class NetworkReport {

    private final Context context;
    private final ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback watcher;
    private String lastDefault = "";
    private long sessionStartBytes = -1;
    private String sessionKind;

    NetworkReport(Context context) {
        this.context = context.getApplicationContext();
        this.cm = this.context.getSystemService(ConnectivityManager.class);
    }

    /** All networks once, then a line each time the car's internet moves to another network. */
    void start() {
        logAll();
        if (cm == null) {
            return;
        }
        watcher = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                defaultChanged(network);
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                defaultChanged(network);
            }

            @Override
            public void onLost(Network network) {
                AirPlayLog.i("net: the car's internet network went away");
                lastDefault = "";
            }
        };
        try {
            cm.registerDefaultNetworkCallback(watcher);
        } catch (RuntimeException e) {
            AirPlayLog.w("net: cannot watch the default network: " + e);
            watcher = null;
        }
    }

    void stop() {
        if (cm != null && watcher != null) {
            try {
                cm.unregisterNetworkCallback(watcher);
            } catch (RuntimeException ignored) {
                // never registered
            }
        }
        watcher = null;
    }

    private void defaultChanged(Network network) {
        String line = describe(network);
        if (!line.equals(lastDefault)) {
            lastDefault = line;
            AirPlayLog.i("net: car internet now via " + line);
        }
    }

    /** Every network the car has, the internet one marked. */
    void logAll() {
        if (cm == null) {
            AirPlayLog.w("net: no connectivity service");
            return;
        }
        Network active = cm.getActiveNetwork();
        AirPlayLog.i("net: car internet via " + (active == null ? "nothing (offline)" : describe(active)));
        for (Network n : cm.getAllNetworks()) {
            if (!n.equals(active)) {
                AirPlayLog.i("net: also " + describe(n));
            }
        }
    }

    /** "Wi-Fi 'Home' on wlan2 192.168.5.79, router 192.168.5.1 (Wi-Fi router), internet ok, unmetered". */
    String describe(Network n) {
        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
        LinkProperties link = cm.getLinkProperties(n);
        StringBuilder sb = new StringBuilder();
        boolean wifi = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        if (caps == null) {
            sb.append("unknown");
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            sb.append("VPN");
        } else if (wifi) {
            sb.append("Wi-Fi");
            String ssid = ssid(caps);
            if (ssid != null) {
                sb.append(" '").append(ssid).append('\'');
            }
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            sb.append("the car's own mobile data");
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            sb.append("Ethernet (in-car network)");
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
            sb.append("Bluetooth tethering");
        } else {
            sb.append("other");
        }
        String gateway = null;
        if (link != null) {
            sb.append(" on ").append(link.getInterfaceName());
            for (LinkAddress a : link.getLinkAddresses()) {
                if (a.getAddress() instanceof Inet4Address) {
                    sb.append(' ').append(a.getAddress().getHostAddress());
                }
            }
            for (RouteInfo r : link.getRoutes()) {
                if (r.isDefaultRoute() && r.getGateway() instanceof Inet4Address) {
                    gateway = r.getGateway().getHostAddress();
                    sb.append(", router ").append(gateway);
                    break;
                }
            }
        }
        if (wifi) {
            sb.append(" (").append(wifiKind(gateway, caps)).append(')');
        }
        if (caps != null) {
            sb.append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    ? ", internet ok" : ", no internet");
            sb.append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    ? ", unmetered" : ", metered");
        }
        return sb.toString();
    }

    /** iPhone hotspots always use 172.20.10.x; Android ones usually say "metered". */
    static String wifiKind(@Nullable String gateway, @Nullable NetworkCapabilities caps) {
        if (gateway != null && gateway.startsWith("172.20.10.")) {
            return "iPhone Personal Hotspot: the phone's mobile data";
        }
        if (caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            return "likely a phone hotspot: mobile data";
        }
        return "Wi-Fi router";
    }

    @Nullable
    private String ssid(NetworkCapabilities caps) {
        String s = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && caps.getTransportInfo() instanceof WifiInfo) {
            s = ((WifiInfo) caps.getTransportInfo()).getSSID();
        }
        if (s == null || s.contains("unknown")) {
            try {
                WifiManager wm = context.getSystemService(WifiManager.class);
                WifiInfo info = wm == null ? null : wm.getConnectionInfo();
                s = info == null ? null : info.getSSID();
            } catch (RuntimeException ignored) {
                s = null;
            }
        }
        if (s == null || s.contains("unknown")) {
            return null; // Android hides the name without location access
        }
        return s.replace("\"", "");
    }

    /** "Remote: 192.168.5.23" from the receiver: say where the iPhone is relative to the car. */
    void iphoneAt(String address) {
        String clean = address.trim();
        if (clean.startsWith("::ffff:")) {
            clean = clean.substring(7);
        }
        String where = "not on any of the car's networks";
        if (cm != null) {
            for (Network n : cm.getAllNetworks()) {
                LinkProperties link = cm.getLinkProperties(n);
                if (link != null && covers(link, clean)) {
                    where = "on the same network as the car: " + describe(n);
                    break;
                }
            }
        }
        AirPlayLog.i("net: iPhone at " + clean + ", " + where);
    }

    private static boolean covers(LinkProperties link, String ip) {
        try {
            InetAddress target = InetAddress.getByName(ip);
            if (!(target instanceof Inet4Address)) {
                return false;
            }
            int t = toInt((Inet4Address) target);
            for (LinkAddress a : link.getLinkAddresses()) {
                if (a.getAddress() instanceof Inet4Address) {
                    int prefix = a.getPrefixLength();
                    int mask = prefix <= 0 ? 0 : -1 << (32 - prefix);
                    if (((toInt((Inet4Address) a.getAddress()) ^ t) & mask) == 0) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
            // not an address
        }
        return false;
    }

    private static int toInt(Inet4Address a) {
        byte[] b = a.getAddress();
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }

    /** Start counting this app's received bytes for a cast or a mirroring session. */
    void sessionStarted(String kind) {
        sessionKind = kind;
        sessionStartBytes = TrafficStats.getUidRxBytes(Process.myUid());
    }

    /** How much came in during the session, and over what. */
    void sessionEnded() {
        if (sessionKind == null || sessionStartBytes < 0) {
            return;
        }
        long now = TrafficStats.getUidRxBytes(Process.myUid());
        long used = now >= sessionStartBytes ? now - sessionStartBytes : -1;
        String where;
        if ("cast".equals(sessionKind)) {
            Network active = cm == null ? null : cm.getActiveNetwork();
            where = "downloaded by the car over " + (active == null ? "?" : describe(active));
        } else {
            where = "the iPhone's screen over the local Wi-Fi (the iPhone used its own internet)";
        }
        AirPlayLog.i(String.format(Locale.US, "net: %s ended, %s received: %s", sessionKind,
                used < 0 ? "?" : megabytes(used), where));
        sessionKind = null;
        sessionStartBytes = -1;
    }

    static String megabytes(long bytes) {
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
