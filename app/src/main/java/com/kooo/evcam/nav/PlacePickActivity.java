package com.kooo.evcam.nav;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.telemetry.Telemetry;
import com.kooo.evcam.telemetry.VehicleState;
import com.kooo.evcam.trip.DestinationLink;
import com.kooo.evcam.ui.Notice;

import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.Style;
import org.maplibre.android.maps.SupportMapFragment;

import java.util.List;
import java.util.Locale;

/**
 * Pick a place on the map and save it, for Automation (Arrive at, Leave, Navigate to). The pin
 * stays in the middle: move the map until it sits on the place, or tap the map to bring a spot
 * there. Opens on the car's position, else the trip's destination, else the last saved place.
 * Leaves the trip alone. Returns the saved place's id ({@link #placeId}).
 */
public class PlacePickActivity extends AppCompatActivity {

    private static final String EXTRA_PLACE = "place_id";
    private static final String TELEMETRY_USER = "place-pick";
    /** Close enough to tell one house from the next. */
    private static final double ZOOM = 16.5;
    /** Bangkok, when nothing better is known. */
    private static final LatLng FALLBACK = new LatLng(13.7563, 100.5018);

    private MapLibreMap map;
    private TextView where;

    /** Opens the picker; the result carries the saved place ({@link #placeId}). */
    public static Intent intent(Context context) {
        return new Intent(context, PlacePickActivity.class);
    }

    /** The saved place's id from the result, or -1 when nothing was saved. */
    public static int placeId(int resultCode, Intent data) {
        return resultCode == Activity.RESULT_OK && data != null ? data.getIntExtra(EXTRA_PLACE, -1) : -1;
    }

    /** The picker needs TomTom's map. */
    public static boolean available() {
        return Navigator.hasKey();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppConfig config = new AppConfig(this);
        boolean dark = NavMapPainter.isDark(this, config.getNavMapScheme());
        getDelegate().setLocalNightMode(dark ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_place_pick);
        where = findViewById(R.id.pick_where);
        findViewById(R.id.pick_close).setOnClickListener(v -> finish());
        findViewById(R.id.pick_here).setOnClickListener(v -> {
            LatLng car = carPosition();
            if (car == null) {
                Notice.makeText(this, R.string.place_no_fix, Toast.LENGTH_LONG).show();
            } else if (map != null) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(car, ZOOM));
            }
        });
        findViewById(R.id.pick_save).setOnClickListener(v -> save());
        if (!available()) {
            findViewById(R.id.pick_no_map).setVisibility(View.VISIBLE);
            findViewById(R.id.pick_pin).setVisibility(View.GONE);
            findViewById(R.id.pick_save).setEnabled(false);
            return;
        }
        NavMap.start(this);
        SupportMapFragment fragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.pick_map);
        if (fragment == null) {
            fragment = SupportMapFragment.newInstance(NavMap.options(this));
            getSupportFragmentManager().beginTransaction().replace(R.id.pick_map, fragment).commitNow();
        }
        fragment.getMapAsync(m -> {
            map = m;
            map.setStyle(new Style.Builder().fromUri(NavMap.styleUrl(dark)));
            map.getUiSettings().setCompassEnabled(false);
            map.getUiSettings().setRotateGesturesEnabled(false);
            map.getUiSettings().setTiltGesturesEnabled(false);
            map.setCameraPosition(new CameraPosition.Builder().target(start()).zoom(ZOOM).build());
            map.addOnCameraIdleListener(this::showWhere);
            map.addOnMapClickListener(at -> {
                map.animateCamera(CameraUpdateFactory.newLatLng(at));
                return true;
            });
            showWhere();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        Telemetry.get().acquire(this, TELEMETRY_USER);
    }

    @Override
    protected void onStop() {
        Telemetry.get().release(TELEMETRY_USER);
        super.onStop();
    }

    private LatLng start() {
        LatLng car = carPosition();
        if (car != null) {
            return car;
        }
        DestinationLink.Place to = Navigator.get().snapshot().destination;
        if (to != null) {
            return new LatLng(to.lat, to.lng);
        }
        List<SavedPlaces.Place> places = SavedPlaces.load(this);
        if (!places.isEmpty()) {
            SavedPlaces.Place last = places.get(places.size() - 1);
            return new LatLng(last.lat, last.lng);
        }
        return FALLBACK;
    }

    private static LatLng carPosition() {
        VehicleState car = Telemetry.get().latest();
        return car == null || car.latitude == null || car.longitude == null ? null
                : new LatLng(car.latitude, car.longitude);
    }

    private void showWhere() {
        if (map == null) {
            return;
        }
        LatLng at = map.getCameraPosition().target;
        if (at != null) {
            where.setText(String.format(Locale.US, "%.5f, %.5f", at.getLatitude(), at.getLongitude()));
        }
    }

    private void save() {
        if (map == null || map.getCameraPosition().target == null) {
            return;
        }
        LatLng at = map.getCameraPosition().target;
        SavePlaceDialog.ask(this, at.getLatitude(), at.getLongitude(), "", place -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_PLACE, place.id));
            finish();
        });
    }
}
