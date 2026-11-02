package com.kooo.evcam.trip;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.share.LocalNetwork;
import com.kooo.evcam.share.QrCode;

/**
 * Battery at arrival (beta): the estimate for the place the phone shared, and the QR code that
 * opens the phone page ({@link DestinationServer}) with the Shortcut steps and a paste box.
 */
public class ArrivalActivity extends AppCompatActivity {

    private static final long REFRESH_MS = 1_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    private AppConfig config;
    private SwitchMaterial toggle;
    private TextView place;
    private TextView percent;
    private TextView detail;
    private View clear;
    private ImageView qr;
    private TextView address;
    private String shownUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_arrival);
        config = new AppConfig(this);
        toggle = findViewById(R.id.arrival_switch);
        place = findViewById(R.id.arrival_place);
        percent = findViewById(R.id.arrival_percent);
        detail = findViewById(R.id.arrival_detail);
        clear = findViewById(R.id.arrival_clear);
        qr = findViewById(R.id.arrival_qr);
        address = findViewById(R.id.arrival_address);
        findViewById(R.id.arrival_close).setOnClickListener(v -> finish());
        toggle.setChecked(config.isArrivalEnabled());
        toggle.setOnCheckedChangeListener((b, on) -> {
            config.setArrivalEnabled(on);
            Trip.get().apply(this);
            refresh();
        });
        clear.setOnClickListener(v -> {
            Trip.get().clear();
            refresh();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    private void refresh() {
        boolean on = Trip.active(this);
        Trip.Status s = Trip.get().status();
        percent.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        clear.setVisibility(s.destination == null ? View.GONE : View.VISIBLE);
        if (!config.isBetaFeaturesEnabled()) {
            place.setText(R.string.arrival_need_beta);
            percent.setText("—");
            detail.setText("");
        } else if (!on) {
            place.setText(R.string.arrival_off);
            percent.setText("—");
            detail.setText("");
        } else if (s.destination == null) {
            place.setText(R.string.arrival_none);
            percent.setText("—");
            detail.setText(R.string.arrival_none_hint);
        } else {
            place.setText(s.destination.name.isEmpty()
                    ? getString(R.string.arrival_unnamed) : s.destination.name);
            if (s.estimate == null) {
                percent.setText("…");
                detail.setText(R.string.arrival_waiting);
            } else {
                percent.setText(ArrivalText.headline(this, s));
                detail.setText(ArrivalText.detail(this, s));
                int color = s.estimate.level == Arrival.Level.SHORT ? R.color.recording
                        : s.estimate.level == Arrival.Level.LOW ? R.color.energy : R.color.text_primary;
                percent.setTextColor(ContextCompat.getColor(this, color));
            }
        }
        showSetup(on);
    }

    private void showSetup(boolean on) {
        LocalNetwork.Endpoint host = on ? LocalNetwork.preferred(LocalNetwork.enumerate()) : null;
        String url = host == null ? null : DestinationServer.pageUrl(this, host.address);
        boolean up = Trip.get().serverUp();
        String key = url + up;
        if (key.equals(shownUrl)) {
            return;
        }
        shownUrl = key;
        if (url == null) {
            qr.setImageDrawable(null);
            qr.setVisibility(View.INVISIBLE);
            address.setText(on ? R.string.arrival_no_network : R.string.arrival_off_setup);
            return;
        }
        int size = Math.round(220 * getResources().getDisplayMetrics().density);
        Bitmap bitmap = QrCode.encode(url, size);
        qr.setImageBitmap(bitmap);
        qr.setVisibility(View.VISIBLE);
        address.setText(up ? DestinationServer.sendUrl(this, host.address)
                : getString(R.string.arrival_port_failed, DestinationServer.PORT));
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
