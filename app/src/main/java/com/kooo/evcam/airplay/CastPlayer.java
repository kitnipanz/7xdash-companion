package com.kooo.evcam.airplay;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

/**
 * Plays a video the iPhone casts (the AirPlay button inside an app, not screen mirroring): the
 * iPhone hands over a stream address and we play it with ExoPlayer in the floating window.
 * Four times a second the position, length and play state go back to the iPhone, which polls
 * them to drive its own player controls.
 *
 * <p>Ported from jqssun/android-airplay-server's AirPlayVideoPlayer (GPL-3.0). Main thread only;
 * the methods post there themselves.</p>
 */
final class CastPlayer {

    interface Listener {
        /** Seconds; duration 0 for live, -1 once stopped ("video finished" for the iPhone). */
        void onInfo(float position, float duration, float rate, boolean ready);

        void onVideoSize(int width, int height);

        void onFirstFrame();

        void onEnded(String why);
    }

    private static final long REPORT_MS = 250;

    private final Context context;
    private final Listener listener;
    /** Where the video is downloaded from: the car's Wi-Fi when it has one. */
    final WifiRoute route;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExoPlayer player;
    private Surface surface;
    /** The audio language picked on the iPhone; null lets the stream's own default play. */
    private String audioLanguage;

    private final Runnable report = new Runnable() {
        @Override
        public void run() {
            reportNow();
            main.postDelayed(this, REPORT_MS);
        }
    };

    private final Player.Listener events = new Player.Listener() {
        @Override
        public void onPlayerError(PlaybackException error) {
            listener.onEnded("player error: " + error.getErrorCodeName() + " " + error.getMessage());
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_ENDED) {
                listener.onEnded("video ended");
            }
        }

        @Override
        public void onTimelineChanged(Timeline timeline, int reason) {
            // the length is usually known from here on (HLS): report it so the iPhone's /play goes on
            reportNow();
        }

        @Override
        public void onVideoSizeChanged(VideoSize size) {
            if (size.height > 0) {
                listener.onVideoSize(Math.round(size.width * size.pixelWidthHeightRatio), size.height);
            }
        }

        @Override
        public void onRenderedFirstFrame() {
            listener.onFirstFrame();
        }
    };

    CastPlayer(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.route = new WifiRoute(this.context);
    }

    boolean isPlaying() {
        return player != null;
    }

    void play(String location, float startSeconds) {
        main.post(() -> {
            // a new video replaces the old one without telling the iPhone "stopped"
            release(false);
            AirPlayLog.i("cast: downloading over " + route.describe());
            ExoPlayer p = new ExoPlayer.Builder(context)
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(route.dataSourceFactory()))
                    .build();
            p.addListener(events);
            applyLanguage(p);
            if (surface != null) {
                p.setVideoSurface(surface);
            }
            player = p;
            p.setMediaItem(MediaItem.fromUri(location), (long) (startSeconds * 1000));
            p.setPlayWhenReady(true);
            p.prepare();
            listener.onInfo(startSeconds, 0f, 0f, false);
            main.postDelayed(report, REPORT_MS);
        });
    }

    void seek(float seconds) {
        main.post(() -> {
            if (player != null) {
                player.seekTo((long) (seconds * 1000));
            }
        });
    }

    /** 0 pauses; anything else plays at that speed. */
    void setRate(float rate) {
        main.post(() -> {
            if (player == null) {
                return;
            }
            if (rate <= 0f) {
                player.setPlayWhenReady(false);
            } else {
                player.setPlaybackParameters(new PlaybackParameters(Math.max(0.1f, rate)));
                player.setPlayWhenReady(true);
            }
        });
    }

    /** Play this audio language (from the iPhone's track choice), now and for the next video. */
    void setAudioLanguage(String language) {
        main.post(() -> {
            audioLanguage = language;
            if (player != null) {
                applyLanguage(player);
            }
        });
    }

    private void applyLanguage(ExoPlayer p) {
        if (audioLanguage == null) {
            return;
        }
        p.setTrackSelectionParameters(p.getTrackSelectionParameters().buildUpon()
                .setPreferredAudioLanguage(audioLanguage)
                .build());
    }

    void setSurface(Surface s) {
        main.post(() -> {
            surface = s;
            if (player != null) {
                player.setVideoSurface(s);
            }
        });
    }

    void stop() {
        main.post(() -> release(true));
    }

    private void release(boolean reportStopped) {
        main.removeCallbacks(report);
        if (player != null) {
            player.removeListener(events);
            player.release();
            player = null;
        }
        if (reportStopped) {
            listener.onInfo(0f, -1f, 0f, false);
        }
    }

    private void reportNow() {
        ExoPlayer p = player;
        if (p == null) {
            return;
        }
        long durationMs = p.getDuration();
        float duration = durationMs == C.TIME_UNSET ? 0f : durationMs / 1000f;
        boolean playing = p.getPlayWhenReady() && p.getPlaybackState() == Player.STATE_READY;
        float rate = playing ? p.getPlaybackParameters().speed : 0f;
        // "ready" = the timeline is known (the iPhone holds its controls until then)
        boolean ready = p.isCurrentMediaItemLive()
                ? p.getPlaybackState() == Player.STATE_READY
                : durationMs != C.TIME_UNSET;
        listener.onInfo(p.getCurrentPosition() / 1000f, duration, rate, ready);
    }
}
