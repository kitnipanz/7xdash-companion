package com.kooo.evcam.drm;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaDrm;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager;
import androidx.media3.exoplayer.drm.ExoMediaDrm;
import androidx.media3.exoplayer.drm.FrameworkMediaDrm;
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback;
import androidx.media3.exoplayer.drm.MediaDrmCallback;
import androidx.media3.exoplayer.drm.MediaDrmCallbackException;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import com.google.android.material.button.MaterialButton;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.airplay.DrivingGate;
import com.kooo.evcam.browser.BrowserPrefs;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * DRM lab: how Widevine works, on this head unit, with Google's public test streams and test
 * license server (the ones the ExoPlayer and Shaka demo apps use). It shows what the car's Widevine
 * is (security level L1 or L3, HDCP, version, secure decoders) and, while a test video plays,
 * each step: the license request, the server's answer, the keys loaded, the decoder picked and
 * the resolution allowed. No real service's content or account is involved.
 *
 * <p>The picture hides while driving unless "show the picture while driving" is on, like the
 * browser.</p>
 */
@OptIn(markerClass = UnstableApi.class)
public class DrmLabActivity extends AppCompatActivity {

    private static final String TAG = "DrmLab";
    private static final String TELEMETRY_USER = "drm-lab";
    private static final String LICENSE =
            "https://proxy.uat.widevine.com/proxy?video_id=2015_tears&provider=widevine_test";
    /** Name, manifest, license server (null: not protected). Google's public Widevine test content. */
    private static final String[][] STREAMS = {
            {"Clear (no DRM)", "https://storage.googleapis.com/wvmedia/clear/h264/tears/tears.mpd", null},
            {"Widevine SD", "https://storage.googleapis.com/wvmedia/cenc/h264/tears/tears_sd.mpd", LICENSE},
            {"Widevine HD", "https://storage.googleapis.com/wvmedia/cenc/h264/tears/tears_hd.mpd", LICENSE},
            {"Widevine UHD", "https://storage.googleapis.com/wvmedia/cenc/h264/tears/tears_uhd.mpd", LICENSE},
    };

    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private final StringBuilder lines = new StringBuilder();
    private TextView log;
    private ScrollView logScroll;
    private SurfaceView video;
    private TextView cover;
    private ExoPlayer player;
    private int dp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = Math.round(getResources().getDisplayMetrics().density);
        setContentView(buildLayout());
        line("DRM lab. Google's Widevine test streams and test license server only.");
        deviceInfo();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Telemetry.get().acquire(getApplicationContext(), TELEMETRY_USER);
        Telemetry.get().addListener(carListener);
        checkCar();
    }

    @Override
    protected void onStop() {
        Telemetry.get().removeListener(carListener);
        Telemetry.get().release(TELEMETRY_USER);
        if (player != null) {
            player.pause();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        releasePlayer();
        super.onDestroy();
    }

    // ================================================================= device

    /** What this head unit's Widevine is, and which decoders can play protected video. */
    private void deviceInfo() {
        line("— Device —");
        UUID wv = C.WIDEVINE_UUID;
        line("Widevine supported: " + MediaDrm.isCryptoSchemeSupported(wv));
        MediaDrm drm = null;
        try {
            drm = new MediaDrm(wv);
            for (String p : new String[]{"vendor", "version", "description", "securityLevel", "systemId",
                    "hdcpLevel", "maxHdcpLevel", "maxNumberOfSessions", "oemCryptoApiVersion", "algorithms"}) {
                try {
                    line("  " + p + ": " + drm.getPropertyString(p));
                } catch (RuntimeException e) {
                    line("  " + p + ": (not reported)");
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                line("  connected HDCP: " + hdcp(drm.getConnectedHdcpLevel()) + " · max HDCP: " + hdcp(drm.getMaxHdcpLevel()));
                line("  max security level: " + security(MediaDrm.getMaxSecurityLevel()));
            }
        } catch (Exception e) {
            line("Widevine: cannot open (" + e + ")");
        } finally {
            if (drm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    drm.close();
                } else {
                    drm.release();
                }
            }
        }
        line("Secure video decoders (needed for L1 / HD):");
        int found = 0;
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
            if (info.isEncoder()) {
                continue;
            }
            for (String type : info.getSupportedTypes()) {
                if (!type.startsWith("video/")) {
                    continue;
                }
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                if (info.getName().endsWith(".secure")
                        || caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback)) {
                    String size = caps.getVideoCapabilities() == null ? ""
                            : " up to " + caps.getVideoCapabilities().getSupportedWidths().getUpper() + "×"
                            + caps.getVideoCapabilities().getSupportedHeights().getUpper();
                    line("  " + info.getName() + " · " + type + size);
                    found++;
                }
            }
        }
        if (found == 0) {
            line("  none: protected video is decoded in software (L3, usually SD only)");
        }
    }

    private static String security(int level) {
        switch (level) {
            case MediaDrm.SECURITY_LEVEL_SW_SECURE_CRYPTO:
                return "L3 (software crypto)";
            case MediaDrm.SECURITY_LEVEL_SW_SECURE_DECODE:
                return "L3 (software decode)";
            case MediaDrm.SECURITY_LEVEL_HW_SECURE_CRYPTO:
                return "L2 (hardware crypto)";
            case MediaDrm.SECURITY_LEVEL_HW_SECURE_DECODE:
                return "L1 (hardware decode)";
            case MediaDrm.SECURITY_LEVEL_HW_SECURE_ALL:
                return "L1 (hardware, all)";
            case 6: // MediaDrm.SECURITY_LEVEL_MAX (hidden)
                return "not reported (the device only says \"its highest\")";
            default:
                return "unknown (" + level + ")";
        }
    }

    private static String hdcp(int level) {
        switch (level) {
            case MediaDrm.HDCP_NONE:
                return "none";
            case MediaDrm.HDCP_V1:
                return "1.x";
            case MediaDrm.HDCP_V2:
                return "2.0";
            case MediaDrm.HDCP_V2_1:
                return "2.1";
            case MediaDrm.HDCP_V2_2:
                return "2.2";
            case MediaDrm.HDCP_V2_3:
                return "2.3";
            case MediaDrm.HDCP_NO_DIGITAL_OUTPUT:
                return "no digital output";
            default:
                return "unknown (" + level + ")";
        }
    }

    // ================================================================= playing

    private void play(String name, String manifest, String license) {
        releasePlayer();
        line("— " + name + " —");
        line("Manifest: " + manifest);
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory().setUserAgent("7xDash DRM lab");
        DefaultMediaSourceFactory sources = new DefaultMediaSourceFactory(this).setDataSourceFactory(http);
        MediaItem.Builder item = new MediaItem.Builder().setUri(manifest).setMimeType(MimeTypes.APPLICATION_MPD);
        if (license != null) {
            line("License server: " + license);
            item.setDrmConfiguration(new MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseUri(license).build());
            DefaultDrmSessionManager drm = new DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .build(new LoggingCallback(new HttpMediaDrmCallback(license, http)));
            sources.setDrmSessionManagerProvider(mediaItem -> drm);
        }
        player = new ExoPlayer.Builder(this).setMediaSourceFactory(sources).build();
        player.setVideoSurfaceView(video);
        player.addAnalyticsListener(analytics);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                line("Player: " + (state == Player.STATE_BUFFERING ? "buffering" : state == Player.STATE_READY ? "playing"
                        : state == Player.STATE_ENDED ? "ended" : "idle"));
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException e) {
                Throwable cause = e.getCause();
                line("ERROR " + e.getErrorCodeName() + (cause == null ? "" : ": " + cause));
            }

            @Override
            public void onVideoSizeChanged(@NonNull androidx.media3.common.VideoSize size) {
                fitVideo(size.width * size.pixelWidthHeightRatio, size.height);
            }
        });
        player.setMediaItem(item.build());
        player.prepare();
        player.play();
        checkCar();
    }

    /** Sizes the picture to the video's shape, centred in its box, so it is not stretched. */
    private void fitVideo(float w, float h) {
        View box = (View) video.getParent();
        if (w <= 0 || h <= 0 || box.getWidth() == 0 || box.getHeight() == 0) {
            return;
        }
        float scale = Math.min(box.getWidth() / w, box.getHeight() / h);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                Math.round(w * scale), Math.round(h * scale), android.view.Gravity.CENTER);
        video.setLayoutParams(p);
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    /** Wraps the HTTP license call so each request and answer shows in the log. */
    private final class LoggingCallback implements MediaDrmCallback {
        private final MediaDrmCallback inner;

        LoggingCallback(MediaDrmCallback inner) {
            this.inner = inner;
        }

        @NonNull
        @Override
        public byte[] executeProvisionRequest(@NonNull UUID uuid, @NonNull ExoMediaDrm.ProvisionRequest request)
                throws MediaDrmCallbackException {
            post("Provisioning request → " + request.getDefaultUrl() + " (" + request.getData().length + " bytes)");
            long t = SystemClock.elapsedRealtime();
            byte[] r = inner.executeProvisionRequest(uuid, request);
            post("Provisioning answer ← " + r.length + " bytes in " + (SystemClock.elapsedRealtime() - t) + " ms");
            return r;
        }

        @NonNull
        @Override
        public byte[] executeKeyRequest(@NonNull UUID uuid, @NonNull ExoMediaDrm.KeyRequest request)
                throws MediaDrmCallbackException {
            post("License request → " + request.getData().length + " bytes (a signed challenge from this device's Widevine)");
            long t = SystemClock.elapsedRealtime();
            try {
                byte[] r = inner.executeKeyRequest(uuid, request);
                post("License answer ← " + r.length + " bytes in " + (SystemClock.elapsedRealtime() - t)
                        + " ms (the content keys, encrypted for this device)");
                return r;
            } catch (MediaDrmCallbackException e) {
                post("License refused: " + e.getCause());
                throw e;
            }
        }
    }

    private final AnalyticsListener analytics = new AnalyticsListener() {
        @Override
        public void onDrmSessionAcquired(@NonNull EventTime t, int state) {
            line("DRM session opened");
        }

        @Override
        public void onDrmKeysLoaded(@NonNull EventTime t) {
            line("Keys loaded into Widevine: the video can be decrypted now");
        }

        @Override
        public void onDrmSessionManagerError(@NonNull EventTime t, @NonNull Exception e) {
            line("DRM error: " + e);
        }

        @Override
        public void onVideoDecoderInitialized(@NonNull EventTime t, @NonNull String name, long at, long took) {
            line("Video decoder: " + name + (name.endsWith(".secure") ? " (secure, hardware path)" : ""));
        }

        @Override
        public void onVideoInputFormatChanged(@NonNull EventTime t, @NonNull Format f, DecoderReuseEvaluation r) {
            line("Video: " + f.width + "×" + f.height + " · " + f.codecs + " · " + (f.bitrate / 1000) + " kbit/s");
        }
    };

    // ================================================================= the car

    private final Telemetry.Listener carListener = readings -> runOnUiThread(this::checkCar);

    private void checkCar() {
        Readings r = Telemetry.get().readings();
        boolean hide = !BrowserPrefs.showWhileDriving(this)
                && DrivingGate.blocked(r.text(Signal.GEAR), Telemetry.get().latest().speedKmh);
        if (cover != null) {
            cover.setVisibility(hide ? View.VISIBLE : View.GONE);
        }
    }

    // ================================================================= views

    private void post(String s) {
        runOnUiThread(() -> line(s));
    }

    private void line(String s) {
        String l = clock.format(new Date()) + "  " + s;
        AppLog.i(TAG, s);
        lines.append(l).append('\n');
        if (log != null) {
            log.setText(lines);
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.bg));
        root.setPadding(16 * dp, 16 * dp, 16 * dp, 16 * dp);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        MaterialButton back = button("‹");
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        TextView title = new TextView(this);
        title.setText(R.string.drm_lab_title);
        title.setTextAppearance(R.style.TextAppearance_Cam_Title);
        title.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        title.setPadding(14 * dp, 0, 0, 0);
        bar.addView(title);
        root.addView(bar);

        HorizontalScrollView buttonsScroll = new HorizontalScrollView(this);
        LinearLayout buttons = new LinearLayout(this);
        for (String[] s : STREAMS) {
            MaterialButton b = button(s[0]);
            b.setOnClickListener(v -> play(s[0], s[1], s[2]));
            buttons.addView(b);
        }
        MaterialButton stop = button(getString(R.string.drm_lab_stop));
        stop.setOnClickListener(v -> {
            releasePlayer();
            line("Stopped");
        });
        buttons.addView(stop);
        MaterialButton again = button(getString(R.string.drm_lab_device));
        again.setOnClickListener(v -> deviceInfo());
        buttons.addView(again);
        buttonsScroll.addView(buttons);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.topMargin = 8 * dp;
        root.addView(buttonsScroll, bp);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyParams.topMargin = 12 * dp;
        root.addView(body, bodyParams);

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(0xFF000000);
        video = new SurfaceView(this);
        frame.addView(video, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        cover = new TextView(this);
        cover.setText(R.string.browser_paused_driving);
        cover.setGravity(Gravity.CENTER);
        cover.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        cover.setBackgroundColor(0xFF000000);
        cover.setVisibility(View.GONE);
        frame.addView(cover, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        body.addView(frame, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        logScroll = new ScrollView(this);
        logScroll.setBackgroundResource(R.drawable.bg_pref_row_static);
        log = new TextView(this);
        log.setTextAppearance(R.style.TextAppearance_Cam_Caption);
        log.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        log.setTypeface(android.graphics.Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        log.setPadding(12 * dp, 12 * dp, 12 * dp, 12 * dp);
        logScroll.addView(log);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        lp.setMarginStart(12 * dp);
        body.addView(logScroll, lp);
        return root;
    }

    private MaterialButton button(String label) {
        MaterialButton b = new MaterialButton(
                new android.view.ContextThemeWrapper(this, R.style.ThemeOverlay_Cam_RailSmall), null,
                com.google.android.material.R.attr.materialButtonStyle);
        b.setText(label);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, 52 * dp);
        p.setMarginEnd(10 * dp);
        b.setLayoutParams(p);
        return b;
    }
}
