package com.kooo.evcam.automation;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.widget.Toast;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.input.ShortcutPerformer;
import com.kooo.evcam.light.GearLight;
import com.kooo.evcam.nav.Navigator;
import com.kooo.evcam.nav.SavedPlaces;
import com.kooo.evcam.overlay.DimOverlayService;
import com.kooo.evcam.overlay.OverlayCoordinator;
import com.kooo.evcam.telemetry.CarAmbientLab;
import com.kooo.evcam.ui.Notice;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Carries out the steps of an automation, one after the other, on its own thread: writes to the
 * car block. Anything that touches windows or the lights goes back to the main thread. A step
 * that fails does not stop the ones after it, and is said on screen.
 */
final class AutomationRunner {

    /** The car drops a value sent straight after power on ({@code CarAmbientLight}). */
    private static final long AFTER_POWER_MS = 250L;
    private static final long MAIN_WAIT_S = 3L;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler worker;

    AutomationRunner(Context context) {
        app = context.getApplicationContext();
        HandlerThread thread = new HandlerThread("automation");
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    /**
     * With {@code report}, a person pressed Try: say it finished as well as what failed. {@code done}
     * gets why steps failed, empty when none said so, on the main thread.
     */
    void run(List<AutomationRule.Step> steps, boolean report, Consumer<String> done) {
        List<AutomationRule.Step> copy = new ArrayList<>(steps);
        worker.post(() -> {
            StringBuilder failed = new StringBuilder();
            for (AutomationRule.Step step : copy) {
                String why;
                try {
                    why = perform(step);
                } catch (RuntimeException e) {
                    // A damaged saved choice must not take the app down on every gear change
                    why = e.toString();
                }
                if (why != null) {
                    BlackBox.note("Automation: " + step.action + " " + step.choice + ": " + why);
                    failed.append(failed.length() == 0 ? "" : ", ").append(why);
                }
            }
            // A shortcut says what it did itself: "Sent" would cover that
            String text = failed.length() > 0 ? app.getString(R.string.automation_failed, failed)
                    : report && !hasShortcut(copy) ? app.getString(R.string.automation_try_done) : null;
            if (text != null) {
                main.post(() -> Notice.makeText(app, text, Toast.LENGTH_LONG).show());
            }
            String why = failed.toString();
            main.post(() -> done.accept(why));
        });
    }

    private static boolean hasShortcut(List<AutomationRule.Step> steps) {
        for (AutomationRule.Step step : steps) {
            if (AutomationAction.SHORTCUT.key.equals(step.action)) {
                return true;
            }
        }
        return false;
    }

    /** Returns null when done, else why not. */
    private String perform(AutomationRule.Step step) {
        AutomationAction action = AutomationAction.fromKey(step.action);
        if (action == null) {
            return null;
        }
        switch (action) {
            case DRIVE_MODE:
                return DriveMode.set(app, step.choice);
            case MOOD_LIGHT:
                main.post(() -> GearLight.setOn(app, AutomationAction.ON.equals(step.choice)));
                return null;
            case MOOD_COLOR:
                Integer color = color(step.choice);
                if (color != null) {
                    main.post(() -> GearLight.holdColor(color));
                }
                return null;
            case CAR_AMBIENT:
                return carAmbient(step.choice);
            case CAR_AMBIENT_LEVEL:
                return carAmbientLevel(step.choice);
            case DIM:
                return dim(step.choice);
            case AC:
                return CarControls.ac(app, AutomationAction.ON.equals(step.choice));
            case TEMP_ZONES:
                return CarControls.tempZones(app, AutomationAction.DUAL.equals(step.choice));
            case GLOVEBOX:
                return CarControls.openGlovebox(app);
            case NAVIGATE:
                return navigate(step.choice);
            case SHORTCUT:
                main.post(() -> ShortcutPerformer.perform(app, step.choice, "automation", null));
                return null;
            default:
                return null;
        }
    }

    private String carAmbient(String choice) {
        Integer power = CarAmbientLab.functionId(CarAmbientLab.TOPZONES);
        Integer colorId = CarAmbientLab.functionId(CarAmbientLab.TOPZONES_COLOR);
        if (power == null || colorId == null) {
            return app.getString(R.string.automation_no_ambient);
        }
        if (AutomationAction.OFF.equals(choice)) {
            return CarAmbientLab.write(app, power, 0);
        }
        String failed = CarAmbientLab.write(app, power, 1);
        Integer color = color(choice);
        if (failed != null || color == null) {
            return failed;
        }
        try {
            Thread.sleep(AFTER_POWER_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return CarAmbientLab.write(app, colorId, color);
    }

    /** Worker thread: waits for Navigator on the main thread, to say if it refused. */
    private String navigate(String choice) {
        SavedPlaces.Place place = SavedPlaces.find(SavedPlaces.load(app), Integer.parseInt(choice));
        if (place == null) {
            return app.getString(R.string.automation_no_place);
        }
        if (!Navigator.active(app) || !Navigator.hasKey()) {
            return app.getString(R.string.automation_no_navigation);
        }
        FutureTask<Boolean> task = new FutureTask<>(() -> Navigator.get().guideTo(app, place.destination()));
        main.post(task);
        try {
            return task.get(MAIN_WAIT_S, TimeUnit.SECONDS) ? null : app.getString(R.string.automation_already_guiding);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return e.toString();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            return e.toString();
        }
    }

    private String carAmbientLevel(String choice) {
        int level = Integer.parseInt(choice);
        String failed = null;
        boolean any = false;
        for (String name : new String[]{CarAmbientLab.MAINZONES_INTENSITY, CarAmbientLab.TOPZONES_INTENSITY}) {
            Integer id = CarAmbientLab.functionId(name);
            if (id != null) {
                any = true;
                String why = CarAmbientLab.write(app, id, level);
                failed = failed == null ? why : failed;
            }
        }
        return any ? failed : app.getString(R.string.automation_no_ambient);
    }

    private String dim(String choice) {
        if (AutomationAction.OFF.equals(choice)) {
            main.post(() -> OverlayCoordinator.setDimOverlayEnabled(app, false));
            return null;
        }
        int opacity = Integer.parseInt(choice);
        main.post(() -> {
            new AppConfig(app).setDimOpacity(opacity);
            if (OverlayCoordinator.setDimOverlayEnabled(app, true)) {
                DimOverlayService.apply(app);
            } else {
                Notice.makeText(app, R.string.msg_need_overlay, Toast.LENGTH_LONG).show();
            }
        });
        return null;
    }

    /** A colour choice is hex; on, off and anything else is null. */
    private static Integer color(String choice) {
        if (AutomationAction.ON.equals(choice) || AutomationAction.OFF.equals(choice)) {
            return null;
        }
        try {
            return Integer.parseInt(choice, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
