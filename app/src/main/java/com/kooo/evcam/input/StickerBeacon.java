package com.kooo.evcam.input;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The app's side of the broadcast scan. The scan itself ({@link BeaconEngine}) runs in the
 * ":ble" process ({@link BeaconService}); this binds to it, hands it what to listen for, and
 * turns what comes back into presses and status for {@link StickerHub}.
 *
 * <p>When that process gives up (scans keep failing) or dies, it is bound again after a short
 * wait, longer each time it fails again without hearing anything. Main thread unless noted.</p>
 */
final class StickerBeacon {

    /** First rebind after the BLE process went; each failed one waits 4 times longer. */
    private static final long REBIND_MS = 1_000L;
    private static final long REBIND_MAX_MS = 5L * 60 * 1000;
    /** A tap and the auto heal can ask together; one fresh process is enough. */
    private static final long RESTART_DEBOUNCE_MS = 5_000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<String> HEARD = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static Context app;
    private static Messenger service;
    private static boolean bound;
    /** Restarts in a row that heard nothing; sets the rebind wait. */
    private static int restarts;
    private static long lastRestartAsk;

    private static Set<String> wanted = new HashSet<>();
    private static boolean allowed;
    private static boolean anyButton;
    private static boolean rebaseline;
    private static boolean wantOn;
    private static int engineStatus = BeaconEngine.OFF;
    private static int lastStatus = -1;

    private StickerBeacon() {
    }

    /**
     * Run the scan for exactly these buttons, or stop it when {@code allowed} is false.
     * Safe to call often from any thread; only a change is sent on.
     */
    static void sync(Context context, Set<String> addresses, boolean allowedNow, boolean any,
                     boolean screenHasRadio) {
        Context application = context == null ? null : context.getApplicationContext();
        Set<String> next = new HashSet<>(addresses == null ? new HashSet<>() : addresses);
        MAIN.post(() -> {
            if (application != null) {
                app = application;
            }
            boolean changed = !next.equals(wanted) || allowedNow != allowed || any != anyButton
                    || (screenHasRadio && !rebaseline);
            wanted = next;
            allowed = allowedNow;
            anyButton = any;
            rebaseline |= screenHasRadio;
            wantOn = allowedNow && (!next.isEmpty() || any);
            if (!bound) {
                bind();
            } else if (changed) {
                sendConfig();
            }
            notifyStatus();
        });
    }

    /** True while the scan is running (presses come from here, not from GATT). Main thread. */
    static boolean isScanning() {
        return status() == StickerHub.BEACON_LISTENING;
    }

    /** This button was heard on air recently, so it is reachable. Any thread. */
    static boolean isHeard(String mac) {
        return HEARD.contains(mac);
    }

    /** Listening, not listening, or failed and retrying. Main thread. */
    static int status() {
        if (service == null) {
            return wantOn ? StickerHub.BEACON_RETRYING : StickerHub.BEACON_OFF;
        }
        return engineStatus;
    }

    /**
     * Start over in a fresh BLE process (tapped BLE, or the hub's auto heal). Restarting the
     * scan inside the old one never helped once it was stuck (car log 2026-10-17).
     */
    static void forceRestart() {
        MAIN.post(() -> {
            long now = SystemClock.elapsedRealtime();
            if (lastRestartAsk != 0 && now - lastRestartAsk < RESTART_DEBOUNCE_MS) {
                return;
            }
            lastRestartAsk = now;
            restarts = 0;
            Messenger to = service;
            if (to != null) {
                Bundle b = new Bundle();
                b.putString(BeaconService.KEY_LINE, "asked by the app");
                Message m = Message.obtain(null, BeaconService.MSG_RESTART);
                m.setData(b);
                try {
                    to.send(m);
                    return;
                } catch (RemoteException e) {
                    // already gone: bind a new one below
                }
            }
            unbind();
            bind();
        });
    }

    static String failName(int code) {
        return BeaconEngine.failName(code);
    }

    private static void bind() {
        if (app == null || bound) {
            return;
        }
        MAIN.removeCallbacks(REBIND);
        try {
            bound = app.bindService(new Intent(app, BeaconService.class), CONNECTION, Context.BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            bound = false;
        }
        if (!bound) {
            StickerHub.trace("BLE process bind refused");
            later();
        }
    }

    private static void unbind() {
        if (bound && app != null) {
            try {
                app.unbindService(CONNECTION);
            } catch (IllegalArgumentException ignored) {
                // not bound after all
            }
        }
        bound = false;
        service = null;
    }

    private static final Runnable REBIND = StickerBeacon::bind;

    private static void later() {
        long wait = Math.min(REBIND_MAX_MS, REBIND_MS << Math.min(16, 2 * restarts));
        restarts++;
        StickerHub.trace("BLE process gone, new one in " + wait / 1000 + "s (#" + restarts + ")");
        MAIN.removeCallbacks(REBIND);
        MAIN.postDelayed(REBIND, wait);
    }

    /** Its process went (gave up, crashed or was killed): forget it and bind a fresh one. */
    private static void lost() {
        unbind();
        engineStatus = BeaconEngine.OFF;
        for (String mac : new ArrayList<>(HEARD)) {
            HEARD.remove(mac);
            StickerHub.beaconState(mac, false);
        }
        later();
        notifyStatus();
    }

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = new Messenger(binder);
            sendConfig();
            notifyStatus();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            lost();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            if (bound) {
                lost();
            }
        }
    };

    private static void sendConfig() {
        Messenger to = service;
        if (to == null) {
            return;
        }
        Bundle b = new Bundle();
        b.putStringArrayList(BeaconService.KEY_ADDRESSES, new ArrayList<>(wanted));
        b.putBoolean(BeaconService.KEY_ALLOWED, allowed);
        b.putBoolean(BeaconService.KEY_ANY, anyButton);
        b.putBoolean(BeaconService.KEY_REBASELINE, rebaseline);
        Message m = Message.obtain(null, BeaconService.MSG_CONFIG);
        m.setData(b);
        m.replyTo = INBOX;
        try {
            to.send(m);
            rebaseline = false;
        } catch (RemoteException e) {
            lost();
        }
    }

    private static final Messenger INBOX = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            Bundle b = msg.getData();
            switch (msg.what) {
                case BeaconService.MSG_TRACE:
                    StickerHub.trace(b.getString(BeaconService.KEY_LINE, ""));
                    break;
                case BeaconService.MSG_HEARD: {
                    String mac = b.getString(BeaconService.KEY_MAC);
                    if (mac == null) {
                        break;
                    }
                    boolean heard = msg.arg1 == 1;
                    if (heard) {
                        restarts = 0;
                        HEARD.add(mac);
                    } else {
                        HEARD.remove(mac);
                    }
                    StickerHub.beaconState(mac, heard);
                    break;
                }
                case BeaconService.MSG_PRESS: {
                    String mac = b.getString(BeaconService.KEY_MAC);
                    byte[] frame = b.getByteArray(BeaconService.KEY_FRAME);
                    if (mac != null && frame != null) {
                        StickerHub.beaconPress(mac, frame);
                    }
                    break;
                }
                case BeaconService.MSG_STATUS:
                    engineStatus = msg.arg1;
                    notifyStatus();
                    break;
                default:
                    break;
            }
        }
    });

    /** Tell the float when listening starts, stops or starts failing. */
    private static void notifyStatus() {
        int now = status();
        if (now != lastStatus) {
            lastStatus = now;
            StickerHub.beaconStatusChanged();
        }
    }
}
