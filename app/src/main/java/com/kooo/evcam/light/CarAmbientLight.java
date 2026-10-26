package com.kooo.evcam.light;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.telemetry.CarAmbientLab;

import java.util.Locale;

/**
 * The car's own top-zone ambient light as a light in a group, set through ECARX
 * ({@link CarAmbientLab#TOPZONES} on/off and {@link CarAmbientLab#TOPZONES_COLOR} as
 * {@code 0xRRGGBB}). It can only be on, off or one colour: an animation shows its colour, and
 * brightness 0 or Off turns it off.
 *
 * <p>The car is slower to take a value than a Bluetooth light, and the speed blend sends a new
 * colour every few km/h: the newest look wins and at most one value goes to the car every
 * {@link #WRITE_EVERY_MS}.</p>
 */
final class CarAmbientLight implements LightLink {

    /** Stands in for a Bluetooth address in a group's list of lights. */
    static final String ADDRESS = "car:ambient";

    private static final String TAG = "CarAmbientLight";
    private static final long WRITE_EVERY_MS = 250L;
    private static final int OFF = 0;
    private static final int ON = 1;

    private final Context app;
    private final MoodLight.Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("car-ambient-light");
    private final Handler h;
    private final Runnable flush = this::flush;

    // Worker thread only
    private Integer powerId;
    private Integer colorId;
    /** What the car was last set to (or read as); null when not known. */
    private Integer sentPower;
    private Integer sentColor;
    private Integer wantPower;
    private Integer wantColor;
    private long lastWrite;
    private volatile boolean released;

    CarAmbientLight(Context context, MoodLight.Listener listener) {
        this.app = context.getApplicationContext();
        this.listener = listener;
        thread.start();
        h = new Handler(thread.getLooper());
    }

    static boolean isCar(String address) {
        return ADDRESS.equals(address);
    }

    @Override
    public void connect(String address) {
        h.post(() -> {
            main.post(() -> {
                if (!released) {
                    listener.onLightConnecting();
                }
            });
            powerId = CarAmbientLab.functionId(CarAmbientLab.TOPZONES);
            colorId = CarAmbientLab.functionId(CarAmbientLab.TOPZONES_COLOR);
            if (powerId == null || colorId == null) {
                AppLog.w(TAG, "no top-zone ambient light functions on this head unit");
                main.post(() -> {
                    if (!released) {
                        listener.onLightDown(MoodLight.FAIL_NOT_A_LIGHT, 0);
                    }
                });
                return;
            }
            sentPower = CarAmbientLab.value(app, powerId);
            sentColor = CarAmbientLab.value(app, colorId);
            BlackBox.note(String.format(Locale.US,
                    "Car ambient light: on/off 0x%08X %s reads %s, colour 0x%08X %s reads %s",
                    powerId, CarAmbientLab.support(app, powerId), sentPower,
                    colorId, CarAmbientLab.support(app, colorId), sentColor));
            main.post(() -> {
                if (!released) {
                    listener.onLightReady(null);
                }
            });
        });
    }

    @Override
    public void power(boolean on) {
        h.post(() -> {
            wantPower = on ? ON : OFF;
            schedule();
        });
    }

    @Override
    public void color(int rgb, int brightness) {
        h.post(() -> {
            wantColor = rgb & 0xFFFFFF;
            if (brightness <= 0) {
                wantPower = OFF;
            }
            schedule();
        });
    }

    @Override
    public void show(LightLook look) {
        LightLook l = look.copy();
        h.post(() -> {
            if (l.effect() == LightProtocol.Effect.OFF || l.brightness <= 0) {
                wantPower = OFF;
            } else {
                wantPower = ON;
                wantColor = l.color & 0xFFFFFF;
            }
            schedule();
        });
    }

    @Override
    public void release() {
        h.post(() -> {
            h.removeCallbacks(flush);
            thread.quitSafely();
        });
        released = true;
    }

    private void schedule() {
        if (powerId == null || released) {
            return;
        }
        h.removeCallbacks(flush);
        long wait = lastWrite + WRITE_EVERY_MS - SystemClock.uptimeMillis();
        h.postDelayed(flush, Math.max(0, wait));
    }

    /** One value per turn: power on before the colour (a colour sent while off may be lost), off on its own. */
    private void flush() {
        if (released) {
            return;
        }
        Integer function = null;
        int value = 0;
        if (wantPower != null && !wantPower.equals(sentPower)) {
            function = powerId;
            value = wantPower;
            sentPower = wantPower;
        } else if (wantColor != null && !wantColor.equals(sentColor) && !Integer.valueOf(OFF).equals(sentPower)) {
            function = colorId;
            value = wantColor;
            sentColor = wantColor;
        }
        if (function == null) {
            return;
        }
        lastWrite = SystemClock.uptimeMillis();
        String failed = CarAmbientLab.write(app, function, value);
        if (failed != null) {
            AppLog.w(TAG, String.format(Locale.US, "set 0x%08X = %d: %s", function, value, failed));
        }
        // Whatever is still wanted goes in the next turn
        schedule();
    }
}
