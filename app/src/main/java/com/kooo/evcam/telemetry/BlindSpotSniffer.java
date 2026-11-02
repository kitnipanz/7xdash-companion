package com.kooo.evcam.telemetry;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.hud.HudNoticeWindow;
import com.kooo.evcam.screen.ScreenState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Experimental: while the screen is on, watches every car setting whose name looks blind-spot
 * related and reports each change in the log, the black box and on the HUD. Lets the driver notice
 * "something just changed while that car passed" without sitting in the finder
 * ({@code BlindSpotFinderActivity}), then teach it there.
 *
 * <p>Off by default ({@link AppConfig#isBlindSpotSnifferEnabled}). The HUD card shows whatever the
 * HUD notifications switch says, because the driver asked for it here.</p>
 */
public final class BlindSpotSniffer {

    private static final String TAG = "BlindSpotSniffer";
    /** Car setting names that look like blind-spot ones. Shared with the finder. */
    public static final Pattern NAME = Pattern.compile(
            "BSD|BSW|BSI|BLIND|BLIS|LCA|LANE_CHANGE|DOW|RCTA|RCW|SIDE_WARN|SIDE_RADAR|APPROACH",
            Pattern.CASE_INSENSITIVE);
    static final long POLL_MS = 300L;
    /** The same setting shows on the HUD at most this often; every change still goes to the log. */
    static final long HUD_EVERY_MS = 2_000L;

    private static BlindSpotSniffer installed;

    private final Context app;
    private final AppConfig config;
    private final HandlerThread thread = new HandlerThread("blind-spot-sniffer");
    private final Handler work;
    private List<CarAmbientLab.Constant> watched;
    private String[] before;
    private boolean running;
    private final Map<Integer, Long> hudAt = new HashMap<>();

    private final Runnable poll = this::poll;

    private BlindSpotSniffer(Context context) {
        app = context.getApplicationContext();
        config = new AppConfig(app);
        thread.start();
        work = new Handler(thread.getLooper());
    }

    /** Application start. Repeat calls do nothing. */
    public static void install(Context context) {
        if (installed != null) {
            return;
        }
        installed = new BlindSpotSniffer(context);
        ScreenState.addListener(new ScreenState.Listener() {
            @Override
            public void onScreenOff() {
                applyConfig();
            }

            @Override
            public void onScreenOn() {
                applyConfig();
            }
        });
        applyConfig();
    }

    /** The setting or the screen changed: start or stop watching. */
    public static void applyConfig() {
        BlindSpotSniffer s = installed;
        if (s != null) {
            s.work.post(s::reconsider);
        }
    }

    private void reconsider() {
        boolean want = config.isBlindSpotSnifferEnabled() && !ScreenState.dark();
        if (want == running) {
            return;
        }
        running = want;
        work.removeCallbacks(poll);
        before = null;
        if (want) {
            work.post(poll);
        } else {
            AppLog.i(TAG, "stopped");
        }
    }

    private void poll() {
        if (!running) {
            return;
        }
        if (watched == null) {
            watched = find();
            if (watched.isEmpty()) {
                // nothing readable yet (car interface still connecting): look again later
                watched = null;
                work.postDelayed(poll, 10_000L);
                return;
            }
        }
        String[] now = CarAmbientLab.readAll(app, watched);
        if (now != null) {
            if (before == null) {
                StringBuilder all = new StringBuilder();
                for (int i = 0; i < watched.size(); i++) {
                    all.append(i == 0 ? "" : ", ").append(watched.get(i).name).append('=').append(now[i]);
                }
                AppLog.i(TAG, "watching " + watched.size() + ": " + all);
                BlackBox.note("盲区信号试验：开始看 " + watched.size() + " 个设置：" + all);
            } else {
                for (int i = 0; i < watched.size(); i++) {
                    if (!Objects.equals(before[i], now[i])) {
                        report(watched.get(i), before[i], now[i]);
                    }
                }
            }
            before = now;
        }
        work.postDelayed(poll, POLL_MS);
    }

    private List<CarAmbientLab.Constant> find() {
        CarAmbientLab.Scan scan = CarAmbientLab.scan(app);
        List<CarAmbientLab.Constant> out = new ArrayList<>();
        if (scan.carReady) {
            for (CarAmbientLab.Constant k : scan.functions) {
                if (NAME.matcher(k.name).find()) {
                    out.add(k);
                }
            }
        }
        return out;
    }

    private void report(CarAmbientLab.Constant k, String was, String now) {
        VehicleState s = Telemetry.get().latest();
        String turn = s.turnSignal == null ? "?" : s.turnSignal == VehicleState.TURN_LEFT ? "L"
                : s.turnSignal == VehicleState.TURN_RIGHT ? "R" : "-";
        String line = k.name + " " + k.hex() + ": " + was + " → " + now;
        AppLog.i(TAG, line + " (turn " + turn + ", " + s.speedKmh + " km/h)");
        BlackBox.noteImportant("盲区信号试验：" + line + "（转向灯 " + turn + "，车速 " + s.speedKmh + "）");
        long at = SystemClock.elapsedRealtime();
        Long last = hudAt.get(k.value);
        if (last == null || at - last >= HUD_EVERY_MS) {
            hudAt.put(k.value, at);
            HudNoticeWindow.show(app, "7xDash", "BSD? " + shortName(k.name) + " " + was + "→" + now, k.hex());
        }
    }

    /** The name without its long ECARX prefix, so it fits on the HUD. */
    static String shortName(String name) {
        int cut = name.indexOf("FUNC_");
        return cut >= 0 ? name.substring(cut + 5) : name;
    }
}
