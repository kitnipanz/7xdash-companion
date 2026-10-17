package com.kooo.evcam.airplay;

import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Announces the AirPlay receiver over mDNS on each network with that network's own address.
 *
 * <p>Why not NsdManager: on the 7X the system responder answers every network with one
 * internal address (198.18.34.15, the car's own vlan), so an iPhone on Wi-Fi sees "7xDash" but
 * connects to an address it cannot reach. This one answers a question with the address of the
 * network it came from, and stays off the car's internal links (vlan, veth, dummy, rmnet).</p>
 *
 * <p>Ported from jqssun/android-airplay-server's UsbMdnsAdvertiser (GPL-3.0), cut down: no
 * probing, one socket for all networks, rescans the networks every few seconds.</p>
 */
final class MdnsResponder {

    /** One service to announce: instance name, "_airplay._tcp", port, TXT. */
    static final class Service {
        final String name;
        final String type;
        final int port;
        final Map<String, String> txt;

        Service(String name, String type, int port, Map<String, String> txt) {
            this.name = name;
            this.type = type;
            this.port = port;
            this.txt = txt == null ? Collections.emptyMap() : txt;
        }
    }

    private static final int PORT = 5353;
    private static final InetSocketAddress GROUP;
    private static final int TYPE_A = 1;
    private static final int TYPE_PTR = 12;
    private static final int TYPE_TXT = 16;
    private static final int TYPE_SRV = 33;
    private static final int TYPE_ANY = 255;
    private static final int CLASS_IN = 1;
    private static final int CACHE_FLUSH = 0x8000;
    private static final int RESPONSE_FLAGS = 0x8400;
    private static final int TTL_HOST = 120;
    private static final int TTL_OTHER = 4500;
    private static final int LEGACY_TTL = 10;
    private static final long SCAN_MS = 5000;
    private static final String[] SERVICES = {"_services", "_dns-sd", "_udp", "local"};

    static {
        InetSocketAddress g;
        try {
            g = new InetSocketAddress(InetAddress.getByName("224.0.0.251"), PORT);
        } catch (Exception e) {
            g = null;
        }
        GROUP = g;
    }

    /** A network we answer on, with its IPv4 address and prefix. */
    private static final class Link {
        final NetworkInterface iface;
        final Inet4Address addr;
        final int mask;

        Link(NetworkInterface iface, Inet4Address addr, int prefix) {
            this.iface = iface;
            this.addr = addr;
            this.mask = prefix <= 0 ? 0 : -1 << (32 - prefix);
        }

        boolean covers(Inet4Address a) {
            return ((ip(a) ^ ip(addr)) & mask) == 0;
        }

        String label() {
            return iface.getName() + " " + addr.getHostAddress();
        }
    }

    private final List<Service> services;
    private final String[] host;
    private volatile boolean running;
    private Thread thread;
    private MulticastSocket socket;
    private final List<Link> links = new ArrayList<>();
    private final Map<String, Long> lastLogged = new HashMap<>();

    MdnsResponder(List<Service> services) {
        this.services = services;
        this.host = new String[] {
                String.format(Locale.US, "7xdash-%06x", new SecureRandom().nextInt(0x1000000)), "local"};
    }

    /** Binds the mDNS port now (throws if the car won't share it), then answers on a thread. */
    void start() throws IOException {
        socket = new MulticastSocket(null);
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(PORT));
            socket.setTimeToLive(255);
            socket.setSoTimeout(250);
        } catch (IOException e) {
            socket.close();
            socket = null;
            throw e;
        }
        AirPlayLog.i("mDNS: own responder listening on " + PORT + " as " + host[0] + ".local");
        running = true;
        thread = new Thread(this::loop, "airplay-mdns");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        running = false;
        Thread t = thread;
        if (t != null) {
            try {
                t.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
    }

    /** True for networks an iPhone could be on; false for the car's internal links. */
    static boolean answersOn(String name) {
        String n = name.toLowerCase(Locale.US);
        return !(n.startsWith("vlan") || n.startsWith("veth") || n.startsWith("dummy")
                || n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("lo")
                || n.startsWith("tun") || n.startsWith("ifb") || n.startsWith("sit")
                || n.startsWith("p2p") || n.startsWith("can"));
    }

    // ---- loop ----

    private void loop() {
        byte[] buf = new byte[9000];
        long nextScan = 0;
        long[] announceAt = {0, 1000, 3000, 7000};
        int announced = 0;
        long started = SystemClock.elapsedRealtime();
        try {
            while (running) {
                long now = SystemClock.elapsedRealtime();
                if (now >= nextScan) {
                    nextScan = now + SCAN_MS;
                    if (scan()) {
                        // a network came or went: announce again on the new set
                        announced = 0;
                        started = now;
                    }
                }
                if (announced < announceAt.length && now - started >= announceAt[announced]) {
                    for (Link l : snapshot()) {
                        send(l, records(l), new ArrayList<>(), false, null, GROUP);
                    }
                    announced++;
                }
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                if (running) {
                    onPacket(packet);
                }
            }
        } catch (Exception e) {
            AirPlayLog.w("mDNS: responder stopped: " + e);
        } finally {
            if (socket != null) {
                for (Link l : snapshot()) {
                    send(l, records(l), new ArrayList<>(), true, null, GROUP);
                }
                socket.close();
            }
        }
    }

    private List<Link> snapshot() {
        synchronized (links) {
            return new ArrayList<>(links);
        }
    }

    /** Join the multicast group on networks that appeared. Returns true when the set changed. */
    private boolean scan() {
        List<Link> found = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast() || !answersOn(ni.getName())) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof Inet4Address) {
                        found.add(new Link(ni, (Inet4Address) ia.getAddress(), ia.getNetworkPrefixLength()));
                    }
                }
            }
        } catch (Exception e) {
            AirPlayLog.w("mDNS: could not list networks: " + e);
            return false;
        }
        List<Link> old = snapshot();
        if (sameSet(old, found)) {
            return false;
        }
        List<String> joined = new ArrayList<>();
        for (Link l : old) {
            if (!containsIface(found, l.iface.getName())) {
                leave(l);
            }
        }
        for (Link l : found) {
            if (!containsIface(old, l.iface.getName()) && !joined.contains(l.iface.getName())) {
                try {
                    socket.joinGroup(GROUP, l.iface);
                    joined.add(l.iface.getName());
                } catch (IOException e) {
                    AirPlayLog.w("mDNS: could not join on " + l.iface.getName() + ": " + e.getMessage());
                }
            }
        }
        synchronized (links) {
            links.clear();
            links.addAll(found);
        }
        StringBuilder sb = new StringBuilder();
        for (Link l : found) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(l.label());
        }
        AirPlayLog.i("mDNS: answering on [" + sb + "]");
        return true;
    }

    private void leave(Link l) {
        try {
            socket.leaveGroup(GROUP, l.iface);
        } catch (IOException ignored) {
            // already gone with the network
        }
    }

    private static boolean containsIface(List<Link> list, String name) {
        for (Link l : list) {
            if (l.iface.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameSet(List<Link> a, List<Link> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).iface.getName().equals(b.get(i).iface.getName())
                    || !a.get(i).addr.equals(b.get(i).addr)) {
                return false;
            }
        }
        return true;
    }

    // ---- questions ----

    private void onPacket(DatagramPacket packet) {
        if (!(packet.getAddress() instanceof Inet4Address) || packet.getLength() < 12) {
            return;
        }
        byte[] d = new byte[packet.getLength()];
        System.arraycopy(packet.getData(), packet.getOffset(), d, 0, d.length);
        if ((rd16(d, 2) & 0x8000) != 0) {
            return; // a response, not a question
        }
        Inet4Address src = (Inet4Address) packet.getAddress();
        Link link = null;
        for (Link l : snapshot()) {
            if (l.covers(src)) {
                link = l;
                break;
            }
        }
        if (link == null || src.equals(link.addr)) {
            return; // not from a network we answer on, or our own packet
        }
        answer(link, new InetSocketAddress(src, packet.getPort()), d);
    }

    private void answer(Link l, InetSocketAddress from, byte[] d) {
        Map<String, Record> answers = new LinkedHashMap<>();
        Map<String, Record> extras = new LinkedHashMap<>();
        int pos = 12;
        int questions = rd16(d, 4);
        for (int q = 0; q < questions; q++) {
            Object[] read = readName(d, pos);
            if (read == null) {
                return;
            }
            @SuppressWarnings("unchecked")
            List<String> name = (List<String>) read[0];
            int next = (Integer) read[1];
            if (next + 4 > d.length) {
                return;
            }
            int type = rd16(d, next);
            pos = next + 4;
            boolean any = type == TYPE_ANY;
            if (eq(name, SERVICES) && (any || type == TYPE_PTR)) {
                for (Service s : services) {
                    add(answers, enumRecord(s));
                }
            }
            for (Service s : services) {
                if (eq(name, typeName(s)) && (any || type == TYPE_PTR)) {
                    add(answers, ptr(s));
                    add(extras, srv(s));
                    add(extras, txt(s));
                    add(extras, a(l));
                }
                if (eq(name, instance(s))) {
                    if (any || type == TYPE_SRV) {
                        add(answers, srv(s));
                        add(extras, a(l));
                    }
                    if (any || type == TYPE_TXT) {
                        add(answers, txt(s));
                    }
                }
            }
            if (eq(name, host) && (any || type == TYPE_A)) {
                add(answers, a(l));
            }
        }
        if (answers.isEmpty()) {
            return;
        }
        for (String k : answers.keySet()) {
            extras.remove(k);
        }
        logAnswer(l, from);
        if (from.getPort() == PORT) {
            send(l, new ArrayList<>(answers.values()), new ArrayList<>(extras.values()), false, null, GROUP);
        } else {
            // legacy unicast question: answer it directly, echoing the question
            byte[] legacy = new byte[pos];
            System.arraycopy(d, 0, legacy, 0, pos);
            send(l, new ArrayList<>(answers.values()), new ArrayList<>(extras.values()), false, legacy, from);
        }
    }

    /** Once a minute per asker, so the log shows who looked us up without filling up. */
    private void logAnswer(Link l, InetSocketAddress from) {
        String key = from.getAddress().getHostAddress();
        long now = SystemClock.elapsedRealtime();
        Long last = lastLogged.get(key);
        if (last == null || now - last > 60_000) {
            lastLogged.put(key, now);
            AirPlayLog.i("mDNS: answered " + key + " on " + l.iface.getName() + " with "
                    + l.addr.getHostAddress());
        }
    }

    // ---- records ----

    private static final class Record {
        final String[] name;
        final int type;
        final boolean unique;
        final int ttl;
        final byte[] rdata;

        Record(String[] name, int type, boolean unique, int ttl, byte[] rdata) {
            this.name = name;
            this.type = type;
            this.unique = unique;
            this.ttl = ttl;
            this.rdata = rdata;
        }

        String key() {
            return String.join(".", name).toLowerCase(Locale.US) + "/" + type + "/"
                    + java.util.Arrays.hashCode(rdata);
        }
    }

    private static void add(Map<String, Record> map, Record r) {
        if (!map.containsKey(r.key())) {
            map.put(r.key(), r);
        }
    }

    private static String[] typeName(Service s) {
        String[] parts = s.type.split("\\.");
        String[] out = new String[parts.length + 1];
        System.arraycopy(parts, 0, out, 0, parts.length);
        out[parts.length] = "local";
        return out;
    }

    private static String[] instance(Service s) {
        String[] t = typeName(s);
        String[] out = new String[t.length + 1];
        out[0] = s.name;
        System.arraycopy(t, 0, out, 1, t.length);
        return out;
    }

    private Record ptr(Service s) {
        return new Record(typeName(s), TYPE_PTR, false, TTL_OTHER, name(instance(s)));
    }

    private Record enumRecord(Service s) {
        return new Record(SERVICES, TYPE_PTR, false, TTL_OTHER, name(typeName(s)));
    }

    private Record srv(Service s) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        u16(b, 0);
        u16(b, 0);
        u16(b, s.port);
        b.write(name(host), 0, name(host).length);
        return new Record(instance(s), TYPE_SRV, true, TTL_HOST, b.toByteArray());
    }

    private Record txt(Service s) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (s.txt.isEmpty()) {
            b.write(0);
        }
        for (Map.Entry<String, String> e : s.txt.entrySet()) {
            label(b, e.getKey() + "=" + e.getValue(), 255);
        }
        return new Record(instance(s), TYPE_TXT, true, TTL_OTHER, b.toByteArray());
    }

    private Record a(Link l) {
        return new Record(host, TYPE_A, true, TTL_HOST, l.addr.getAddress());
    }

    private List<Record> records(Link l) {
        List<Record> out = new ArrayList<>();
        for (Service s : services) {
            out.add(ptr(s));
            out.add(srv(s));
            out.add(txt(s));
            out.add(enumRecord(s));
        }
        out.add(a(l));
        return out;
    }

    // ---- packets ----

    private void send(Link l, List<Record> answers, List<Record> extras, boolean goodbye,
                      byte[] legacy, SocketAddress to) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (legacy != null) {
            b.write(legacy, 0, 2);
            u16(b, RESPONSE_FLAGS);
            b.write(legacy, 4, 2);
        } else {
            u16(b, 0);
            u16(b, RESPONSE_FLAGS);
            u16(b, 0);
        }
        u16(b, answers.size());
        u16(b, 0);
        u16(b, extras.size());
        if (legacy != null) {
            b.write(legacy, 12, legacy.length - 12);
        }
        List<Record> all = new ArrayList<>(answers);
        all.addAll(extras);
        for (Record r : all) {
            byte[] n = name(r.name);
            b.write(n, 0, n.length);
            u16(b, r.type);
            u16(b, r.unique && !goodbye && legacy == null ? CLASS_IN | CACHE_FLUSH : CLASS_IN);
            int ttl = goodbye ? 0 : legacy != null ? Math.min(r.ttl, LEGACY_TTL) : r.ttl;
            u16(b, ttl >>> 16);
            u16(b, ttl & 0xFFFF);
            u16(b, r.rdata.length);
            b.write(r.rdata, 0, r.rdata.length);
        }
        byte[] bytes = b.toByteArray();
        try {
            synchronized (this) {
                socket.setNetworkInterface(l.iface);
                socket.send(new DatagramPacket(bytes, bytes.length, to));
            }
        } catch (IOException e) {
            AirPlayLog.w("mDNS: send on " + l.iface.getName() + " failed: " + e.getMessage());
        }
    }

    // ---- wire helpers ----

    private static int ip(Inet4Address a) {
        byte[] b = a.getAddress();
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }

    private static boolean eq(List<String> a, String[] b) {
        if (a.size() != b.length) {
            return false;
        }
        for (int i = 0; i < b.length; i++) {
            if (!a.get(i).equalsIgnoreCase(b[i])) {
                return false;
            }
        }
        return true;
    }

    static int rd16(byte[] d, int i) {
        return ((d[i] & 0xFF) << 8) | (d[i + 1] & 0xFF);
    }

    private static void u16(ByteArrayOutputStream b, int v) {
        b.write((v >> 8) & 0xFF);
        b.write(v & 0xFF);
    }

    private static void label(ByteArrayOutputStream b, String s, int max) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(bytes.length, max);
        if (n < bytes.length) {
            while (n > 0 && (bytes[n] & 0xC0) == 0x80) {
                n--;
            }
        }
        b.write(n);
        b.write(bytes, 0, n);
    }

    static byte[] name(String[] labels) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (String l : labels) {
            label(b, l, 63);
        }
        b.write(0);
        return b.toByteArray();
    }

    /** {labels, index after the name}, following compression pointers; null when malformed. */
    static Object[] readName(byte[] d, int start) {
        List<String> labels = new ArrayList<>();
        int pos = start;
        int end = -1;
        int jumps = 0;
        while (true) {
            if (pos >= d.length) {
                return null;
            }
            int len = d[pos] & 0xFF;
            if (len == 0) {
                return new Object[] {labels, end < 0 ? pos + 1 : end};
            } else if ((len & 0xC0) == 0xC0) {
                if (pos + 1 >= d.length || ++jumps > 16) {
                    return null;
                }
                if (end < 0) {
                    end = pos + 2;
                }
                pos = ((len & 0x3F) << 8) | (d[pos + 1] & 0xFF);
            } else {
                if (pos + 1 + len > d.length) {
                    return null;
                }
                labels.add(new String(d, pos + 1, len, StandardCharsets.UTF_8));
                pos += 1 + len;
            }
        }
    }
}
