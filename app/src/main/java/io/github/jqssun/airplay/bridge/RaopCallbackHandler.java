package io.github.jqssun.airplay.bridge;

/**
 * What libairplay_native calls back, from its own threads. Method names and signatures are
 * looked up by name in android_raop_callbacks.c and log_sink.h, so keep them exactly.
 */
public interface RaopCallbackHandler {

    void onVideoData(byte[] data, long ntpTimeNs, boolean isH265);

    void onAudioFormat(int ct, int spf, boolean usingScreen);

    void onVideoSize(float srcW, float srcH, float w, float h);

    void onVolumeChange(float volume);

    float onClientVolume();

    void onAudioTeardown();

    void onConnectionInit();

    void onConnectionDestroy();

    void onConnectionReset(int reason);

    void onDisplayPin(String pin);

    void onMetadata(byte[] data);

    void onCoverArt(byte[] data);

    void onProgress(long start, long curr, long end);

    void onDacpId(String dacpId, String activeRemote);

    void onMirrorRunning(boolean running);

    void onVideoPlay(String location, float startPositionSeconds);

    void onVideoScrub(float positionSeconds);

    void onVideoRate(float rate);

    void onVideoStop();

    /** 7xDash: the track picked on the iPhone; type "soun" or "sbtl", language like "th". */
    void onVideoSelectMedia(String mediaType, String language);

    void onVideoSessionPoll();

    void onLog(String message);
}
