package com.kooo.evcam.climate;

/**
 * When to turn the rear AC back on. Pure logic, fed one reading every couple of seconds.
 *
 * <p>The car keeps the rear AC setting when you leave and come back. It only drops it when the
 * whole climate system goes off (fan at zero) and on again. So this remembers what the rear was
 * set to while the front climate ran, waits for off then steady on, and sends one command if the
 * rear was on before. It never retries: the car answers the command late, so a command is checked
 * over the next few readings instead.</p>
 *
 * <p>A change of the rear only counts after two readings in a row, with the front on. Otherwise
 * the moment the climate goes off, when rear reads off a little before front, would look like the
 * person turning the rear off.</p>
 */
final class RearAcRule {

    enum Act {
        NONE,
        /** Send the rear on command now. */
        RESTORE,
        /** The rear reads on after the command. */
        RESTORED,
        /** The rear still reads off after the command. */
        NOT_RESTORED,
        /** Could not send in time (not in P, or defrosting). */
        GAVE_UP,
        REMEMBER_ON,
        REMEMBER_OFF
    }

    /** One reading; null is what the car did not give. */
    static final class Sample {
        final Integer frontPower;
        final Integer frontFan;
        final Integer rearPower;
        /** In P and the windscreen is not being cleared: safe to send. */
        final boolean clear;
        final long at;

        Sample(Integer frontPower, Integer frontFan, Integer rearPower, boolean clear, long at) {
            this.frontPower = frontPower;
            this.frontFan = frontFan;
            this.rearPower = rearPower;
            this.clear = clear;
            this.at = at;
        }
    }

    /** How long after the climate came on a command may still go out. */
    static final long LIMIT_MS = 90_000L;
    /** Readings to wait for the rear to read on after the command. */
    static final int CHECKS = 3;
    private static final int FAN_MASK = 0xFFFF0000;
    private static final int FAN_FAMILY = 0x10020000;
    private static final int OFF_SAMPLES = 2;

    private enum Phase { RUNNING, OFF, WAITING, CHECKING }

    private boolean wanted;
    private Phase phase = Phase.RUNNING;
    private Integer lastRear;
    private Integer changingTo;
    private Integer lastFan;
    private int offSamples;
    private long deadline;
    private int checksLeft;

    RearAcRule(boolean wanted) {
        this.wanted = wanted;
    }

    /** The command did not go out: wait for the next off and on. */
    void commandRefused() {
        lastRear = 0;
        phase = Phase.RUNNING;
    }

    Act step(Sample s) {
        boolean on = isOn(s);
        boolean off = isOff(s);
        Integer rear = s.rearPower == null || (s.rearPower != 0 && s.rearPower != 1) ? null : s.rearPower;
        switch (phase) {
            case OFF:
                return afterOff(s, on, off, rear);
            case WAITING:
                return waiting(s, off, rear);
            case CHECKING:
                return checking(rear);
            case RUNNING:
            default:
                return running(on, off, rear);
        }
    }

    private Act running(boolean on, boolean off, Integer rear) {
        if (off) {
            changingTo = null;
            if (++offSamples >= OFF_SAMPLES) {
                offSamples = 0;
                lastFan = null;
                phase = Phase.OFF;
            }
            return Act.NONE;
        }
        offSamples = 0;
        if (!on || rear == null) {
            return Act.NONE;
        }
        if (lastRear == null) {
            lastRear = rear;
            return rear == 1 ? remember(true) : Act.NONE;
        }
        if (rear.equals(lastRear)) {
            changingTo = null;
            return Act.NONE;
        }
        if (!rear.equals(changingTo)) {
            changingTo = rear;
            return Act.NONE;
        }
        changingTo = null;
        lastRear = rear;
        return remember(rear == 1);
    }

    private Act remember(boolean rearOn) {
        wanted = rearOn;
        return rearOn ? Act.REMEMBER_ON : Act.REMEMBER_OFF;
    }

    private Act afterOff(Sample s, boolean on, boolean off, Integer rear) {
        if (!on) {
            lastFan = off ? null : lastFan;
            return Act.NONE;
        }
        if (!s.frontFan.equals(lastFan)) {
            lastFan = s.frontFan;
            return Act.NONE;
        }
        if (rear == null) {
            return Act.NONE;
        }
        if (rear == 1 || !wanted) {
            lastRear = rear;
            changingTo = null;
            phase = Phase.RUNNING;
            return Act.NONE;
        }
        phase = Phase.WAITING;
        deadline = s.at + LIMIT_MS;
        return waiting(s, false, rear);
    }

    private Act waiting(Sample s, boolean off, Integer rear) {
        if (off) {
            lastFan = null;
            phase = Phase.OFF;
            return Act.NONE;
        }
        if (s.at > deadline) {
            // Stay wanted: the rear reads off because of the reset, not because of a person.
            lastRear = 0;
            changingTo = null;
            phase = Phase.RUNNING;
            return Act.GAVE_UP;
        }
        if (rear != null && rear == 1) {
            lastRear = 1;
            phase = Phase.RUNNING;
            return Act.NONE;
        }
        if (rear == null || !s.clear) {
            return Act.NONE;
        }
        checksLeft = CHECKS;
        phase = Phase.CHECKING;
        return Act.RESTORE;
    }

    private Act checking(Integer rear) {
        if (rear != null && rear == 1) {
            lastRear = 1;
            changingTo = null;
            phase = Phase.RUNNING;
            return Act.RESTORED;
        }
        if (--checksLeft > 0) {
            return Act.NONE;
        }
        lastRear = 0;
        changingTo = null;
        phase = Phase.RUNNING;
        return Act.NOT_RESTORED;
    }

    /** Positive proof only: missing data can read 0, so a fan speed must back the power reading. */
    private static boolean isOn(Sample s) {
        return s.frontPower != null && s.frontPower == 1 && s.frontFan != null
                && (s.frontFan & FAN_MASK) == FAN_FAMILY;
    }

    private static boolean isOff(Sample s) {
        return s.frontPower != null && s.frontPower == 0 && s.frontFan != null && s.frontFan == 0;
    }
}
