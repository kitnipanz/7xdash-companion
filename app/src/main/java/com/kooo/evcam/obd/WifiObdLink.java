package com.kooo.evcam.obd;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * A Wi-Fi adapter: the car joins the adapter's network, and the adapter listens at the address
 * nearly all of them ship with. That network has no internet, so Android routes everything
 * else over mobile data; the socket has to be bound to Wi-Fi to reach it.
 */
final class WifiObdLink implements ObdLink {

    private static final String HOST = "192.168.0.10";
    private static final int PORT = 35000;
    private static final int CONNECT_MS = 5_000;

    private final ConnectivityManager networks;
    private volatile Socket socket;

    WifiObdLink(Context context) {
        networks = context.getApplicationContext().getSystemService(ConnectivityManager.class);
    }

    @Override
    public void open() throws IOException {
        Socket next = new Socket();
        socket = next;
        Network wifi = wifi();
        if (wifi != null) {
            wifi.bindSocket(next);
        }
        next.setTcpNoDelay(true);
        next.connect(new InetSocketAddress(HOST, PORT), CONNECT_MS);
    }

    private Network wifi() {
        if (networks == null) {
            return null;
        }
        for (Network network : networks.getAllNetworks()) {
            NetworkCapabilities caps = networks.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return network;
            }
        }
        return null;
    }

    @Override
    public InputStream input() throws IOException {
        return requireSocket().getInputStream();
    }

    @Override
    public OutputStream output() throws IOException {
        return requireSocket().getOutputStream();
    }

    @Override
    public void close() {
        Socket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException e) {
                // going anyway
            }
        }
    }

    private Socket requireSocket() throws IOException {
        Socket current = socket;
        if (current == null) {
            throw new IOException("closed");
        }
        return current;
    }
}
