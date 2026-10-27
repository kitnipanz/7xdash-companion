package com.kooo.evcam.input;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.telemetry.CarAmbientLab;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Experiment: what does each of the car's buttons send? While listening, three things are
 * watched at once and every press becomes one {@link Heard}:
 * <ul>
 *   <li>key events, from the accessibility service ({@link ShortcutKeys#dispatch}) or the lab's
 *   own window: key code, scan code and the input device;</li>
 *   <li>ECARX car settings that change (the Car lights lab's Watch, all functions);</li>
 *   <li>key-looking lines of the system log, when the car lets 7xDash read it (READ_LOGS).</li>
 * </ul>
 * A heard key can be pressed again ({@link com.kooo.evcam.audio.CarVolume#key}) and a heard
 * setting sent again ({@link CarAmbientLab#set}), to see whether 7xDash can do what the button
 * does. Everything heard goes to the black box.
 */
public final class ButtonLab {

    public enum Source { KEY, CAR, LOG }

    /** One thing heard. */
    public static final class Heard {
        public final Source source;
        public final long at;
        public final String title;
        public final String detail;
        /** KEY: the key code (0 when the button only has a scan code). */
        public final int keyCode;
        /** CAR: the setting that changed, with its value before and now. */
        public final CarAmbientLab.Constant setting;
        public final String before;
        public final String now;

        Heard(Source source, String title, String detail, int keyCode,
              CarAmbientLab.Constant setting, String before, String now) {
            this.source = source;
            this.at = System.currentTimeMillis();
            this.title = title;
            this.detail = detail;
            this.keyCode = keyCode;
            this.setting = setting;
            this.before = before;
            this.now = now;
        }
    }

    public interface Listener {
        /** Main thread. */
        void heard(Heard h);

        /** Main thread: what the listening is doing ("watching 812 car settings…"). */
        void status(String line);
    }

    private static final String TAG = "ButtonLab";
    private static final long SETTLE_MS = 3000L;
    private static final long PASS_GAP_MS = 300L;
    /** Log lines this close together are one press. */
    private static final long LOG_GROUP_MS = 600L;
    private static final int LOG_GROUP_MAX = 12;
    private static final Pattern LOG_KEY = Pattern.compile(
            "(?i)keyevent|keycode|key_?code|hw_?key|onkey|steering|swc|knob|rotary|volume_?(up|down)|"
                    + "inputevent|\\bkey(down|up)\\b|interceptkey");

    private static volatile ButtonLab live;

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private long lastKeyTime = -1;
    private int lastKeyCode = -1;
    private java.lang.Process logcat;

    private ButtonLab(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /** Starts listening; stops any earlier one. */
    public static ButtonLab start(Context context, Listener listener) {
        stopAll();
        ButtonLab lab = new ButtonLab(context, listener);
        lab.running = true;
        live = lab;
        BlackBox.note("button-lab: listening");
        new Thread(lab::watchCar, "button-lab-car").start();
        new Thread(lab::watchLog, "button-lab-log").start();
        return lab;
    }

    public static void stopAll() {
        ButtonLab lab = live;
        live = null;
        if (lab != null) {
            lab.stop();
        }
    }

    public static boolean isListening() {
        return live != null;
    }

    private void stop() {
        running = false;
        java.lang.Process p = logcat;
        if (p != null) {
            p.destroy();
        }
        BlackBox.note("button-lab: stopped");
    }

    // ================================================================= keys

    /**
     * Every key event the app sees, before anything uses it. Called by {@link ShortcutKeys} (the
     * accessibility service) and by the lab's own window; the same press seen twice counts once.
     */
    public static void onKey(KeyEvent event) {
        ButtonLab lab = live;
        if (lab != null && event != null && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0) {
            lab.key(event);
        }
    }

    private synchronized void key(KeyEvent e) {
        if (e.getEventTime() == lastKeyTime && e.getKeyCode() == lastKeyCode) {
            return;
        }
        lastKeyTime = e.getEventTime();
        lastKeyCode = e.getKeyCode();
        InputDevice device = e.getDevice();
        String name = device == null || device.getName() == null ? "no device" : device.getName();
        String title = e.getKeyCode() == KeyEvent.KEYCODE_UNKNOWN
                ? "scan " + e.getScanCode() : KeyEvent.keyCodeToString(e.getKeyCode());
        String detail = "key " + e.getKeyCode() + " · scan " + e.getScanCode() + " · " + name
                + " (id " + e.getDeviceId() + ", source 0x" + Integer.toHexString(e.getSource()) + ")";
        post(new Heard(Source.KEY, title, detail, e.getKeyCode(), null, null, null));
    }

    // ================================================================= car settings

    private void watchCar() {
        status("Finding the car's settings…");
        CarAmbientLab.Scan scan = CarAmbientLab.scan(context);
        List<CarAmbientLab.Constant> list = scan.functions;
        if (!scan.carReady || list.isEmpty()) {
            status("Car settings: not available (" + scan.status + ") · keys and log still listening");
            return;
        }
        String[] base = CarAmbientLab.readAll(context, list);
        if (base == null) {
            status("Car settings: no car function interface · keys and log still listening");
            return;
        }
        Set<Integer> noisy = new HashSet<>();
        long settleUntil = SystemClock.uptimeMillis() + SETTLE_MS;
        boolean told = false;
        status("Getting ready: " + list.size() + " car settings…");
        while (running) {
            sleep(PASS_GAP_MS);
            String[] now = CarAmbientLab.readAll(context, list);
            if (now == null || !running) {
                continue;
            }
            boolean settling = SystemClock.uptimeMillis() < settleUntil;
            for (int i = 0; i < list.size(); i++) {
                if (java.util.Objects.equals(base[i], now[i])) {
                    continue;
                }
                CarAmbientLab.Constant k = list.get(i);
                if (settling) {
                    noisy.add(k.value);
                } else if (!noisy.contains(k.value)) {
                    k.before = base[i];
                    k.current = now[i];
                    k.watched = true;
                    post(new Heard(Source.CAR, k.owner + "." + k.name,
                            k.hex() + " · was " + base[i] + " · now " + now[i], 0, k, base[i], now[i]));
                }
                base[i] = now[i];
            }
            if (!settling && !told) {
                told = true;
                status("Listening: keys · " + list.size() + " car settings (" + noisy.size()
                        + " change by themselves, ignored) · " + logState());
            }
        }
    }

    // ================================================================= system log

    private String logState() {
        return context.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED
                ? "system log" : "system log: only 7xDash's own (READ_LOGS not granted)";
    }

    private void watchLog() {
        BufferedReader in = null;
        String mine = " " + Process.myPid() + " ";
        try {
            logcat = Runtime.getRuntime().exec(new String[]{"logcat", "-v", "threadtime", "-T", "1"});
            in = new BufferedReader(new InputStreamReader(logcat.getInputStream()));
            List<String> group = new ArrayList<>();
            String line;
            while (running && (line = in.readLine()) != null) {
                // Touches on the screen are input events too; they are not buttons
                if (line.contains(mine) || line.contains("MotionEvent") || !LOG_KEY.matcher(line).find()) {
                    continue;
                }
                synchronized (group) {
                    if (group.isEmpty()) {
                        // The first line of a press: hand the group over once it has gone quiet
                        main.postDelayed(() -> {
                            List<String> done;
                            synchronized (group) {
                                done = new ArrayList<>(group);
                                group.clear();
                            }
                            postLog(done);
                        }, LOG_GROUP_MS);
                    }
                    if (group.size() < LOG_GROUP_MAX) {
                        group.add(trim(line));
                    }
                }
            }
        } catch (Exception e) {
            if (running) {
                AppLog.w(TAG, "logcat: " + e);
            }
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // closing anyway
                }
            }
        }
    }

    private void postLog(List<String> lines) {
        if (lines.isEmpty() || !running) {
            return;
        }
        post(new Heard(Source.LOG, lines.size() + " log line" + (lines.size() == 1 ? "" : "s"),
                String.join("\n", lines), 0, null, null, null));
    }

    /** "10-27 17:33:26.311  546  952 D MediaSessionService: …" without the date and ids. */
    private static String trim(String line) {
        String s = line.replaceFirst("^\\S+\\s+(\\S+)\\s+\\d+\\s+\\d+\\s+", "$1 ");
        return s.length() > 220 ? s.substring(0, 220) + "…" : s;
    }

    // ================================================================= helpers

    private void post(Heard h) {
        BlackBox.note("button-lab: " + h.source + " " + h.title + " · " + h.detail.replace('\n', ' '));
        main.post(() -> {
            if (live == this) {
                listener.heard(h);
            }
        });
    }

    private void status(String line) {
        main.post(() -> {
            if (live == this) {
                listener.status(line);
            }
        });
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
