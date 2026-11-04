package com.kooo.evcam.nav;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.overlay.OverlayCoordinator;
import com.kooo.evcam.trip.Arrival;
import com.kooo.evcam.trip.DestinationLink;
import com.kooo.evcam.trip.Trip;

import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.SupportMapFragment;

/**
 * Navigation (beta): search a place (or take the one shared from the iPhone, or hold on the map),
 * see the route with its time, distance and battery at arrival, then Start for turn-by-turn.
 * The map is Google's (Maps SDK, needs Google Play services); without it the screen still
 * guides, just with no map. The guiding itself is {@link Navigator}'s and goes on when this closes.
 * The map can also go into a floating window ({@link NavFloatWindow}) over other apps, and while
 * guiding the sheet shows what the HUD shows ({@link HudTurnCard}).
 */
public class NavigationActivity extends AppCompatActivity implements Navigator.Listener {

    private static final String TAG = "NavigationActivity";

    private AppConfig config;
    private EditText search;
    private LinearLayout results;
    private View searchBar;
    private View banner;
    private ManeuverIcon.ArrowView arrow;
    private TextView toNext;
    private TextView instruction;
    private TextView instructionDetail;
    private TextView place;
    private TextView summary;
    private TextView status;
    private View options;
    private MaterialButton go;
    private MaterialButton end;
    private MaterialButton mute;
    private View recenter;
    private LinearLayout routes;
    /** The choices and the chosen one the cards were last made for. */
    private java.util.List<NavRoute> shownRoutes;
    private NavRoute shownChosen;
    private View hudPreview;
    private TextView hudPreviewLabel;
    private HudTurnCard hudCard;

    private MapLibreMap map;
    /** Null until the map is ready (and stays null without one). */
    private NavMapPainter painter;
    private Navigator.Phase shownPhase;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_navigation);
        config = new AppConfig(this);
        search = findViewById(R.id.nav_search);
        results = findViewById(R.id.nav_results);
        searchBar = findViewById(R.id.nav_search_bar);
        banner = findViewById(R.id.nav_banner);
        arrow = findViewById(R.id.nav_arrow);
        toNext = findViewById(R.id.nav_to_next);
        instruction = findViewById(R.id.nav_instruction);
        instructionDetail = findViewById(R.id.nav_instruction_detail);
        place = findViewById(R.id.nav_place);
        summary = findViewById(R.id.nav_summary);
        status = findViewById(R.id.nav_status);
        options = findViewById(R.id.nav_options);
        go = findViewById(R.id.nav_go);
        end = findViewById(R.id.nav_end);
        mute = findViewById(R.id.nav_mute);
        recenter = findViewById(R.id.nav_recenter);
        routes = findViewById(R.id.nav_routes);
        hudPreview = findViewById(R.id.nav_hud_preview);
        hudPreviewLabel = findViewById(R.id.nav_hud_preview_label);
        hudCard = new HudTurnCard(this, Math.round(84 * getResources().getDisplayMetrics().density));
        ((FrameLayout) findViewById(R.id.nav_hud_preview_card)).addView(hudCard.view,
                new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        findViewById(R.id.nav_close).setOnClickListener(v -> finish());
        search.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
                runSearch();
                return true;
            }
            return false;
        });
        search.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                showSharedPlace();
            }
        });
        go.setOnClickListener(v -> {
            Navigator.Snapshot s = Navigator.get().snapshot();
            if (s.phase == Navigator.Phase.FAILED) {
                Navigator.get().retry();
            } else {
                if (painter != null) {
                    painter.following = true;
                }
                Navigator.get().start();
            }
        });
        end.setOnClickListener(v -> {
            Navigator.get().end();
            clearMap();
        });
        mute.setOnClickListener(v -> Navigator.get().setMuted(!Navigator.get().snapshot().muted));
        recenter.setOnClickListener(v -> {
            recenter.setVisibility(View.GONE);
            if (painter != null) {
                painter.recenter(Navigator.get().snapshot());
            }
        });
        findViewById(R.id.nav_float).setOnClickListener(v -> openFloating());
        findViewById(R.id.nav_settings).setOnClickListener(v -> openSettings());

        setUpMap();
    }

    private void setUpMap() {
        if (!Navigator.hasKey()) {
            AppLog.w(TAG, "no map: no key");
            findViewById(R.id.nav_no_map).setVisibility(View.VISIBLE);
            return;
        }
        NavMap.start(this);
        SupportMapFragment fragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.nav_map);
        if (fragment == null) {
            fragment = SupportMapFragment.newInstance(NavMap.options(this));
            getSupportFragmentManager().beginTransaction().replace(R.id.nav_map, fragment).commitNow();
        }
        fragment.getMapAsync(this::onMap);
    }

    private void onMap(MapLibreMap m) {
        map = m;
        float d = getResources().getDisplayMetrics().density;
        // the cards cover the left and the top: keep the route and the compass clear of them
        painter = new NavMapPainter(this, map, findViewById(R.id.nav_map), Math.round(540 * d), 0);
        painter.onRouteTapped = r -> Navigator.get().choose(r);
        map.getUiSettings().setCompassEnabled(true);
        map.addOnCameraMoveStartedListener(reason -> {
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                painter.following = false;
                recenter.setVisibility(View.VISIBLE);
            }
        });
        map.addOnMapLongClickListener(at -> {
            if (!Navigator.get().isGuiding()) {
                Navigator.get().preview(this, new DestinationLink.Place(at.getLatitude(), at.getLongitude(),
                        getString(R.string.nav_dropped_pin)));
                painter.following = false;
            }
            return true;
        });
        onNavChanged(Navigator.get().snapshot());
    }

    /**
     * Place the HUD turn card by hand: the HUD drawn small with the card on it; drag to move, drag
     * the corner to resize. The real HUD follows as it moves (a sample card when nothing is guided).
     */
    private void openHudPlacer() {
        android.view.Display hud = com.kooo.evcam.zeekr.HudDisplay.find(this);
        android.graphics.Point size = hud == null ? new android.graphics.Point(1920, 640)
                : com.kooo.evcam.zeekr.HudDisplay.sizeOf(hud);
        int left = config.getHudNoticeLeftPercent();
        int right = config.getHudNoticeRightPercent();
        float[] stored = HudTurnPlace.restore(config.getNavHudPlace());
        float[] start = stored != null ? stored : HudTurnPlace.defaults(size.x, size.y, left, right);
        float[] storedMap = HudTurnPlace.restore(config.getNavHudMapPlace());
        float[] mapStart = !config.isNavHudMapEnabled() ? null
                : storedMap != null ? storedMap : HudTurnPlace.mapDefaults();
        Navigator.HudLine line = Navigator.hudLine(this, Navigator.get().snapshot());
        long[] lastLive = {0};
        HudPlacementView placer = new HudPlacementView(this, (float) size.x / size.y, start, mapStart,
                line != null ? line : Navigator.sampleHudLine(this), (which, shares, done) -> {
                    if (which == HudPlacementView.MAP) {
                        config.setNavHudMapPlace(HudTurnPlace.store(shares));
                    } else {
                        config.setNavHudPlace(HudTurnPlace.store(shares));
                    }
                    long now = android.os.SystemClock.uptimeMillis();
                    if (done || now - lastLive[0] > 120) {
                        // the real HUD follows, not on every move: each one opens its window again
                        lastLive[0] = now;
                        Navigator.get().relayoutHud();
                    }
                });
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView hint = new TextView(this);
        hint.setText(hud == null ? R.string.nav_hud_adjust_no_hud : R.string.nav_hud_adjust_hint);
        hint.setTextAppearance(R.style.TextAppearance_Cam_Body);
        hint.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        box.addView(hint);
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        at.topMargin = pad / 2;
        box.addView(placer, at);
        Navigator.get().setHudPlacing(this, true);
        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.nav_hud_adjust)
                .setView(box)
                .setPositiveButton(R.string.nav_hud_adjust_done, null)
                .setNeutralButton(R.string.nav_hud_adjust_reset, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(v -> {
                    config.setNavHudPlace("");
                    config.setNavHudMapPlace("");
                    placer.setShares(HudPlacementView.CARD, HudTurnPlace.defaults(size.x, size.y, left, right));
                    if (config.isNavHudMapEnabled()) {
                        placer.setShares(HudPlacementView.MAP, HudTurnPlace.mapDefaults());
                    }
                    Navigator.get().relayoutHud();
                }));
        // a stray tap beside the HUD drawing must not close it mid-placing
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(d -> {
            Navigator.get().setHudPlacing(this, false);
            showHudPreview(Navigator.get().snapshot());
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(Math.round(getResources().getDisplayMetrics().widthPixels * 0.7f),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    /** The QR code the iPhone scans once to send places here (Google Maps / Apple Maps → Share → Send to 7xDash). */
    private void openPhoneSetup() {
        com.kooo.evcam.trip.PhoneSetup setup = com.kooo.evcam.trip.PhoneSetup.now(this);
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Math.round(20 * d);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView hint = new TextView(this);
        hint.setText(R.string.arrival_setup_hint);
        hint.setTextAppearance(R.style.TextAppearance_Cam_Body);
        hint.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        box.addView(hint);
        if (setup.pageUrl != null) {
            int size = Math.round(240 * d);
            android.widget.ImageView qr = new android.widget.ImageView(this);
            qr.setImageBitmap(com.kooo.evcam.share.QrCode.encode(setup.pageUrl, size));
            qr.setContentDescription(getString(R.string.arrival_setup_title));
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(size, size);
            at.topMargin = pad;
            box.addView(qr, at);
        }
        TextView note = new TextView(this);
        note.setText(setup.note);
        note.setGravity(Gravity.CENTER);
        note.setTextIsSelectable(true);
        note.setTextAppearance(R.style.TextAppearance_Cam_Caption);
        note.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        note.setPadding(0, pad / 2, 0, 0);
        box.addView(note);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.arrival_setup_title)
                .setView(box)
                .setPositiveButton(R.string.nav_hud_adjust_done, null)
                .show();
    }

    /**
     * Before starting, the routes Google offers: time and distance, the main road, Google's pick
     * marked; the chosen one highlighted. A tap (or a tap on its grey line) chooses it.
     */
    private void showRoutes(Navigator.Snapshot s) {
        java.util.List<NavRoute> choices = s.phase == Navigator.Phase.PREVIEW && s.choices.size() > 1
                ? s.choices : java.util.Collections.emptyList();
        routes.setVisibility(choices.isEmpty() ? View.GONE : View.VISIBLE);
        if (choices.equals(shownRoutes) && s.route == shownChosen) {
            return;
        }
        shownRoutes = choices;
        shownChosen = s.route;
        routes.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        for (NavRoute r : choices) {
            boolean chosen = r == s.route;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            int pad = Math.round(12 * d);
            card.setPadding(pad, pad / 2 + 2, pad, pad / 2 + 2);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(12 * d);
            bg.setColor(ContextCompat.getColor(this, chosen ? R.color.energy_quiet : R.color.surface));
            bg.setStroke(Math.round((chosen ? 2 : 1) * d), ContextCompat.getColor(this, chosen ? R.color.energy : R.color.line));
            card.setBackground(bg);
            TextView time = new TextView(this);
            String head = getString(R.string.nav_route_time, NavText.duration(this, r.seconds), NavText.distance(this, r.meters));
            time.setText(r.best ? head + "  ·  " + getString(R.string.nav_route_best) : head);
            time.setTextAppearance(R.style.TextAppearance_Cam_Row);
            time.setTextColor(ContextCompat.getColor(this, chosen ? R.color.energy_text : R.color.text_primary));
            time.setTypeface(time.getTypeface(), android.graphics.Typeface.BOLD);
            card.addView(time);
            if (!r.label.isEmpty()) {
                TextView via = new TextView(this);
                via.setText(getString(R.string.nav_route_via, r.label));
                via.setTextAppearance(R.style.TextAppearance_Cam_Caption);
                via.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                via.setSingleLine(true);
                via.setEllipsize(android.text.TextUtils.TruncateAt.END);
                card.addView(via);
            }
            card.setOnClickListener(v -> Navigator.get().choose(r));
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            at.bottomMargin = Math.round(6 * d);
            routes.addView(card, at);
        }
    }

    /** Navigation settings, in three groups: voice and HUD, the map (colours, car colour), the iPhone. */
    private void openSettings() {
        View box = NavSettings.build(this, new NavSettings.Host() {
            @Override
            public void hudChanged() {
                showHudPreview(Navigator.get().snapshot());
            }

            @Override
            public void schemeChanged(int scheme) {
                if (painter != null) {
                    painter.applyScheme(scheme);
                }
                NavFloatWindow.get().applyScheme(scheme);
            }

            @Override
            public void carColorChanged() {
                onNavChanged(Navigator.get().snapshot());
                NavFloatWindow.get().refresh();
            }

            @Override
            public Runnable hudPlacer() {
                return NavigationActivity.this::openHudPlacer;
            }

            @Override
            public Runnable phoneSetup() {
                return NavigationActivity.this::openPhoneSetup;
            }
        });

        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.nav_settings_title)
                .setView(box)
                .setPositiveButton(R.string.nav_hud_adjust_done, null)
                .show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(Math.round(560 * getResources().getDisplayMetrics().density),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    /** The map in a floating window over other apps; this screen closes. */
    private void openFloating() {
        if (!OverlayCoordinator.canShowOverlay(this)) {
            Toast.makeText(this, R.string.nav_float_need_overlay, Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        hideKeyboard();
        NavFloatWindow.get().show(this);
        finish();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // one map at a time: the full screen takes over from the floating window
        NavFloatWindow.get().hide();
        Navigator.get().attach(this);
        Navigator.get().addListener(this);
    }

    @Override
    protected void onDestroy() {
        if (painter != null) {
            painter.release();
        }
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        Navigator.get().removeListener(this);
        Navigator.get().detach();
        super.onStop();
    }

    @Override
    public void onNavChanged(Navigator.Snapshot s) {
        boolean guiding = s.phase == Navigator.Phase.GUIDING || s.phase == Navigator.Phase.ARRIVED;
        if (guiding) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        searchBar.setVisibility(guiding ? View.GONE : View.VISIBLE);
        if (guiding) {
            results.setVisibility(View.GONE);
        }
        banner.setVisibility(guiding ? View.VISIBLE : View.GONE);
        options.setVisibility(s.phase == Navigator.Phase.IDLE || s.phase == Navigator.Phase.PREVIEW
                ? View.VISIBLE : View.GONE);
        mute.setIconResource(s.muted ? R.drawable.ic_voice_off : R.drawable.ic_voice_on);
        mute.setVisibility(guiding ? View.VISIBLE : View.GONE);
        showBanner(s);
        showSheet(s);
        showRoutes(s);
        showHudPreview(s);
        if (painter == null) {
            return;
        }
        if (s.phase != shownPhase) {
            if (s.phase == Navigator.Phase.PREVIEW) {
                painter.following = false;
                painter.fitRoute(s.route);
            } else if (s.phase == Navigator.Phase.GUIDING) {
                painter.following = true;
                recenter.setVisibility(View.GONE);
            }
            shownPhase = s.phase;
        }
        painter.draw(s);
    }

    /**
     * The HUD preview over the map before guiding: a sample card, so how it looks on the HUD can be
     * seen. Hidden while guiding (the banner shows the turn) and when its switch is off.
     */
    private void showHudPreview(Navigator.Snapshot s) {
        // while guiding the orange banner already shows the turn: no second copy over the map
        boolean guiding = s.phase == Navigator.Phase.GUIDING || s.phase == Navigator.Phase.ARRIVED;
        if (!config.isNavHudPreviewEnabled() || guiding) {
            hudPreview.setVisibility(View.GONE);
            return;
        }
        Navigator.HudLine line = Navigator.hudLine(this, s);
        boolean sample = line == null;
        hudCard.set(sample ? Navigator.sampleHudLine(this) : line);
        hudPreviewLabel.setText(sample ? R.string.nav_hud_preview_sample
                : config.isNavHudEnabled() ? R.string.nav_hud_preview : R.string.nav_hud_preview_off);
        hudPreview.setVisibility(View.VISIBLE);
    }

    private void showBanner(Navigator.Snapshot s) {
        if (s.route == null || s.progress == null) {
            return;
        }
        if (s.phase == Navigator.Phase.ARRIVED) {
            arrow.set(Maneuver.ARRIVE, Color.WHITE);
            toNext.setText(R.string.nav_arrived);
            instruction.setText(placeName(s.destination));
            instructionDetail.setVisibility(View.GONE);
            return;
        }
        int next = s.progress.next;
        arrow.set(next < 0 ? Maneuver.ARRIVE : s.route.steps.get(next).maneuver, Color.WHITE);
        toNext.setText(NavText.distance(this, s.progress.toNext));
        instruction.setText(s.rerouting ? getString(R.string.nav_rerouting) : NavText.headline(this, s.route, next));
        String detail = NavText.detail(s.route, next);
        instructionDetail.setText(detail);
        instructionDetail.setVisibility(detail.isEmpty() || s.rerouting ? View.GONE : View.VISIBLE);
    }

    private void showSheet(Navigator.Snapshot s) {
        go.setVisibility(View.GONE);
        end.setVisibility(s.phase == Navigator.Phase.IDLE ? View.GONE : View.VISIBLE);
        end.setText(s.phase == Navigator.Phase.GUIDING ? R.string.nav_end : R.string.nav_cancel);
        summary.setTextColor(ContextCompat.getColor(this, R.color.energy_text));
        switch (s.phase) {
            case IDLE:
                place.setText(R.string.nav_title);
                summary.setText("");
                status.setText(!Navigator.active(this) ? R.string.nav_need_beta
                        : !Navigator.hasKey() ? R.string.nav_error_no_key : R.string.nav_idle_hint);
                return;
            case ROUTING:
                place.setText(placeName(s.destination));
                summary.setText("");
                status.setText(s.carLat == null ? R.string.nav_waiting_gps : R.string.nav_routing);
                return;
            case FAILED:
                place.setText(placeName(s.destination));
                summary.setText("");
                status.setText(s.error == 0 ? getString(R.string.nav_error_route) : getString(s.error));
                go.setText(R.string.nav_retry);
                go.setVisibility(View.VISIBLE);
                return;
            default:
                break;
        }
        place.setText(placeName(s.destination));
        double meters = s.progress != null ? s.progress.remainingMeters : s.route == null ? 0 : s.route.meters;
        double seconds = s.progress != null ? s.progress.remainingSeconds : s.route == null ? 0 : s.route.seconds;
        if (s.phase == Navigator.Phase.ARRIVED) {
            summary.setText(R.string.nav_arrived);
        } else {
            summary.setText(getString(R.string.nav_summary, NavText.duration(this, seconds),
                    NavText.distance(this, meters), NavText.eta(this, seconds)));
        }
        status.setText(batteryLine(s.battery));
        if (s.battery != null && s.battery.level != Arrival.Level.OK) {
            status.setTextColor(ContextCompat.getColor(this,
                    s.battery.level == Arrival.Level.SHORT ? R.color.recording : R.color.energy));
        } else {
            status.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        }
        if (s.phase == Navigator.Phase.PREVIEW) {
            go.setText(R.string.nav_start);
            go.setVisibility(View.VISIBLE);
        }
    }

    private String batteryLine(Arrival.Estimate e) {
        return NavText.battery(this, e);
    }

    private String placeName(DestinationLink.Place p) {
        return NavMapPainter.placeName(p);
    }

    private void clearMap() {
        if (painter == null) {
            return;
        }
        recenter.setVisibility(View.GONE);
        painter.recenter(Navigator.get().snapshot());
    }

    // ---- search ----

    private void runSearch() {
        String q = search.getText().toString().trim();
        if (q.isEmpty()) {
            return;
        }
        hideKeyboard();
        results.removeAllViews();
        results.addView(row(getString(R.string.nav_searching), null, null));
        results.setVisibility(View.VISIBLE);
        Navigator.get().search(this, q, (found, error) -> {
            if (isFinishing()) {
                return;
            }
            results.removeAllViews();
            if (found == null) {
                results.addView(row(getString(error), null, null));
                return;
            }
            if (found.isEmpty()) {
                results.addView(row(getString(R.string.nav_no_results), null, null));
                return;
            }
            for (int i = 0; i < Math.min(6, found.size()); i++) {
                TomTomJson.Found f = found.get(i);
                results.addView(row(f.place.name, f.address, () -> choose(f.place)));
            }
        });
    }

    /** Offers the place last shared from the iPhone (Battery at arrival), if there is one. */
    private void showSharedPlace() {
        if (Navigator.get().isGuiding() || results.getVisibility() == View.VISIBLE) {
            return;
        }
        DestinationLink.Place shared = Trip.get().status().destination;
        if (shared == null) {
            return;
        }
        results.removeAllViews();
        results.addView(row(getString(R.string.nav_shared, placeName(shared)), getString(R.string.nav_shared_sub),
                () -> choose(shared)));
        results.setVisibility(View.VISIBLE);
    }

    private void choose(DestinationLink.Place p) {
        hideKeyboard();
        search.clearFocus();
        results.setVisibility(View.GONE);
        Navigator.get().preview(this, p);
    }

    private View row(String title, String sub, Runnable onTap) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(14 * getResources().getDisplayMetrics().density);
        row.setPadding(pad + pad / 2, pad / 2 + 2, pad, pad / 2 + 2);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextAppearance(R.style.TextAppearance_Cam_Row);
        t.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView a = new TextView(this);
            a.setText(sub);
            a.setTextAppearance(R.style.TextAppearance_Cam_Caption);
            a.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
            a.setSingleLine(true);
            a.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(a);
        }
        if (onTap != null) {
            android.util.TypedValue ripple = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
            row.setBackgroundResource(ripple.resourceId);
            row.setOnClickListener(v -> onTap.run());
        }
        return row;
    }

    private void hideKeyboard() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) {
            imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        }
    }
}
