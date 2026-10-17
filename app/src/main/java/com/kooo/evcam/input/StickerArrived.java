package com.kooo.evcam.input;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.kooo.evcam.R;

import java.util.HashMap;
import java.util.Map;

/** Card over whatever is on screen, naming the sticker that just connected. */
final class StickerArrived {

    private static final long AGAIN_MS = 60000L;
    private static final long SHOW_MS = 4000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Long> LAST = new HashMap<>();
    private static final Runnable HIDE = StickerArrived::hide;

    private static View root;

    private StickerArrived() {
    }

    static void show(Context context, String address) {
        if (context == null || address == null || address.isEmpty()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        Long last = LAST.get(address);
        if (last != null && now - last < AGAIN_MS) {
            return;
        }
        LAST.put(address, now);
        Context app = context.getApplicationContext();
        String which = which(app, address);
        present(app, which);
    }

    private static void present(Context context, String which) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            return;
        }
        WindowManager windows = context.getSystemService(WindowManager.class);
        if (windows == null) {
            return;
        }
        hide();
        try {
            Context themed = new ContextThemeWrapper(context, R.style.Theme_Cam);
            View card = LayoutInflater.from(themed).inflate(R.layout.overlay_sticker, null);
            TextView body = card.findViewById(R.id.sticker_arrived_which);
            body.setText(which);
            card.setOnClickListener(v -> hide());
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
            windows.addView(card, params);
            root = card;
            MAIN.removeCallbacks(HIDE);
            MAIN.postDelayed(HIDE, SHOW_MS);
        } catch (RuntimeException ignored) {
            root = null;
        }
    }

    private static void hide() {
        View card = root;
        root = null;
        MAIN.removeCallbacks(HIDE);
        if (card == null) {
            return;
        }
        WindowManager windows = card.getContext().getSystemService(WindowManager.class);
        if (windows == null) {
            return;
        }
        try {
            windows.removeView(card);
        } catch (RuntimeException ignored) {
            // already gone
        }
    }

    private static String which(Context context, String address) {
        String alias = StickerNames.alias(context, address);
        if (!alias.isEmpty()) {
            return alias;
        }
        String tail = StickerFrame.tail(StickerFrame.deviceName(address));
        String name = name(context, address);
        if (name.isEmpty()) {
            return tail.isEmpty() ? address : tail;
        }
        if (tail.isEmpty()) {
            return name;
        }
        return context.getString(R.string.sticker_arrived_named, name, tail);
    }

    private static String name(Context context, String address) {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            return "";
        }
        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);
            String name = device.getName();
            return name == null ? "" : name.trim();
        } catch (IllegalArgumentException | SecurityException ignored) {
            return "";
        }
    }
}
