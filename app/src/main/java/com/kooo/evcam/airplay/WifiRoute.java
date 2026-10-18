package com.kooo.evcam.airplay;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.okhttp.OkHttpDataSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import okhttp3.Dns;
import okhttp3.OkHttpClient;

/**
 * Sends a cast's downloads over the car's Wi-Fi when it has one with internet, instead of the
 * car's own mobile data (the 7X routes everything over its in-car network by default, even on
 * a home Wi-Fi). Local addresses (the receiver's own playlist proxy on localhost) always go
 * direct. Without such a Wi-Fi, or if Android does not show it to this app, downloads take the
 * default network as before.
 */
@OptIn(markerClass = UnstableApi.class)
final class WifiRoute {

    private final ConnectivityManager cm;
    private final OkHttpClient direct = new OkHttpClient();
    private volatile Network wifi;
    private volatile OkHttpClient overWifi;
    private volatile String wifiName = "";
    private ConnectivityManager.NetworkCallback watcher;

    WifiRoute(Context context) {
        cm = context.getApplicationContext().getSystemService(ConnectivityManager.class);
    }

    void start() {
        if (cm == null) {
            return;
        }
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        watcher = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities caps) {
                boolean usable = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                if (usable && !network.equals(wifi)) {
                    use(network);
                } else if (!usable && network.equals(wifi)) {
                    drop("Wi-Fi has no internet");
                }
            }

            @Override
            public void onLost(@NonNull Network network) {
                if (network.equals(wifi)) {
                    drop("Wi-Fi went away");
                }
            }
        };
        try {
            cm.registerNetworkCallback(request, watcher);
        } catch (RuntimeException e) {
            AirPlayLog.w("net: cannot watch Wi-Fi for casting: " + e);
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
        wifi = null;
        overWifi = null;
    }

    private void use(Network network) {
        LinkProperties link = cm.getLinkProperties(network);
        wifiName = link == null ? "Wi-Fi" : link.getInterfaceName();
        overWifi = new OkHttpClient.Builder()
                .socketFactory(network.getSocketFactory())
                .dns(new Dns() {
                    @NonNull
                    @Override
                    public List<InetAddress> lookup(@NonNull String host) throws UnknownHostException {
                        return Arrays.asList(network.getAllByName(host));
                    }
                })
                .build();
        wifi = network;
        AirPlayLog.i("net: casting downloads will use the car's Wi-Fi (" + wifiName + ")");
    }

    private void drop(String why) {
        wifi = null;
        overWifi = null;
        AirPlayLog.i("net: " + why + ": casting downloads go over the car's default network");
    }

    /** For the log: what a cast's downloads go over right now. */
    String describe() {
        return overWifi != null ? "the car's Wi-Fi (" + wifiName + ")"
                : "the car's default network (no usable Wi-Fi visible to 7xDash)";
    }

    /** ExoPlayer's data source: localhost direct, everything else over Wi-Fi when there is one. */
    DataSource.Factory dataSourceFactory() {
        return () -> new Routed();
    }

    private static boolean isLocal(@Nullable String host) {
        return host == null || host.equals("localhost") || host.startsWith("127.") || host.equals("::1");
    }

    /** Picks the client per request, by the address it goes to. */
    private final class Routed implements DataSource {
        private DataSource current;
        private final java.util.ArrayList<TransferListener> listeners = new java.util.ArrayList<>();

        @Override
        public void addTransferListener(@NonNull TransferListener listener) {
            listeners.add(listener);
        }

        @Override
        public long open(@NonNull DataSpec dataSpec) throws IOException {
            OkHttpClient client = isLocal(dataSpec.uri.getHost()) || overWifi == null ? direct : overWifi;
            current = new OkHttpDataSource.Factory(client).createDataSource();
            for (TransferListener l : listeners) {
                current.addTransferListener(l);
            }
            return current.open(dataSpec);
        }

        @Override
        public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
            return current.read(buffer, offset, length);
        }

        @Nullable
        @Override
        public Uri getUri() {
            return current == null ? null : current.getUri();
        }

        @NonNull
        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return current == null ? Collections.emptyMap() : current.getResponseHeaders();
        }

        @Override
        public void close() throws IOException {
            if (current != null) {
                try {
                    current.close();
                } finally {
                    current = null;
                }
            }
        }
    }
}
