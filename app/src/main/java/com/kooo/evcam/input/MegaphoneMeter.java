package com.kooo.evcam.input;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.kooo.evcam.R;

/** Overlay while hold-to-speak is open. Not focusable, so the key-up still stops the mic. */
final class MegaphoneMeter {

    private final Context context;
    private View root;
    private ProgressBar bar;
    private TextView number;

    MegaphoneMeter(Context context) {
        this.context = context;
    }

    void show() {
        if (root != null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            return;
        }
        WindowManager windows = context.getSystemService(WindowManager.class);
        if (windows == null) {
            return;
        }
        try {
            Context themed = new ContextThemeWrapper(context, R.style.Theme_Cam);
            View inflated = LayoutInflater.from(themed).inflate(R.layout.overlay_megaphone, null);
            ProgressBar meter = inflated.findViewById(R.id.megaphone_bar);
            TextView label = inflated.findViewById(R.id.megaphone_level);
            label.setText(context.getString(R.string.megaphone_level, 0));
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.CENTER;
            windows.addView(inflated, params);
            root = inflated;
            bar = meter;
            number = label;
        } catch (RuntimeException ignored) {
            root = null;
        }
    }

    void onPercent(int percent) {
        if (bar == null || number == null) {
            return;
        }
        int clamped = Math.max(0, Math.min(100, percent));
        bar.setProgress(clamped);
        number.setText(context.getString(R.string.megaphone_level, clamped));
    }

    void hide() {
        if (root == null) {
            return;
        }
        WindowManager windows = context.getSystemService(WindowManager.class);
        try {
            if (windows != null) {
                windows.removeView(root);
            }
        } catch (RuntimeException ignored) {
            // already detached
        }
        root = null;
        bar = null;
        number = null;
    }
}
