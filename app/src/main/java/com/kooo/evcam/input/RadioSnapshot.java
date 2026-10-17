package com.kooo.evcam.input;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * One line on what else shares the car's radio chip: Wi-Fi (and its hotspot) and every
 * remembered Bluetooth device. Logged with each radio quiet gap, to find what takes the chip
 * from the sticker scan for 5.3 s every 11.6 s (car log 2026-10-17; not discovery).
 */
final class RadioSnapshot {

    private RadioSnapshot() {
    }

    static String describe(Context context, BluetoothAdapter adapter) {
        if (context == null) {
            return "";
        }
        return "wifi=" + wifi(context) + " bt=" + bonded(adapter);
    }

    private static String wifi(Context context) {
        StringBuilder sb = new StringBuilder();
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            sb.append(wifi == null ? "?" : wifi.isWifiEnabled() ? "on" : "off");
            if (wifi != null) {
                // Hidden but present on this SDK; the car's hotspot shares the chip too.
                Method ap = WifiManager.class.getMethod("isWifiApEnabled");
                if (Boolean.TRUE.equals(ap.invoke(wifi))) {
                    sb.append(",hotspot");
                }
            }
        } catch (Exception e) {
            if (sb.length() == 0) {
                sb.append('?');
            }
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network active = cm == null ? null : cm.getActiveNetwork();
            NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                sb.append(",connected");
            }
        } catch (RuntimeException ignored) {
            // no network state
        }
        return sb.toString();
    }

    /** "[name tail type conn, ...]", conn = yes/no/? (the connected check is hidden API). */
    private static String bonded(BluetoothAdapter adapter) {
        if (adapter == null) {
            return "?";
        }
        Set<BluetoothDevice> devices;
        try {
            devices = adapter.getBondedDevices();
        } catch (SecurityException e) {
            return "no-permission";
        }
        if (devices == null || devices.isEmpty()) {
            return "[]";
        }
        Method connected = null;
        try {
            connected = BluetoothDevice.class.getMethod("isConnected");
        } catch (NoSuchMethodException ignored) {
            // shown as conn=?
        }
        StringBuilder sb = new StringBuilder("[");
        for (BluetoothDevice device : devices) {
            if (sb.length() > 1) {
                sb.append("; ");
            }
            String name;
            int type;
            try {
                name = device.getName();
                type = device.getType();
            } catch (SecurityException e) {
                name = null;
                type = BluetoothDevice.DEVICE_TYPE_UNKNOWN;
            }
            String address = device.getAddress();
            sb.append(name == null ? "?" : name)
                    .append(' ').append(address == null || address.length() < 5 ? "?" : address.substring(address.length() - 5))
                    .append(' ').append(type == BluetoothDevice.DEVICE_TYPE_CLASSIC ? "classic"
                            : type == BluetoothDevice.DEVICE_TYPE_LE ? "le"
                            : type == BluetoothDevice.DEVICE_TYPE_DUAL ? "dual" : "?")
                    .append(" conn=");
            String conn = "?";
            if (connected != null) {
                try {
                    conn = Boolean.TRUE.equals(connected.invoke(device)) ? "yes" : "no";
                } catch (Exception ignored) {
                    // stays ?
                }
            }
            sb.append(conn);
        }
        return sb.append(']').toString();
    }
}
