package com.kooo.evcam.climate;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.UserExit;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.telemetry.CarAmbientLab;
import com.kooo.evcam.telemetry.Telemetry;
import com.kooo.evcam.telemetry.VehicleState;

/**
 * Turns the rear AC back on after the car's climate went off and on ({@link RearAcRule}), when
 * the person switched this on in Settings. Off by default: it writes to the car on its own.
 *
 * <p>Numbers and zones are from the Australian 7X notes (zeekr-shortcut-car PR 145, firmware
 * 2.1.1c). The zone meanings hold for these climate functions only. If this head unit's library
 * names them differently it does nothing. Reads only while the gear is known (the car is awake)
 * and sends only in P: speed is a float sensor nobody keeps on for this, and P means stopped.</p>
 */
public final class RearAc {

    private static final long EVERY_MS = 2_000L;
    private static final int POWER = 0x10010100;
    private static final int FAN = 0x10020100;
    private static final int ZONE_FRONT = 0x08;
    private static final int ZONE_REAR = 0x80;
    private static final int ON = 1;
    private static final String DEFROST = "HVAC_FUNC_DEFROST_FRONT_MAX";

    private static RearAc installed;

    private final Context app;
    private final AppConfig config;
    private final Handler handler;
    private final Runnable tick = this::tick;
    private RearAcRule rule;
    private boolean checked;
    private boolean usable;

    private RearAc(Context context) {
        app = context.getApplicationContext();
        config = new AppConfig(app);
        HandlerThread thread = new HandlerThread("rear-ac");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    public static void install(Context context) {
        if (installed == null) {
            installed = new RearAc(context);
        }
        reload();
    }

    /** The switch changed. */
    public static void reload() {
        RearAc me = installed;
        if (me != null) {
            me.handler.post(me::restart);
        }
    }

    private void restart() {
        handler.removeCallbacks(tick);
        rule = null;
        if (config.isRearAcRestore()) {
            handler.post(tick);
        }
    }

    private void tick() {
        handler.postDelayed(tick, EVERY_MS);
        VehicleState car = Telemetry.get().latest();
        if (car == null || car.gear == null || UserExit.isExited(app) || !usable()) {
            return;
        }
        if (rule == null) {
            rule = new RearAcRule(config.isRearAcWanted());
        }
        Integer frontPower = CarAmbientLab.valueInZone(app, POWER, ZONE_FRONT);
        Integer frontFan = CarAmbientLab.valueInZone(app, FAN, ZONE_FRONT);
        Integer rearPower = CarAmbientLab.valueInZone(app, POWER, ZONE_REAR);
        boolean clear = "P".equals(car.gear) && !defrosting();
        act(rule.step(new RearAcRule.Sample(frontPower, frontFan, rearPower, clear, SystemClock.elapsedRealtime())));
    }

    private void act(RearAcRule.Act act) {
        switch (act) {
            case RESTORE:
                String failed = CarAmbientLab.writeInZone(app, POWER, ZONE_REAR, ON);
                BlackBox.note("Rear AC: climate came back on, " + (failed == null ? "sent on" : failed));
                if (failed != null) {
                    rule.commandRefused();
                }
                break;
            case RESTORED:
                BlackBox.note("Rear AC: reads on");
                break;
            case NOT_RESTORED:
                BlackBox.note("Rear AC: still reads off after the command");
                break;
            case GAVE_UP:
                BlackBox.note("Rear AC: not restored, not in P or defrosting");
                break;
            case REMEMBER_ON:
                config.setRearAcWanted(true);
                break;
            case REMEMBER_OFF:
                config.setRearAcWanted(false);
                break;
            case NONE:
            default:
                break;
        }
    }

    /** Only with the names and numbers the notes were tested with. Looked up once. */
    private boolean usable() {
        if (!checked) {
            checked = true;
            Integer power = CarAmbientLab.functionId("HVAC_FUNC_POWER");
            Integer fan = CarAmbientLab.functionId("HVAC_FUNC_FAN_SPEED");
            usable = power != null && power == POWER && fan != null && fan == FAN;
            if (!usable) {
                BlackBox.note("Rear AC: not on this head unit (power " + power + ", fan " + fan + ")");
            }
        }
        return usable;
    }

    /** The windscreen gets the air first. Not all clearing modes can be read, so this is the max one. */
    private boolean defrosting() {
        Integer id = CarAmbientLab.functionId(DEFROST);
        if (id == null) {
            return false;
        }
        Integer plain = CarAmbientLab.value(app, id);
        return plain != null && plain == ON;
    }
}
