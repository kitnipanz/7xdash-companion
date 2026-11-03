package com.kooo.evcam.nav;

import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.MapColorScheme;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.trip.Arrival;
import com.kooo.evcam.trip.DestinationLink;
import com.kooo.evcam.trip.Trip;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Navigation (beta): search a place (or take the one shared from the iPhone, or hold on the map),
 * see the route with its time, distance and battery at arrival, then Start for turn-by-turn.
 * The map is Google's (Maps SDK, needs Google Play services); without it the screen still
 * guides, just with no map. The guiding itself is {@link Navigator}'s and goes on when this closes.
 */
public class NavigationActivity extends AppCompatActivity implements Navigator.Listener {

    private static final String TAG = "NavigationActivity";
    private static final int ROUTE_BLUE = 0xFF4285F4;
    private static final int ROUTE_EDGE = 0xFF1A5BC4;

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

    private GoogleMap map;
    private Polyline routeLine;
    private Polyline routeEdge;
    private Marker car;
    private Marker pin;
    private NavRoute shownRoute;
    private DestinationLink.Place shownPin;
    /** The camera follows the car until the map is moved by hand; Re-centre brings it back. */
    private boolean following = true;
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
                following = true;
                Navigator.get().start();
            }
        });
        end.setOnClickListener(v -> {
            Navigator.get().end();
            clearMap();
        });
        mute.setOnClickListener(v -> Navigator.get().setMuted(!Navigator.get().snapshot().muted));
        recenter.setOnClickListener(v -> {
            following = true;
            recenter.setVisibility(View.GONE);
            onNavChanged(Navigator.get().snapshot());
        });
        SwitchMaterial voice = findViewById(R.id.nav_voice_switch);
        voice.setChecked(config.isNavVoiceEnabled());
        voice.setOnCheckedChangeListener((b, on) -> config.setNavVoiceEnabled(on));
        SwitchMaterial hud = findViewById(R.id.nav_hud_switch);
        hud.setChecked(config.isNavHudEnabled());
        hud.setOnCheckedChangeListener((b, on) -> config.setNavHudEnabled(on));

        setUpMap();
    }

    private void setUpMap() {
        int play = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this);
        if (play != ConnectionResult.SUCCESS || !Navigator.hasKey()) {
            AppLog.w(TAG, "no map: Play services " + play + ", key " + Navigator.hasKey());
            findViewById(R.id.nav_no_map).setVisibility(View.VISIBLE);
            return;
        }
        SupportMapFragment fragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.nav_map);
        if (fragment == null) {
            fragment = SupportMapFragment.newInstance();
            getSupportFragmentManager().beginTransaction().replace(R.id.nav_map, fragment).commitNow();
        }
        fragment.getMapAsync(this::onMap);
    }

    private void onMap(GoogleMap m) {
        map = m;
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        map.setMapColorScheme(night ? MapColorScheme.DARK : MapColorScheme.LIGHT);
        map.setTrafficEnabled(true);
        map.setBuildingsEnabled(true);
        map.getUiSettings().setMapToolbarEnabled(false);
        map.getUiSettings().setCompassEnabled(true);
        map.getUiSettings().setZoomControlsEnabled(false);
        // the cards cover the left: keep Google's logo and the compass clear of them
        int side = Math.round(540 * getResources().getDisplayMetrics().density);
        map.setPadding(side, 0, 0, 0);
        map.setOnCameraMoveStartedListener(reason -> {
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                following = false;
                recenter.setVisibility(View.VISIBLE);
            }
        });
        map.setOnMapLongClickListener(at -> {
            if (!Navigator.get().isGuiding()) {
                Navigator.get().preview(this, new DestinationLink.Place(at.latitude, at.longitude,
                        getString(R.string.nav_dropped_pin)));
                following = false;
            }
        });
        Navigator.Snapshot s = Navigator.get().snapshot();
        if (s.carLat != null) {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(s.carLat, s.carLng), 15));
        }
        shownRoute = null;
        shownPin = null;
        onNavChanged(s);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Navigator.get().attach(this);
        Navigator.get().addListener(this);
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
        drawMap(s);
        if (s.phase != shownPhase) {
            if (s.phase == Navigator.Phase.PREVIEW) {
                following = false;
                fitRoute(s.route);
            } else if (s.phase == Navigator.Phase.GUIDING) {
                following = true;
                recenter.setVisibility(View.GONE);
            }
            shownPhase = s.phase;
        }
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
        if (e == null) {
            return getString(R.string.nav_battery_unknown);
        }
        if (e.level == Arrival.Level.SHORT) {
            return getString(R.string.nav_battery_short, (int) Math.ceil(-e.spareKm));
        }
        return getString(R.string.nav_battery, Math.round(e.percent));
    }

    private String placeName(DestinationLink.Place p) {
        if (p == null) {
            return "";
        }
        return p.name.isEmpty() ? String.format(Locale.US, "%.5f, %.5f", p.lat, p.lng) : p.name;
    }

    private void drawMap(Navigator.Snapshot s) {
        if (map == null) {
            return;
        }
        if (s.route != shownRoute) {
            shownRoute = s.route;
            if (routeLine != null) {
                routeLine.remove();
                routeEdge.remove();
                routeLine = null;
                routeEdge = null;
            }
            if (s.route != null) {
                List<LatLng> pts = new ArrayList<>(s.route.pointCount());
                for (int i = 0; i < s.route.pointCount(); i++) {
                    pts.add(new LatLng(s.route.lat(i), s.route.lng(i)));
                }
                float d = getResources().getDisplayMetrics().density;
                routeEdge = map.addPolyline(new PolylineOptions().addAll(pts).color(ROUTE_EDGE).width(11 * d).zIndex(1));
                routeLine = map.addPolyline(new PolylineOptions().addAll(pts).color(ROUTE_BLUE).width(7 * d).zIndex(2));
            }
        }
        if (s.destination != shownPin) {
            shownPin = s.destination;
            if (pin != null) {
                pin.remove();
                pin = null;
            }
            if (s.destination != null) {
                pin = map.addMarker(new MarkerOptions().position(new LatLng(s.destination.lat, s.destination.lng))
                        .title(placeName(s.destination)));
            }
        }
        if (s.carLat == null) {
            return;
        }
        boolean onRoute = s.progress != null && !s.progress.offRoute && s.phase == Navigator.Phase.GUIDING;
        LatLng at = onRoute ? new LatLng(s.progress.lat, s.progress.lng) : new LatLng(s.carLat, s.carLng);
        float bearing = s.carBearing == null ? 0 : s.carBearing.floatValue();
        if (car == null) {
            car = map.addMarker(new MarkerOptions().position(at).flat(true).anchor(0.5f, 0.5f).zIndex(3)
                    .icon(BitmapDescriptorFactory.fromBitmap(carIcon())));
        }
        if (car != null) {
            car.setPosition(at);
            car.setRotation(bearing);
        }
        if (following && s.phase == Navigator.Phase.GUIDING) {
            map.animateCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder()
                    .target(at).bearing(bearing).tilt(55).zoom(zoomFor(s)).build()), 900, null);
        } else if (following && s.phase == Navigator.Phase.IDLE) {
            map.animateCamera(CameraUpdateFactory.newLatLng(at));
        }
    }

    /** Closer in town and before a turn, further out on a long straight. */
    private static float zoomFor(Navigator.Snapshot s) {
        double toNext = s.progress == null ? 1_000 : s.progress.toNext;
        return toNext < 300 ? 17.5f : toNext < 2_000 ? 16.5f : 15.5f;
    }

    private void fitRoute(NavRoute r) {
        if (map == null || r == null || r.pointCount() < 2) {
            return;
        }
        LatLngBounds.Builder b = LatLngBounds.builder();
        for (int i = 0; i < r.pointCount(); i++) {
            b.include(new LatLng(r.lat(i), r.lng(i)));
        }
        int pad = Math.round(80 * getResources().getDisplayMetrics().density);
        try {
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), pad));
        } catch (IllegalStateException e) {
            // map not laid out yet
        }
    }

    private void clearMap() {
        if (map == null) {
            return;
        }
        following = true;
        recenter.setVisibility(View.GONE);
        onNavChanged(Navigator.get().snapshot());
    }

    /** The car: a blue arrow with a white edge, like Google's. */
    private Bitmap carIcon() {
        int size = Math.round(44 * getResources().getDisplayMetrics().density);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        Path p = new Path();
        p.moveTo(size * 0.5f, size * 0.08f);
        p.lineTo(size * 0.86f, size * 0.9f);
        p.lineTo(size * 0.5f, size * 0.7f);
        p.lineTo(size * 0.14f, size * 0.9f);
        p.close();
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(ROUTE_BLUE);
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setColor(Color.WHITE);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(size * 0.07f);
        edge.setStrokeJoin(Paint.Join.ROUND);
        c.drawPath(p, edge);
        c.drawPath(p, fill);
        return bitmap;
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
                GoogleJson.Found f = found.get(i);
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
