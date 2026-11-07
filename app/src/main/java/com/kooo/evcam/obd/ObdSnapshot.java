package com.kooo.evcam.obd;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** What the screen shows: one immutable copy, published from the reading thread. */
final class ObdSnapshot {

    enum State {
        CONNECTING,
        /** Talking to the adapter, looking for the car. */
        SEARCHING,
        /** The adapter answers but the car does not: usually ignition off. Keeps trying. */
        NO_CAR,
        LIVE,
        STOPPED
    }

    enum Failure {
        NONE,
        /** The link did not open: adapter off, out of range, or not the one paired. */
        CANNOT_OPEN,
        /** Something answered, but it is not an ELM327. */
        NOT_ELM,
        LOST
    }

    final State state;
    final Failure failure;
    final String adapter;
    final String protocol;
    /** Voltage on the OBD socket, which the adapter measures itself: the car's 12 V battery. */
    final Float volts;
    final Map<ObdPid, Double> values;
    final List<Integer> supported;
    /** Null until read. */
    final List<String> storedCodes;
    final List<String> pendingCodes;
    /** Direct battery module reads by identifier, in request order; empty until tried. */
    final Map<Integer, ZeekrProbe.Result> zeekr;

    ObdSnapshot(State state, Failure failure, String adapter, String protocol, Float volts,
                Map<ObdPid, Double> values, List<Integer> supported,
                List<String> storedCodes, List<String> pendingCodes,
                Map<Integer, ZeekrProbe.Result> zeekr) {
        this.state = state;
        this.failure = failure;
        this.adapter = adapter;
        this.protocol = protocol;
        this.volts = volts;
        this.values = Collections.unmodifiableMap(values);
        this.zeekr = Collections.unmodifiableMap(zeekr);
        this.supported = Collections.unmodifiableList(supported);
        this.storedCodes = storedCodes == null ? null : Collections.unmodifiableList(storedCodes);
        this.pendingCodes = pendingCodes == null ? null : Collections.unmodifiableList(pendingCodes);
    }
}
