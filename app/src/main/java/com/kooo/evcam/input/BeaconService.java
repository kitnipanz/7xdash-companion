package com.kooo.evcam.input;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Hosts {@link BeaconEngine} in its own process (":ble"), so a scan stuck in this head unit's
 * Bluetooth stack can be cleared by killing this process alone, without touching recording.
 * {@link StickerBeacon} in the app's process binds here and gets every event back.
 */
public class BeaconService extends Service {

    // App -> here
    static final int MSG_CONFIG = 1;
    static final int MSG_RESTART = 2;
    // Here -> app
    static final int MSG_TRACE = 10;
    static final int MSG_HEARD = 11;
    static final int MSG_PRESS = 12;
    static final int MSG_STATUS = 13;

    static final String KEY_ADDRESSES = "addresses";
    static final String KEY_ALLOWED = "allowed";
    static final String KEY_ANY = "any";
    static final String KEY_REBASELINE = "rebaseline";
    static final String KEY_LINE = "line";
    static final String KEY_MAC = "mac";
    static final String KEY_FRAME = "frame";

    /** Lets the last messages reach the app before this process goes. */
    private static final long KILL_DELAY_MS = 300L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Messenger client;

    private final Messenger inbox = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            if (msg.replyTo != null) {
                boolean first = client == null;
                client = msg.replyTo;
                if (first) {
                    send(MSG_TRACE, line("BLE process up pid=" + Process.myPid()));
                }
            }
            if (msg.what == MSG_CONFIG) {
                Bundle b = msg.getData();
                ArrayList<String> list = b.getStringArrayList(KEY_ADDRESSES);
                BeaconEngine.sync(BeaconService.this, new HashSet<>(list == null ? new ArrayList<>() : list),
                        b.getBoolean(KEY_ALLOWED), b.getBoolean(KEY_ANY), b.getBoolean(KEY_REBASELINE));
            } else if (msg.what == MSG_RESTART) {
                send(MSG_TRACE, line("restart BLE process (" + msg.getData().getString(KEY_LINE, "asked") + ")"));
                die();
            }
        }
    });

    @Override
    public void onCreate() {
        super.onCreate();
        BeaconEngine.attach(new BeaconEngine.Out() {
            @Override
            public void trace(String text) {
                send(MSG_TRACE, line(text));
            }

            @Override
            public void heard(String mac, boolean heard) {
                Bundle b = new Bundle();
                b.putString(KEY_MAC, mac);
                Message m = Message.obtain(null, MSG_HEARD, heard ? 1 : 0, 0);
                m.setData(b);
                send(m);
            }

            @Override
            public void press(String mac, byte[] frame) {
                Bundle b = new Bundle();
                b.putString(KEY_MAC, mac);
                b.putByteArray(KEY_FRAME, frame);
                Message m = Message.obtain(null, MSG_PRESS);
                m.setData(b);
                send(m);
            }

            @Override
            public void status(int status) {
                send(Message.obtain(null, MSG_STATUS, status, 0));
            }

            @Override
            public void giveUp(String why) {
                die();
            }
        });
    }

    @Override
    public IBinder onBind(Intent intent) {
        return inbox.getBinder();
    }

    /** Save the press counters, then end this process; the app binds a fresh one. */
    private void die() {
        BeaconEngine.flushCounts();
        main.postDelayed(() -> Process.killProcess(Process.myPid()), KILL_DELAY_MS);
    }

    private static Bundle line(String text) {
        Bundle b = new Bundle();
        b.putString(KEY_LINE, text);
        return b;
    }

    private void send(int what, Bundle data) {
        Message m = Message.obtain(null, what);
        m.setData(data);
        send(m);
    }

    private void send(Message m) {
        Messenger to = client;
        if (to == null) {
            return;
        }
        try {
            to.send(m);
        } catch (RemoteException e) {
            client = null;
        }
    }
}
