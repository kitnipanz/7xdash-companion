package com.kooo.evcam.input;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

/**
 * One mono 16-bit track per picked speaker, all fed the same samples. Each track is set up the way
 * the megaphone's always was (media, sonification, preferred device), the route the car is known to
 * honour for the outside bus.
 *
 * <p>The first track's write blocks and paces the caller; the others write without blocking and drop
 * what does not fit, so one speaker that stops taking audio cannot hold the rest up.</p>
 */
public final class SpeakerSet {

    private final List<AudioTrack> tracks = new ArrayList<>();
    private final StringBuilder report = new StringBuilder();

    private SpeakerSet() {
    }

    /** Output devices whose address was picked, in the order picked. Missing ones are left out. */
    public static List<AudioDeviceInfo> find(AudioManager manager, List<String> addresses) {
        List<AudioDeviceInfo> out = new ArrayList<>();
        if (manager == null || addresses == null) {
            return out;
        }
        AudioDeviceInfo[] devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        for (String address : addresses) {
            AudioDeviceInfo device = Megaphone.find(devices, address);
            if (device != null && !out.contains(device)) {
                out.add(device);
            }
        }
        return out;
    }

    /**
     * Opens and starts a track on every device that takes one. {@link #size()} is 0 when none did;
     * {@link #describe()} says what happened to each.
     */
    public static SpeakerSet open(List<AudioDeviceInfo> devices, int rate, int bufferBytes) {
        SpeakerSet set = new SpeakerSet();
        int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        for (AudioDeviceInfo device : devices) {
            if (set.report.length() > 0) {
                set.report.append(", ");
            }
            set.report.append(device.getAddress()).append(' ');
            if (min <= 0) {
                set.report.append("no track at ").append(rate).append(" Hz");
                continue;
            }
            AudioTrack track = build(rate, Math.max(min, bufferBytes));
            if (track == null) {
                set.report.append("track failed");
                continue;
            }
            if (!track.setPreferredDevice(device)) {
                track.release();
                set.report.append("refused");
                continue;
            }
            track.play();
            set.tracks.add(track);
            AudioDeviceInfo routed = track.getRoutedDevice();
            set.report.append("routed to ").append(routed == null ? "?" : routed.getAddress());
        }
        return set;
    }

    private static AudioTrack build(int rate, int bufferBytes) {
        AudioAttributes.Builder attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Keep it out of any playback capture
            attributes.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE);
        }
        try {
            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(attributes.build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(rate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferBytes)
                    .build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release();
                return null;
            }
            return track;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public int size() {
        return tracks.size();
    }

    /** Per speaker: its address and where the car routed it, or why it got no track. */
    public String describe() {
        return report.toString();
    }

    public void setVolume(float gain) {
        for (AudioTrack track : tracks) {
            track.setVolume(gain);
        }
    }

    /** False when the pacing track failed; the caller stops. */
    public boolean write(byte[] pcm, int length) {
        for (int i = 0; i < tracks.size(); i++) {
            int wrote = tracks.get(i).write(pcm, 0, length,
                    i == 0 ? AudioTrack.WRITE_BLOCKING : AudioTrack.WRITE_NON_BLOCKING);
            if (i == 0 && wrote < 0) {
                return false;
            }
        }
        return !tracks.isEmpty();
    }

    /** False when the pacing track failed; the caller stops. */
    public boolean write(short[] pcm, int frames) {
        for (int i = 0; i < tracks.size(); i++) {
            int wrote = tracks.get(i).write(pcm, 0, frames,
                    i == 0 ? AudioTrack.WRITE_BLOCKING : AudioTrack.WRITE_NON_BLOCKING);
            if (i == 0 && wrote < 0) {
                return false;
            }
        }
        return !tracks.isEmpty();
    }

    public void release() {
        for (AudioTrack track : tracks) {
            try {
                track.pause();
                track.flush();
            } catch (IllegalStateException ignored) {
                // not playing yet
            }
            track.release();
        }
        tracks.clear();
    }
}
