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
 * {@code 0xRRGGBB}), with the brightness on the car's 0 to 10 scale
 * ({@link CarAmbientLab#MAINZONES_INTENSITY}, and {@link CarAmbientLab#TOPZONES_INTENSITY} when the
 * car has it; {@link #level}). It can only be on, off or one colour: an animation shows its colour,
 * and brightness 0 or Off turns it off.
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
    /** Brightness functions the car has (main zones, top zones); empty when it has none. */
    private int[] levelIds = new int[0];
    private Integer[] sentLevels = new Integer[0];
    private Integer wantLevel;
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
            findLevels();
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
            } else {
                wantLevel = level(brightness);
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
                wantLevel = level(l.brightness);
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

    /**
     * Brightness 1 to 100 on the car's 1 to 10 scale, rounded. Never 0 while on: 0 would leave the
     * light on but dark, and brightness 0 already turns it off.
     */
    static int level(int brightness) {
        int level = Math.round(brightness * CarAmbientLab.INTENSITY_MAX / 100f);
        return Math.max(1, Math.min(CarAmbientLab.INTENSITY_MAX, level));
    }

    /** Looks up the brightness functions this car has and reads what they are at now. */
    private void findLevels() {
        java.util.List<Integer> ids = new java.util.ArrayList<>();
        StringBuilder note = new StringBuilder("Car ambient light brightness:");
        for (String name : new String[]{CarAmbientLab.MAINZONES_INTENSITY, CarAmbientLab.TOPZONES_INTENSITY}) {
            Integer id = CarAmbientLab.functionId(name);
            note.append(' ').append(name.replace("SETTING_FUNC_AMBIENCE_LIGHT_", "")).append('=');
            if (id == null) {
                note.append("missing");
                continue;
            }
            ids.add(id);
            note.append(String.format(Locale.US, "0x%08X %s reads %s", id,
                    CarAmbientLab.support(app, id), CarAmbientLab.value(app, id)));
        }
        levelIds = new int[ids.size()];
        sentLevels = new Integer[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            levelIds[i] = ids.get(i);
            sentLevels[i] = CarAmbientLab.value(app, levelIds[i]);
        }
        BlackBox.note(note.toString());
    }

    /**
     * One value per turn: power on first (a value sent while off may be lost), then the brightness,
     * then the colour; off on its own.
     */
    private void flush() {
        if (released) {
            return;
        }
        Integer function = null;
        int value = 0;
        boolean lit = !Integer.valueOf(OFF).equals(sentPower);
        int levelAt = lit && wantLevel != null ? stale(sentLevels, wantLevel) : -1;
        if (wantPower != null && !wantPower.equals(sentPower)) {
            function = powerId;
            value = wantPower;
            sentPower = wantPower;
        } else if (levelAt >= 0) {
            function = levelIds[levelAt];
            value = wantLevel;
            sentLevels[levelAt] = wantLevel;
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

    /** First brightness function not yet at {@code want}; -1 when all are. */
    static int stale(Integer[] sent, int want) {
        for (int i = 0; i < sent.length; i++) {
            if (sent[i] == null || sent[i] != want) {
                return i;
            }
        }
        return -1;
    }
}
