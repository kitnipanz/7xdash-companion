package com.kooo.evcam.browser;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaDrm;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.google.android.material.button.MaterialButton;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.airplay.DrivingGate;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * Netflix and YouTube lab (beta): every way this head unit could play Netflix and YouTube, and
 * how smoothly each one does.
 *
 * <ul>
 *   <li><b>Check this car</b>: screen and refresh rates, the WebView's version, Widevine level,
 *   hardware video decoders (H.264, HEVC, VP9, AV1) and whether they reach 1080p60, and which
 *   player apps are installed. Ends with a verdict for each route.</li>
 *   <li><b>Check the web player</b>: inside the WebView, Media Source codecs, "smooth" and
 *   "power efficient" answers from Media Capabilities, and which Widevine robustness levels the
 *   page can get (what netflix.com and youtube.com ask for).</li>
 *   <li><b>Routes</b>: the Netflix and YouTube apps (and SmartTube, NewPipe, …), netflix.com,
 *   youtube.com desktop, mobile, TV (youtube.com/tv, made for remotes and weak hardware) and an
 *   embedded player, in the lab's own WebView.</li>
 *   <li><b>Smooth meter</b>: while a page plays, every 2 s: resolution, frames per second, dropped
 *   frames and buffer, from the page's own video element. Leaving a route logs its average.</li>
 *   <li><b>Force H.264</b>: tells pages VP9 and AV1 are not supported, so YouTube sends H.264,
 *   which almost every car decodes in hardware.</li>
 * </ul>
 *
 * <p>No JavaScript bridge is added to the WebView (third-party pages load in it): the probe
 * reports through console lines starting with {@code LAB:}. The picture hides while driving unless
 * "show the picture while driving" is on, like the browser.</p>
 */
public class StreamLabActivity extends AppCompatActivity {

    private static final String TAG = "StreamLab";
    private static final String TELEMETRY_USER = "stream-lab";
    private static final String PROBE_BASE = "https://stream-lab.7xdash.invalid/";
    private static final long METER_MS = 2000;
    /** A short, public, embeddable video (Big Buck Bunny, Blender Foundation). */
    private static final String YT_TEST_ID = "aqz-KE-bpKQ";

    private static final String NETFLIX = NetflixApp.PACKAGE;
    private static final String YOUTUBE = "com.google.android.youtube";
    /** Player apps worth knowing about: package, name. */
    private static final String[][] APPS = {
            {NETFLIX, "Netflix"},
            {YOUTUBE, "YouTube"},
            {"com.google.android.youtube.tv", "YouTube for Android TV"},
            {"com.google.android.apps.youtube.music", "YouTube Music"},
            {"app.revanced.android.youtube", "YouTube ReVanced"},
            {"com.teamsmart.videomanager.tv", "SmartTube"},
            {"org.smarttube.stable", "SmartTube (stable)"},
            {"org.smarttube.beta", "SmartTube (beta)"},
            {"org.schabi.newpipe", "NewPipe"},
            {"com.github.libretube", "LibreTube"},
            {"com.android.chrome", "Chrome"},
            {"com.google.android.gms", "Google Play services"},
            {"com.android.vending", "Play Store"},
    };
    /** Other YouTube players the "Other YouTube app" button opens, first installed wins. */
    private static final String[] OTHER_YOUTUBE = {
            "app.revanced.android.youtube", "org.smarttube.stable", "com.teamsmart.videomanager.tv",
            "org.smarttube.beta", "org.schabi.newpipe", "com.github.libretube", "com.google.android.youtube.tv",
    };

    /** Smart TV user agent: youtube.com/tv only serves its TV app to TVs. */
    private static final String TV_AGENT = "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/537.36"
            + " (KHTML, like Gecko) 76.0.3809.146/6.0 TV Safari/537.36";

    /** Hides VP9 and AV1 from pages so they pick H.264 (the h264ify trick). */
    private static final String FORCE_H264_JS = "(function(){var no=/vp0?9|av01|av1/i;"
            + "if(window.MediaSource){var t=MediaSource.isTypeSupported.bind(MediaSource);"
            + "MediaSource.isTypeSupported=function(x){return no.test(x)?false:t(x);};}"
            + "var c=HTMLMediaElement.prototype.canPlayType;"
            + "HTMLMediaElement.prototype.canPlayType=function(x){return no.test(x)?'':c.call(this,x);};"
            + "if(navigator.mediaCapabilities){var d=navigator.mediaCapabilities.decodingInfo.bind(navigator.mediaCapabilities);"
            + "navigator.mediaCapabilities.decodingInfo=function(cfg){if(cfg&&cfg.video&&no.test(cfg.video.contentType))"
            + "return Promise.resolve({supported:false,smooth:false,powerEfficient:false});return d(cfg);};}})();";

    /** The largest playing video on the page: size, frames, dropped frames, buffer ahead. */
    private static final String METER_JS = "(function(){var v=null,best=-1;"
            + "document.querySelectorAll('video').forEach(function(x){var a=x.videoWidth*x.videoHeight;"
            + "if(!x.paused&&a>best){best=a;v=x;}});if(!v)return '';"
            + "var q=v.getVideoPlaybackQuality?v.getVideoPlaybackQuality():null;"
            + "var b=0;for(var i=0;i<v.buffered.length;i++){if(v.buffered.start(i)<=v.currentTime&&v.buffered.end(i)>=v.currentTime)b=v.buffered.end(i)-v.currentTime;}"
            + "return JSON.stringify({w:v.videoWidth,h:v.videoHeight,t:q?q.totalVideoFrames:-1,"
            + "d:q?q.droppedVideoFrames:-1,b:b,r:v.playbackRate});})()";

    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private final StringBuilder lines = new StringBuilder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView log;
    private ScrollView logScroll;
    private FrameLayout frame;
    private WebView web;
    private TextView cover;
    private MaterialButton h264Button;
    private View fullView;
    private WebChromeClient.CustomViewCallback fullCallback;
    private String defaultAgent;
    private String desktopAgent;
    private ScriptHandler h264Script;
    private boolean forceH264;
    private int dp;

    // What the checks found, for the verdict
    private int widevineLevel; // 1 or 3, 0: none or unknown
    private boolean hwAvc1080p60;
    private boolean hwVp9;
    private boolean hwAv1;

    // Smooth meter for the route on screen
    private String route;
    private long lastFrames = -1;
    private long lastDropped;
    private long lastAt;
    private long routeFrames;
    private long routeDropped;
    private long routeMs;
    private int routeMaxH;
    private String lastShown = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = Math.round(getResources().getDisplayMetrics().density);
        setContentView(buildLayout());
        line("Netflix and YouTube lab (beta). Start with Check this car, then Check the web player,"
                + " then try each route. The smooth meter writes a line every 2 s while a page plays.");
    }

    @Override
    protected void onStart() {
        super.onStart();
        Telemetry.get().acquire(getApplicationContext(), TELEMETRY_USER);
        Telemetry.get().addListener(carListener);
        checkCar();
        web.onResume();
        main.postDelayed(meter, METER_MS);
    }

    @Override
    protected void onStop() {
        main.removeCallbacks(meter);
        web.onPause();
        Telemetry.get().removeListener(carListener);
        Telemetry.get().release(TELEMETRY_USER);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        web.stopLoading();
        web.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (fullView != null) {
            hideFull();
        } else if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // ================================================================= check this car

    private void checkDevice() {
        line("═══ Check this car ═══");
        display();
        webViewInfo();
        widevine();
        decoders();
        apps();
        verdict();
    }

    private void display() {
        Display d = getWindowManager().getDefaultDisplay();
        DisplayMetrics m = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(m);
        StringBuilder rates = new StringBuilder();
        if (d != null) {
            for (Display.Mode mode : d.getSupportedModes()) {
                rates.append(Math.round(mode.getRefreshRate())).append("Hz ");
            }
        }
        line("Screen: " + m.widthPixels + "×" + m.heightPixels + " · " + m.densityDpi + " dpi · refresh "
                + (d == null ? "?" : Math.round(d.getRefreshRate()) + " Hz (modes: " + rates.toString().trim() + ")"));
    }

    private void webViewInfo() {
        PackageInfo p = WebView.getCurrentWebViewPackage();
        line("WebView: " + (p == null ? "unknown" : p.packageName + " " + p.versionName)
                + " · user agent Chrome " + BrowserView.chromeVersion(defaultAgent));
        if (p != null) {
            String v = p.versionName == null ? "" : p.versionName;
            int major = 0;
            try {
                major = Integer.parseInt(v.contains(".") ? v.substring(0, v.indexOf('.')) : v);
            } catch (NumberFormatException ignored) {
            }
            if (major > 0 && major < 110) {
                line("  ⚠ Old WebView: YouTube and Netflix web may refuse it or run slowly. Update Android System WebView.");
            }
        }
    }

    private void widevine() {
        widevineLevel = 0;
        UUID wv = new UUID(0xEDEF8BA979D64ACEL, 0xA3C827DCD51D21EDL);
        if (!MediaDrm.isCryptoSchemeSupported(wv)) {
            line("Widevine: not on this car (Netflix cannot play at all)");
            return;
        }
        MediaDrm drm = null;
        try {
            drm = new MediaDrm(wv);
            String level = prop(drm, "securityLevel");
            widevineLevel = level.contains("1") ? 1 : level.contains("3") ? 3 : level.contains("2") ? 3 : 0;
            line("Widevine: " + level + " · version " + prop(drm, "version") + " · HDCP "
                    + prop(drm, "hdcpLevel") + " (max " + prop(drm, "maxHdcpLevel") + ") · system id "
                    + prop(drm, "systemId"));
        } catch (Exception e) {
            line("Widevine: cannot open (" + e + ")");
        } finally {
            if (drm != null) {
                drm.close();
            }
        }
    }

    private static String prop(MediaDrm drm, String name) {
        try {
            return drm.getPropertyString(name);
        } catch (RuntimeException e) {
            return "?";
        }
    }

    /** Hardware and software decoders for the four codecs streaming uses, and what they reach. */
    private void decoders() {
        hwAvc1080p60 = false;
        hwVp9 = false;
        hwAv1 = false;
        String[][] kinds = {
                {"video/avc", "H.264"},
                {"video/hevc", "HEVC"},
                {"video/x-vnd.on2.vp9", "VP9"},
                {"video/av01", "AV1"},
        };
        line("Video decoders (hw = hardware, the smooth one):");
        MediaCodecInfo[] all = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
        for (String[] k : kinds) {
            StringBuilder s = new StringBuilder();
            for (MediaCodecInfo info : all) {
                if (info.isEncoder() || !supports(info, k[0])) {
                    continue;
                }
                boolean hw = hardware(info);
                MediaCodecInfo.VideoCapabilities v;
                try {
                    v = info.getCapabilitiesForType(k[0]).getVideoCapabilities();
                } catch (RuntimeException e) {
                    continue;
                }
                if (v == null) {
                    continue;
                }
                boolean p60 = v.areSizeAndRateSupported(1920, 1080, 60);
                boolean uhd = v.areSizeAndRateSupported(3840, 2160, 30);
                if (s.length() > 0) {
                    s.append("\n      ");
                }
                s.append(hw ? "hw " : "sw ").append(info.getName()).append(" · max ")
                        .append(v.getSupportedWidths().getUpper()).append('×').append(v.getSupportedHeights().getUpper())
                        .append(" · 1080p60 ").append(p60 ? "yes" : "no")
                        .append(" · 4K30 ").append(uhd ? "yes" : "no");
                if (hw) {
                    if (k[1].equals("H.264") && p60) {
                        hwAvc1080p60 = true;
                    } else if (k[1].equals("VP9")) {
                        hwVp9 = true;
                    } else if (k[1].equals("AV1")) {
                        hwAv1 = true;
                    }
                }
            }
            line("  " + k[1] + ": " + (s.length() == 0 ? "none" : s));
        }
    }

    private static boolean supports(MediaCodecInfo info, String mime) {
        for (String t : info.getSupportedTypes()) {
            if (t.equalsIgnoreCase(mime)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hardware(MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isHardwareAccelerated();
        }
        String n = info.getName().toLowerCase(Locale.US);
        return !(n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw."));
    }

    private void apps() {
        line("Apps:");
        for (String[] a : APPS) {
            String v = version(a[0]);
            if (v != null) {
                line("  ✓ " + a[1] + " " + v);
            }
        }
        StringBuilder missing = new StringBuilder();
        for (String[] a : APPS) {
            if (version(a[0]) == null) {
                missing.append(missing.length() == 0 ? "" : ", ").append(a[1]);
            }
        }
        if (missing.length() > 0) {
            line("  not installed: " + missing);
        }
    }

    private String version(String pkg) {
        try {
            PackageInfo p = getPackageManager().getPackageInfo(pkg, 0);
            return p.versionName == null ? "" : p.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private void verdict() {
        boolean netflix = version(NETFLIX) != null;
        boolean youtube = version(YOUTUBE) != null;
        boolean gms = version("com.google.android.gms") != null;
        String other = firstInstalled(OTHER_YOUTUBE);
        line("— Verdict (from the checks; play each route to be sure) —");
        line("Netflix:");
        if (widevineLevel == 0) {
            line("  ✗ No usable Widevine: Netflix cannot play here by any route.");
        } else {
            line("  " + (netflix ? "✓" : "○") + " Netflix app: " + (netflix ? "installed. " : "not installed. ")
                    + (widevineLevel == 1 ? "Widevine L1: HD if Netflix lists this car as certified, else SD."
                    : "Widevine L3: SD at most (about 540p).")
                    + " Smooth: the app uses the hardware decoder. Best Netflix route.");
        }
        line("  ? netflix.com in the WebView: Netflix's web player wants a desktop Chrome Widevine module;"
                + " the WebView has Android's. Try it: an M7xxx / F7xxx error means this route is closed.");
        line("  ✗ AirPlay / screen mirroring from a phone: Netflix blacks out mirrored video (HDCP).");
        line("YouTube:");
        line("  " + (youtube ? (gms ? "✓" : "⚠") : "○") + " YouTube app: "
                + (youtube ? (gms ? "installed, with Google Play services." : "installed, but without Google Play services it may not sign in or play.")
                : "not installed.") + " Smoothest: hardware decoders, adaptive quality.");
        if (other != null) {
            line("  ✓ Other YouTube app installed: " + other + ". SmartTube is made for TVs and runs smoothly on weak hardware.");
        } else {
            line("  ○ SmartTube / NewPipe / ReVanced: not installed. SmartTube (TV) is the lightest native option.");
        }
        String codec = hwVp9 ? (hwAv1 ? "VP9 and AV1 in hardware" : "VP9 in hardware, AV1 not")
                : "no VP9 hardware decoder";
        line("  ✓ youtube.com/tv (TV page): light, big buttons, made for slow chips. " + codec
                + (hwVp9 ? "." : ": turn on Force H.264 for smooth 1080p."));
        line("  " + (hwVp9 || hwAvc1080p60 ? "✓" : "⚠") + " youtube.com desktop: heavy page; "
                + (hwVp9 ? "fine with hardware VP9." : "YouTube picks VP9/AV1 in software → dropped frames. Force H.264 fixes it.")
                + (hwAvc1080p60 ? " H.264 1080p60 in hardware: yes." : " H.264 1080p60 in hardware: no, keep 720p."));
        line("  ✓ m.youtube.com: lighter than desktop, same codec rule.");
        line("  ✓ AirPlay from an iPhone (7xDash AirPlay): YouTube mirrors fine, the phone does the decoding.");
    }

    private String firstInstalled(String[] packages) {
        for (String p : packages) {
            if (version(p) != null) {
                for (String[] a : APPS) {
                    if (a[0].equals(p)) {
                        return a[1];
                    }
                }
                return p;
            }
        }
        return null;
    }

    // ================================================================= check the web player

    private static final String PROBE_HTML = "<!doctype html><meta name=viewport content='width=device-width'>"
            + "<body style='background:#000;color:#aaa;font:16px sans-serif;padding:16px'>Checking the web player…"
            + "<script>(async function(){var R=function(s){console.log('LAB:'+s);};"
            + "try{"
            + "R('secure page: '+window.isSecureContext+' · MediaSource: '+('MediaSource' in window)"
            + "+' · ManagedMediaSource: '+('ManagedMediaSource' in window)+' · EME: '+('requestMediaKeySystemAccess' in navigator));"
            + "var T=[['H.264 High','video/mp4; codecs=\"avc1.640028\"'],['HEVC','video/mp4; codecs=\"hvc1.1.6.L120.90\"'],"
            + "['VP9','video/webm; codecs=\"vp9\"'],['VP9 (YouTube)','video/webm; codecs=\"vp09.00.40.08\"'],"
            + "['AV1','video/mp4; codecs=\"av01.0.08M.08\"'],['AAC','audio/mp4; codecs=\"mp4a.40.2\"'],"
            + "['Opus','audio/webm; codecs=\"opus\"'],['Dolby AC-3','audio/mp4; codecs=\"ac-3\"'],['Dolby E-AC-3','audio/mp4; codecs=\"ec-3\"']];"
            + "var ms=[];for(var i=0;i<T.length;i++){ms.push(T[i][0]+(window.MediaSource&&MediaSource.isTypeSupported(T[i][1])?' ✓':' ✗'));}"
            + "R('Media Source codecs: '+ms.join(' · '));"
            + "if(navigator.mediaCapabilities){"
            + "var C=[['H.264','video/mp4; codecs=\"avc1.640028\"'],['VP9','video/webm; codecs=\"vp09.00.40.08\"'],['AV1','video/mp4; codecs=\"av01.0.08M.08\"']];"
            + "var S=[[1280,720,30,2500000],[1920,1080,30,5000000],[1920,1080,60,8000000],[3840,2160,30,20000000]];"
            + "for(var c=0;c<C.length;c++){var out=[];for(var s=0;s<S.length;s++){"
            + "try{var r=await navigator.mediaCapabilities.decodingInfo({type:'media-source',video:{contentType:C[c][1],"
            + "width:S[s][0],height:S[s][1],framerate:S[s][2],bitrate:S[s][3]}});"
            + "out.push(S[s][1]+'p'+S[s][2]+' '+(!r.supported?'✗':r.smooth?(r.powerEfficient?'smooth+hw':'smooth'):'NOT smooth'));"
            + "}catch(e){out.push(S[s][1]+'p'+S[s][2]+' ?');}}"
            + "R('Smooth '+C[c][0]+': '+out.join(' · '));}}"
            + "else{R('Media Capabilities: not available');}"
            + "var K=[['com.widevine.alpha','HW_SECURE_ALL'],['com.widevine.alpha','HW_SECURE_DECODE'],"
            + "['com.widevine.alpha','HW_SECURE_CRYPTO'],['com.widevine.alpha','SW_SECURE_DECODE'],"
            + "['com.widevine.alpha','SW_SECURE_CRYPTO'],['com.widevine.alpha',''],"
            + "['com.microsoft.playready.recommendation',''],['com.microsoft.playready',''],['org.w3.clearkey','']];"
            + "for(var k=0;k<K.length;k++){var name=K[k][0]+(K[k][1]?' '+K[k][1]:'');"
            + "try{var a=await navigator.requestMediaKeySystemAccess(K[k][0],[{initDataTypes:['cenc'],"
            + "videoCapabilities:[{contentType:'video/mp4; codecs=\"avc1.640028\"',robustness:K[k][1]}],"
            + "audioCapabilities:[{contentType:'audio/mp4; codecs=\"mp4a.40.2\"',robustness:K[k][1]?'SW_SECURE_CRYPTO':''}]}]);"
            + "var ok='✓';try{await a.createMediaKeys();ok='✓ (keys created)';}catch(e){ok='✓ access, keys failed: '+e.name;}"
            + "R('DRM '+name+': '+ok);}catch(e){R('DRM '+name+': ✗ '+(e.name||e));}}"
            + "}catch(e){R('probe error: '+e);}"
            + "R('DONE');document.body.textContent='Web player check done. See the log.';})();</script>";

    private void checkWeb() {
        line("═══ Check the web player (WebView" + (forceH264 ? ", Force H.264 on" : "") + ") ═══");
        endRoute();
        route = null;
        web.getSettings().setUserAgentString(desktopAgent);
        web.loadDataWithBaseURL(PROBE_BASE, PROBE_HTML, "text/html", "utf-8", null);
    }

    private void onProbeLine(String s) {
        if (s.equals("DONE")) {
            line("Web check done. Netflix web needs Widevine (a ✓ above, best HW_SECURE_ALL for HD);"
                    + " YouTube needs Media Source with H.264 or VP9 \"smooth\" at the size you want.");
            return;
        }
        line("  " + s);
    }

    // ================================================================= routes

    private void openWeb(String name, String url, String agent) {
        endRoute();
        route = name;
        routeFrames = 0;
        routeDropped = 0;
        routeMs = 0;
        routeMaxH = 0;
        lastFrames = -1;
        lastShown = "";
        line("▶ " + name + " · " + url + (forceH264 ? " · Force H.264" : ""));
        web.getSettings().setUserAgentString(agent);
        web.loadUrl(url);
        checkCar();
    }

    private void openApp(String name, String pkg, String deepLink) {
        endRoute();
        route = null;
        if (pkg == null || version(pkg) == null) {
            line("▶ " + name + ": not installed on this car.");
            return;
        }
        Intent i = null;
        if (deepLink != null) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).setPackage(pkg);
            if (i.resolveActivity(getPackageManager()) == null) {
                i = null;
            }
        }
        if (i == null) {
            i = getPackageManager().getLaunchIntentForPackage(pkg);
        }
        if (i == null) {
            line("▶ " + name + ": installed, but has nothing to open (no launcher screen).");
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
            line("▶ " + name + " " + version(pkg) + " opened. Play something and judge it there; come back with Back."
                    + (pkg.equals(NETFLIX) ? " In Netflix: Account → (top) App settings → Playback specification shows"
                    + " Widevine level and the quality it allows." : ""));
        } catch (ActivityNotFoundException | SecurityException e) {
            line("▶ " + name + ": cannot open (" + e.getMessage() + ")");
        }
    }

    private void openOtherYouTube() {
        for (String p : OTHER_YOUTUBE) {
            if (version(p) != null) {
                openApp(firstInstalled(new String[]{p}), p, null);
                return;
            }
        }
        line("▶ Other YouTube apps: none installed (SmartTube, NewPipe, LibreTube, ReVanced, YouTube for Android TV).");
    }

    private void stop() {
        endRoute();
        route = null;
        hideFull();
        web.loadUrl("about:blank");
        line("Stopped");
    }

    private void setForceH264(boolean on) {
        forceH264 = on;
        if (h264Script != null) {
            h264Script.remove();
            h264Script = null;
        }
        if (on) {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                line("Force H.264: this WebView cannot run a script before the page; not available.");
                forceH264 = false;
            } else {
                h264Script = WebViewCompat.addDocumentStartJavaScript(web, FORCE_H264_JS, Collections.singleton("*"));
            }
        }
        h264Button.setText(forceH264 ? "Force H.264: on" : "Force H.264: off");
        line("Force H.264 " + (forceH264 ? "on: pages see no VP9 or AV1 and pick H.264. Reloading."
                : "off: pages pick their own codec. Reloading."));
        if (web.getUrl() != null && !web.getUrl().equals("about:blank")) {
            web.reload();
        }
    }

    // ================================================================= smooth meter

    private final Runnable meter = new Runnable() {
        @Override
        public void run() {
            main.postDelayed(this, METER_MS);
            if (route == null) {
                return;
            }
            web.evaluateJavascript(METER_JS, StreamLabActivity.this::onMeter);
        }
    };

    private void onMeter(String value) {
        if (route == null || value == null || value.equals("null") || value.equals("\"\"")) {
            return;
        }
        try {
            // evaluateJavascript hands back a JSON string literal holding our JSON
            JSONObject o = new JSONObject(new org.json.JSONTokener(value).nextValue().toString());
            long frames = o.getLong("t");
            long dropped = o.getLong("d");
            int w = o.getInt("w");
            int h = o.getInt("h");
            double buffer = o.getDouble("b");
            long now = SystemClock.elapsedRealtime();
            if (frames < 0) {
                show(w + "×" + h + " · frame counts not reported · buffer " + Math.round(buffer) + " s");
                return;
            }
            if (lastFrames >= 0 && frames >= lastFrames && now > lastAt) {
                long df = frames - lastFrames;
                long dd = Math.max(0, dropped - lastDropped);
                long dt = now - lastAt;
                routeFrames += df;
                routeDropped += dd;
                routeMs += dt;
                routeMaxH = Math.max(routeMaxH, h);
                double fps = df * 1000.0 / dt;
                double drop = df == 0 ? 0 : dd * 100.0 / df;
                show(String.format(Locale.US, "%d×%d · %.1f fps · dropped %.1f%% · buffer %d s %s",
                        w, h, fps, drop, Math.round(buffer), drop < 1 ? "✓ smooth" : drop < 5 ? "⚠ some drops" : "✗ choppy"));
            }
            lastFrames = frames;
            lastDropped = dropped;
            lastAt = now;
        } catch (Exception e) {
            AppLog.w(TAG, "meter: " + e);
        }
    }

    /** Logs a meter line; a paused or stalled video is said once, not every 2 s. */
    private void show(String s) {
        if (s.contains(" 0.0 fps")) {
            if (!lastShown.equals("paused")) {
                line("  ⏸ paused or stalled");
            }
            lastShown = "paused";
            return;
        }
        lastShown = "playing";
        line("  " + s);
    }

    /** The average of the route that is ending. */
    private void endRoute() {
        if (route != null && routeMs > 0 && routeFrames > 0) {
            double fps = routeFrames * 1000.0 / routeMs;
            double drop = routeDropped * 100.0 / routeFrames;
            line(String.format(Locale.US, "■ %s: %.0f s played · up to %dp · %.1f fps · dropped %.1f%% → %s",
                    route, routeMs / 1000.0, routeMaxH, fps, drop,
                    drop < 1 ? "smooth" : drop < 5 ? "watchable, some drops" : "choppy"));
        }
        route = null;
    }

    // ================================================================= the car

    private final Telemetry.Listener carListener = readings -> runOnUiThread(this::checkCar);

    private void checkCar() {
        Readings r = Telemetry.get().readings();
        boolean hide = !BrowserPrefs.showWhileDriving(this)
                && DrivingGate.blocked(r.text(Signal.GEAR), Telemetry.get().latest().speedKmh);
        if (cover != null) {
            cover.setVisibility(hide ? View.VISIBLE : View.GONE);
            cover.bringToFront();
        }
    }

    // ================================================================= web view

    @SuppressLint("SetJavaScriptEnabled")
    private WebView buildWeb() {
        WebView w = new WebView(this);
        w.setBackgroundColor(0xFF000000);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        defaultAgent = s.getUserAgentString();
        desktopAgent = BrowserView.desktopUserAgent(BrowserView.chromeVersion(defaultAgent));
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                if (!"http".equals(scheme) && !"https".equals(scheme)) {
                    line("  page asked to open " + scheme + ": (an app link), not followed");
                    return true;
                }
                return false;
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(r)) {
                        line("  page asked for protected video (Widevine): allowed");
                        request.grant(new String[]{PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID});
                        return;
                    }
                }
                request.deny();
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                String s = m.message();
                if (s != null && s.startsWith("LAB:")) {
                    onProbeLine(s.substring(4));
                    return true;
                }
                if (route != null && m.messageLevel() == ConsoleMessage.MessageLevel.ERROR && s != null
                        && s.toLowerCase(Locale.US).matches(".*(\\bdrm\\b|widevine|\\beme\\b|keysystem|mediakeys|decod|\\bm7\\d{3}\\b|\\bf7\\d{3}\\b).*")) {
                    line("  page error: " + (s.length() > 200 ? s.substring(0, 200) + "…" : s));
                }
                return true;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                hideFull();
                fullView = view;
                fullCallback = callback;
                frame.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                cover.bringToFront();
            }

            @Override
            public void onHideCustomView() {
                hideFull();
            }
        });
        return w;
    }

    private void hideFull() {
        if (fullView != null) {
            frame.removeView(fullView);
            fullView = null;
            if (fullCallback != null) {
                fullCallback.onCustomViewHidden();
                fullCallback = null;
            }
        }
    }

    // ================================================================= views

    private void line(String s) {
        AppLog.i(TAG, s);
        lines.append(clock.format(new Date())).append("  ").append(s).append('\n');
        if (log != null) {
            log.setText(lines);
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void copyAll() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Netflix and YouTube lab", lines.toString()));
            Toast.makeText(this, getString(R.string.stargate_lab_copied, lines.length()), Toast.LENGTH_SHORT).show();
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
        title.setText(R.string.stream_lab_title);
        title.setTextAppearance(R.style.TextAppearance_Cam_Title);
        title.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        title.setPadding(14 * dp, 0, 0, 0);
        bar.addView(title);
        root.addView(bar);

        web = buildWeb();

        LinearLayout checks = row(root);
        add(checks, "Check this car", v -> checkDevice());
        add(checks, "Check the web player", v -> checkWeb());
        h264Button = add(checks, "Force H.264: off", v -> setForceH264(!forceH264));
        add(checks, "Stop", v -> stop());
        add(checks, getString(R.string.stargate_lab_copy_all), v -> copyAll());

        LinearLayout netflix = row(root);
        label(netflix, "Netflix");
        add(netflix, "Netflix app", v -> openApp("Netflix app", NETFLIX, null));
        add(netflix, "netflix.com (web)", v -> openWeb("netflix.com", "https://www.netflix.com/browse", desktopAgent));

        LinearLayout yt = row(root);
        label(yt, "YouTube");
        add(yt, "YouTube app", v -> openApp("YouTube app", YOUTUBE, "https://www.youtube.com/watch?v=" + YT_TEST_ID));
        add(yt, "Other YouTube app", v -> openOtherYouTube());
        add(yt, "TV page", v -> openWeb("youtube.com/tv", "https://www.youtube.com/tv", TV_AGENT));
        add(yt, "Desktop", v -> openWeb("youtube.com desktop",
                "https://www.youtube.com/watch?v=" + YT_TEST_ID, desktopAgent));
        add(yt, "Mobile", v -> openWeb("m.youtube.com", "https://m.youtube.com/watch?v=" + YT_TEST_ID, defaultAgent));
        add(yt, "Embedded", v -> openWeb("youtube embed",
                "https://www.youtube-nocookie.com/embed/" + YT_TEST_ID + "?autoplay=1&playsinline=1", desktopAgent));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyParams.topMargin = 12 * dp;
        root.addView(body, bodyParams);

        frame = new FrameLayout(this);
        frame.setBackgroundColor(0xFF000000);
        frame.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        cover = new TextView(this);
        cover.setText(R.string.browser_paused_driving);
        cover.setGravity(Gravity.CENTER);
        cover.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        cover.setBackgroundColor(0xFF000000);
        cover.setClickable(true);
        cover.setVisibility(View.GONE);
        frame.addView(cover, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        body.addView(frame, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 3f));

        logScroll = new ScrollView(this);
        logScroll.setBackgroundResource(R.drawable.bg_pref_row_static);
        log = new TextView(this);
        log.setTextAppearance(R.style.TextAppearance_Cam_Caption);
        log.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        log.setTypeface(android.graphics.Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        log.setPadding(12 * dp, 12 * dp, 12 * dp, 12 * dp);
        logScroll.addView(log);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2f);
        lp.setMarginStart(12 * dp);
        body.addView(logScroll, lp);
        return root;
    }

    private LinearLayout row(LinearLayout root) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(row);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = 8 * dp;
        root.addView(scroll, p);
        return row;
    }

    private void label(LinearLayout row, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextAppearance(R.style.TextAppearance_Cam_Body);
        t.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        t.setMinWidth(90 * dp);
        row.addView(t);
    }

    private MaterialButton add(LinearLayout row, String label, View.OnClickListener click) {
        MaterialButton b = button(label);
        b.setOnClickListener(click);
        row.addView(b);
        return b;
    }

    private MaterialButton button(String label) {
        MaterialButton b = new MaterialButton(
                new android.view.ContextThemeWrapper(this, R.style.ThemeOverlay_Cam_RailSmall), null,
                com.google.android.material.R.attr.materialButtonStyle);
        b.setText(label);
        b.setAllCaps(false);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, 52 * dp);
        p.setMarginEnd(10 * dp);
        b.setLayoutParams(p);
        return b;
    }
}
