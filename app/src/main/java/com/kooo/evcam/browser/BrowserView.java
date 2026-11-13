package com.kooo.evcam.browser;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.webkit.UserAgentMetadata;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.airplay.DrivingGate;
import com.kooo.evcam.telemetry.Readings;
import com.kooo.evcam.telemetry.Signal;
import com.kooo.evcam.telemetry.Telemetry;

import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The browser itself, shared by the full screen ({@link BrowserActivity}) and the floating window
 * ({@link BrowserFloat}): a toolbar and a WebView that tells sites it is desktop Chrome, so they
 * serve their desktop site (Netflix sends phones and tablets to its app).
 *
 * <p>Protected video (Widevine) is allowed when a page asks; whether it plays depends on the head
 * unit's Widevine. The picture hides while the car is in gear or moving ({@link DrivingGate}); the
 * sound keeps playing, like AirPlay. Links to other apps ({@code intent:}, {@code nflx:}) are not
 * followed, so a site cannot throw you out to its app.</p>
 */
@SuppressLint("ViewConstructor")
public final class BrowserView extends FrameLayout {

    /** What the window holding the browser does for it. */
    public interface Host {
        void close();

        /** Full screen to floating, or the other way, at {@code url}. */
        void switchMode(String url);

        /** The floating window needs key focus while something is typed. */
        void wantKeys(boolean on);

        /**
         * Floating only: a grip that moves and pinches the window; {@code onTap} runs on a plain
         * tap (null: nothing). Null in full screen.
         */
        View.OnTouchListener grip(Runnable onTap);

        /** The toolbar was hidden or shown (the floating window drops or adds its height). */
        default void toolbarShown(boolean shown) {
        }

        /** Where the page starts below the window's top: the toolbar's height, or 0 (hidden, video full screen). */
        default void pageTopChanged(int top) {
        }

        boolean floating();
    }

    private static final String TAG = "Browser";
    private static final String TELEMETRY_USER = "browser";
    /** Height of the floating window's drag bar: easy to grab with a finger. */
    private static final int DRAG_BAR_DP = 28;
    private static final Pattern CHROME = Pattern.compile("Chrome/([0-9.]+)");
    /** Sites read these too, not only the user agent. */
    private static final String DESKTOP_JS =
            "try{Object.defineProperty(navigator,'platform',{get:function(){return 'Win32';}});"
                    + "Object.defineProperty(navigator,'maxTouchPoints',{get:function(){return 0;}});}catch(e){}";

    private final Host host;
    private final float density;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WebView web;
    private final EditText address;
    private final LinearLayout toolbar;
    /** Floating only: a bar across the top to move the window by (null in full screen). */
    private final View dragBar;
    /** Shown while the toolbar is hidden: tap to bring it back (floating: drag to move). */
    private final ImageView showHandle;
    private final View cover;
    private final FrameLayout fullHolder;
    private View fullView;
    private WebChromeClient.CustomViewCallback fullCallback;
    private boolean watching;
    private boolean blocked;

    public BrowserView(Context context, Host host, String url) {
        super(context);
        this.host = host;
        density = context.getResources().getDisplayMetrics().density;
        setBackgroundColor(0xFF000000);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        addView(column, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        toolbar = new LinearLayout(context);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBackgroundColor(ContextCompat.getColor(context, R.color.bg));
        int pad = dp(6);
        toolbar.setPadding(pad, pad, pad, pad);
        if (host.floating()) {
            // The whole width moves the window, not just the small grip at the toolbar's left end
            FrameLayout bar = new FrameLayout(context);
            bar.setBackgroundColor(ContextCompat.getColor(context, R.color.bg));
            View pill = new View(context);
            GradientDrawable pillShape = new GradientDrawable();
            pillShape.setColor(ContextCompat.getColor(context, R.color.text_tertiary));
            pillShape.setCornerRadius(dp(3));
            pill.setBackground(pillShape);
            bar.addView(pill, new LayoutParams(dp(56), dp(5), Gravity.CENTER));
            bar.setContentDescription(context.getString(R.string.browser_move));
            bar.setOnTouchListener(host.grip(null));
            column.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(DRAG_BAR_DP)));
            dragBar = bar;
        } else {
            dragBar = null;
        }
        column.addView(toolbar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60)));

        web = new WebView(context);
        column.addView(web, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        if (host.floating()) {
            ImageView grip = icon(R.drawable.ic_float_window, R.string.browser_move, null);
            grip.setOnTouchListener(host.grip(null));
            toolbar.addView(grip, square());
        }
        toolbar.addView(icon(R.drawable.ic_back, R.string.browser_back, v -> {
            if (web.canGoBack()) {
                web.goBack();
            }
        }), square());
        toolbar.addView(icon(R.drawable.ic_chevron_right, R.string.browser_forward, v -> {
            if (web.canGoForward()) {
                web.goForward();
            }
        }), square());
        toolbar.addView(icon(R.drawable.ic_refresh, R.string.browser_reload, v -> web.reload()), square());

        address = new EditText(context);
        address.setSingleLine(true);
        address.setSelectAllOnFocus(true);
        address.setTextAppearance(R.style.TextAppearance_Cam_Body);
        address.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        address.setHintTextColor(ContextCompat.getColor(context, R.color.text_hint));
        address.setHint(R.string.browser_address_hint);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_URI);
        GradientDrawable field = new GradientDrawable();
        field.setColor(ContextCompat.getColor(context, R.color.surface));
        field.setCornerRadius(dp(10));
        address.setBackground(field);
        address.setPadding(dp(14), 0, dp(14), 0);
        address.setOnFocusChangeListener((v, has) -> {
            if (has) {
                host.wantKeys(true);
            }
        });
        address.setOnEditorActionListener((v, action, event) -> {
            load(BrowserPrefs.toUrl(address.getText().toString()));
            hideKeyboard();
            if (!host.floating()) {
                // (Floating: focusing the page makes the window scroll its content up under the toolbar)
                web.requestFocus();
            }
            // Floating: give the keys back to the app underneath until the page is touched again
            host.wantKeys(false);
            return true;
        });
        LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        fieldParams.setMarginStart(dp(6));
        fieldParams.setMarginEnd(dp(6));
        toolbar.addView(address, fieldParams);

        // Netflix plays only in its own app on Android: open the app when it is there
        toolbar.addView(word(R.string.browser_netflix, v -> {
            if (NetflixApp.installed(getContext())) {
                NetflixApp.open(getContext());
            } else {
                load("https://www.netflix.com/browse");
            }
        }));
        toolbar.addView(word(R.string.browser_youtube, v -> load("https://www.youtube.com/")));
        toolbar.addView(icon(host.floating() ? R.drawable.ic_fullscreen : R.drawable.ic_floating,
                host.floating() ? R.string.browser_full : R.string.browser_float,
                v -> host.switchMode(currentUrl())), square());
        toolbar.addView(icon(R.drawable.ic_chevron_up, R.string.browser_hide_toolbar, v -> setToolbarShown(false)), square());
        toolbar.addView(icon(R.drawable.ic_close, R.string.browser_close, v -> host.close()), square());

        // Hidden while driving; a passenger can hold "Show for passengers" to turn the switch on
        cover = DrivingCover.build(context, R.string.browser_paused_driving, this::checkCar);
        cover.setVisibility(GONE);

        fullHolder = new FrameLayout(context);
        fullHolder.setBackgroundColor(0xFF000000);
        fullHolder.setVisibility(GONE);
        addView(fullHolder, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        showHandle = icon(R.drawable.ic_chevron_up, R.string.browser_show_toolbar, null);
        showHandle.setRotation(180f);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0xCC000000);
        dot.setStroke(dp(2), ContextCompat.getColor(getContext(), R.color.text_secondary));
        showHandle.setBackground(dot);
        showHandle.setAlpha(0.9f);
        showHandle.setVisibility(GONE);
        if (host.floating()) {
            showHandle.setOnTouchListener(host.grip(this::showChrome));
        } else {
            showHandle.setOnClickListener(v -> showChrome());
        }
        LayoutParams handleParams = new LayoutParams(dp(52), dp(52), Gravity.TOP | Gravity.END);
        handleParams.setMargins(dp(8), dp(8), dp(8), dp(8));
        addView(showHandle, handleParams);

        // The cover sits over everything, the page's own full screen video included
        LayoutParams coverParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
        addView(cover, coverParams);

        setUp();
        load(url == null || url.isEmpty() ? BrowserPrefs.lastUrl(context) : url);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setUp() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        String chrome = chromeVersion(s.getUserAgentString());
        s.setUserAgentString(desktopUserAgent(chrome));
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            String major = chrome.contains(".") ? chrome.substring(0, chrome.indexOf('.')) : chrome;
            WebSettingsCompat.setUserAgentMetadata(s, new UserAgentMetadata.Builder()
                    .setPlatform("Windows")
                    .setPlatformVersion("15.0.0")
                    .setArchitecture("x86")
                    .setBitness(64)
                    .setMobile(false)
                    .setModel("")
                    .setFullVersion(chrome)
                    .setBrandVersionList(java.util.Arrays.asList(
                            new UserAgentMetadata.BrandVersion.Builder().setBrand("Google Chrome")
                                    .setMajorVersion(major).setFullVersion(chrome).build(),
                            new UserAgentMetadata.BrandVersion.Builder().setBrand("Chromium")
                                    .setMajorVersion(major).setFullVersion(chrome).build(),
                            new UserAgentMetadata.BrandVersion.Builder().setBrand("Not)A;Brand")
                                    .setMajorVersion("99").setFullVersion("99.0.0.0").build()))
                    .build());
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, DESKTOP_JS, Collections.singleton("*"));
        }
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                // Only web pages: a site's "open in the app" link must not leave the browser
                return !"http".equals(scheme) && !"https".equals(scheme);
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                showUrl(url);
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                // Sites like YouTube change page without loading one: keep the address box right
                showUrl(url);
                BrowserPrefs.setLastUrl(getContext(), url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                showUrl(url);
                BrowserPrefs.setLastUrl(getContext(), url);
                CookieManager.getInstance().flush();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // Protected video (Netflix) only; camera and microphone stay off
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(r)) {
                        AppLog.i(TAG, "protected media allowed for " + request.getOrigin());
                        request.grant(new String[]{PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID});
                        return;
                    }
                }
                request.deny();
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                fullView = view;
                fullCallback = callback;
                fullHolder.addView(view, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
                fullHolder.setVisibility(VISIBLE);
                // The video covers the toolbar: the handle must still be there to get out
                refreshChrome();
            }

            @Override
            public void onHideCustomView() {
                leaveVideoFullScreen();
            }
        });
        web.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                // A tap on the page may land in a text box (Netflix sign in): let the keyboard come
                host.wantKeys(true);
            }
            return false;
        });
    }

    /**
     * Car head units often ship an old WebView (the emulator's is Chrome 91), and sites such as
     * Netflix turn away an old Chrome before trying anything. Report at least this version.
     */
    static final int MIN_CHROME = 130;

    static String chromeVersion(String defaultAgent) {
        Matcher m = CHROME.matcher(defaultAgent == null ? "" : defaultAgent);
        String v = m.find() ? m.group(1) : MIN_CHROME + ".0.0.0";
        try {
            int major = Integer.parseInt(v.contains(".") ? v.substring(0, v.indexOf('.')) : v);
            return major >= MIN_CHROME ? v : MIN_CHROME + ".0.0.0";
        } catch (NumberFormatException e) {
            return MIN_CHROME + ".0.0.0";
        }
    }

    static String desktopUserAgent(String chromeVersion) {
        String major = chromeVersion.contains(".") ? chromeVersion.substring(0, chromeVersion.indexOf('.')) : chromeVersion;
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                + major + ".0.0.0 Safari/537.36";
    }

    /** Hides the toolbar so the page (a video) fills the window, or brings it back. */
    public void setToolbarShown(boolean shown) {
        boolean now = toolbar.getVisibility() == VISIBLE;
        if (now == shown) {
            return;
        }
        if (!shown) {
            hideKeyboard();
        }
        toolbar.setVisibility(shown ? VISIBLE : GONE);
        if (dragBar != null) {
            dragBar.setVisibility(shown ? VISIBLE : GONE);
        }
        host.toolbarShown(shown);
        refreshChrome();
    }

    /** The handle's tap: leave the video's full screen and bring the toolbar back. */
    private void showChrome() {
        leaveVideoFullScreen();
        setToolbarShown(true);
    }

    /** The handle shows whenever the toolbar cannot be seen: hidden, or covered by a full screen video. */
    private void refreshChrome() {
        boolean covered = toolbar.getVisibility() != VISIBLE || fullView != null;
        showHandle.setVisibility(covered ? VISIBLE : GONE);
        host.pageTopChanged(pageTop());
    }

    /** Where the page starts: under the toolbar, or at the top when there is none to see. */
    public int pageTop() {
        return toolbar.getVisibility() == VISIBLE && fullView == null ? toolbarHeight() : 0;
    }

    /** The toolbar's height, for the floating window to add or drop. */
    public int toolbarHeight() {
        return dp(60) + (dragBar == null ? 0 : dp(DRAG_BAR_DP));
    }

    // ================================================================= the car

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!watching) {
            watching = true;
            Telemetry.get().acquire(getContext().getApplicationContext(), TELEMETRY_USER);
            Telemetry.get().addListener(carListener);
            checkCar();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (watching) {
            watching = false;
            Telemetry.get().removeListener(carListener);
            Telemetry.get().release(TELEMETRY_USER);
        }
        super.onDetachedFromWindow();
    }

    private final Telemetry.Listener carListener = readings -> main.post(this::checkCar);

    private void checkCar() {
        Readings r = Telemetry.get().readings();
        boolean now = !BrowserPrefs.showWhileDriving(getContext())
                && DrivingGate.blocked(r.text(Signal.GEAR), Telemetry.get().latest().speedKmh);
        if (now != blocked) {
            blocked = now;
            AppLog.i(TAG, now ? "picture hidden: driving" : "picture shown");
            if (now) {
                hideKeyboard();
            }
        }
        cover.setVisibility(now ? VISIBLE : GONE);
    }

    // ================================================================= page

    public void load(String url) {
        showUrl(url);
        web.loadUrl(url);
    }

    public String currentUrl() {
        String url = web.getUrl();
        return url == null ? BrowserPrefs.lastUrl(getContext()) : url;
    }

    /** Back: leave video full screen, then the page's history. False when there is nothing to go back to. */
    public boolean back() {
        if (fullView != null) {
            leaveVideoFullScreen();
            return true;
        }
        if (web.canGoBack()) {
            web.goBack();
            return true;
        }
        return false;
    }

    public void onPause() {
        web.onPause();
    }

    public void onResume() {
        web.onResume();
    }

    /** Ends the page for good (closing the window). */
    public void destroy() {
        BrowserPrefs.setLastUrl(getContext(), web.getUrl());
        CookieManager.getInstance().flush();
        leaveVideoFullScreen();
        web.stopLoading();
        web.loadUrl("about:blank");
        ViewGroup parent = (ViewGroup) web.getParent();
        if (parent != null) {
            parent.removeView(web);
        }
        web.destroy();
    }

    private void leaveVideoFullScreen() {
        if (fullView == null) {
            return;
        }
        fullHolder.removeView(fullView);
        fullHolder.setVisibility(GONE);
        fullView = null;
        WebChromeClient.CustomViewCallback cb = fullCallback;
        fullCallback = null;
        if (cb != null) {
            cb.onCustomViewHidden();
        }
        refreshChrome();
    }

    private void showUrl(String url) {
        if (url != null && !address.hasFocus()) {
            address.setText(url);
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
        if (imm != null) {
            imm.hideSoftInputFromWindow(getWindowToken(), 0);
        }
        address.clearFocus();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP && back()) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // ================================================================= views

    private ImageView icon(int res, int label, OnClickListener click) {
        ImageView b = new ImageView(getContext());
        b.setImageResource(res);
        b.setColorFilter(ContextCompat.getColor(getContext(), R.color.text_primary));
        b.setContentDescription(getContext().getString(label));
        int p = dp(10);
        b.setPadding(p, p, p, p);
        if (click != null) {
            b.setOnClickListener(click);
            b.setBackgroundResource(R.drawable.bg_pref_row_static);
        }
        return b;
    }

    private TextView word(int label, OnClickListener click) {
        TextView t = new TextView(getContext());
        t.setText(label);
        t.setTextAppearance(R.style.TextAppearance_Cam_Body);
        t.setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), 0, dp(14), 0);
        t.setBackgroundResource(R.drawable.bg_pref_row_static);
        t.setOnClickListener(click);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT);
        p.setMarginStart(dp(4));
        t.setLayoutParams(p);
        return t;
    }

    private LinearLayout.LayoutParams square() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(48), dp(48));
        p.setMarginStart(dp(4));
        return p;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
