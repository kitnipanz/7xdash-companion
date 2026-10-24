package com.kooo.evcam.audio;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.remote.CarLink;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 7xDash web side of the outside speaker: reports on/off and both volumes over the Car Link,
 * lists the speakers to pick from, and takes {@code {"t":"speaker","on":…,"volume":…,"megaphone":…,
 * "speakers":[addresses]}} back. "beta" stays true:
 * the web card from 1.1.6 disables itself without it, and the speaker is no longer a beta.
 */
public final class SpeakerRemote implements CarLink.Speaker {

    private final Context context;
    private final AppConfig config;
    private final CarLink link;
    private final Runnable changed;

    public SpeakerRemote(Context context, CarLink link) {
        this.context = context.getApplicationContext();
        this.config = new AppConfig(this.context);
        this.link = link;
        this.changed = link::sendSpeaker;
    }

    public void attach() {
        OutsideSpeaker.addListener(changed);
        link.setSpeaker(this);
    }

    public void detach() {
        OutsideSpeaker.removeListener(changed);
        link.setSpeaker(null);
    }

    @Override
    public JSONObject status() {
        JSONObject msg = new JSONObject();
        try {
            msg.put("beta", true);
            msg.put("supported", OutsideSpeaker.isSupported());
            msg.put("on", OutsideSpeaker.isOn());
            msg.put("asking", false);
            msg.put("volume", config.getOutsideSpeakerVolume());
            msg.put("megaphone", config.getSpeakVolume());
            msg.put("speakers", speakers());
        } catch (JSONException ignored) {
            // keys and values are plain
        }
        return msg;
    }

    @Override
    public void command(JSONObject msg) {
        if (msg == null) {
            return;
        }
        if (msg.has("volume")) {
            OutsideSpeaker.setVolume(context, msg.optInt("volume", config.getOutsideSpeakerVolume()));
        }
        if (msg.has("megaphone")) {
            config.setSpeakVolume(msg.optInt("megaphone", config.getSpeakVolume()));
        }
        JSONArray picked = msg.optJSONArray("speakers");
        if (picked != null) {
            List<String> addresses = new ArrayList<>();
            for (int i = 0; i < picked.length(); i++) {
                String address = picked.optString(i, "").trim();
                if (!address.isEmpty() && !addresses.contains(address)) {
                    addresses.add(address);
                }
            }
            // Never none: the web cannot untick the last one, and an empty list is ignored here too
            if (!addresses.isEmpty()) {
                config.setSpeakSpeakers(addresses);
                OutsideSpeaker.speakersChanged(context);
            }
        }
        if (msg.has("on")) {
            boolean want = msg.optBoolean("on");
            if (!want) {
                OutsideSpeaker.stop(context);
            } else if (!OutsideSpeaker.isOn()) {
                OutsideSpeaker.start(context);
            }
        }
        link.sendSpeaker();
    }

    /**
     * Every output the head unit lists with an address, plus picked ones it does not list right now:
     * {@code [{"address":…,"name":…,"on":true|false}]}, picked first in the order picked.
     */
    private JSONArray speakers() throws JSONException {
        List<String> picked = config.getSpeakSpeakers();
        List<String> addresses = new ArrayList<>(picked);
        Map<String, String> names = new HashMap<>();
        AudioManager manager = context.getSystemService(AudioManager.class);
        AudioDeviceInfo[] devices = manager == null ? null : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        if (devices != null) {
            for (AudioDeviceInfo device : devices) {
                String address = device == null || !device.isSink() ? null : device.getAddress();
                if (address == null || address.isEmpty()) {
                    continue;
                }
                CharSequence product = device.getProductName();
                if (product != null && product.length() > 0 && !names.containsKey(address)) {
                    names.put(address, product.toString());
                }
                if (!addresses.contains(address)) {
                    addresses.add(address);
                }
            }
        }
        JSONArray out = new JSONArray();
        for (String address : addresses) {
            JSONObject row = new JSONObject();
            row.put("address", address);
            row.put("name", names.containsKey(address) ? names.get(address) : "");
            row.put("on", picked.contains(address));
            out.put(row);
        }
        return out;
    }
}
