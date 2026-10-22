package com.kooo.evcam.audio;

import android.content.Context;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.remote.CarLink;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 7xDash web side of the outside speaker: reports on/off and both volumes over the Car Link,
 * and takes {@code {"t":"speaker","on":…,"volume":…,"megaphone":…}} back. Turning the
 * speaker on still needs Start now on the car screen; turning it on is refused while beta
 * features are off.
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
        OutsideSpeakerService.addListener(changed);
        link.setSpeaker(this);
    }

    public void detach() {
        OutsideSpeakerService.removeListener(changed);
        link.setSpeaker(null);
    }

    @Override
    public JSONObject status() {
        JSONObject msg = new JSONObject();
        try {
            msg.put("beta", config.isBetaFeaturesEnabled());
            msg.put("supported", OutsideSpeakerService.isSupported());
            msg.put("on", OutsideSpeakerService.isOn());
            msg.put("asking", OutsideSpeakerService.isAsking());
            msg.put("volume", config.getOutsideSpeakerVolume());
            msg.put("megaphone", config.getSpeakVolume());
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
            OutsideSpeakerService.setVolume(context, msg.optInt("volume", config.getOutsideSpeakerVolume()));
        }
        if (msg.has("megaphone")) {
            config.setSpeakVolume(msg.optInt("megaphone", config.getSpeakVolume()));
        }
        if (msg.has("on")) {
            boolean want = msg.optBoolean("on");
            if (!want) {
                OutsideSpeakerService.stop(context);
            } else if (config.isBetaFeaturesEnabled()
                    && !OutsideSpeakerService.isOn() && !OutsideSpeakerService.isAsking()) {
                OutsideSpeakerService.start(context);
            }
        }
        link.sendSpeaker();
    }
}
