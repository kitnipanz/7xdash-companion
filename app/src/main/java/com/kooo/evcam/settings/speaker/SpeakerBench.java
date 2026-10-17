package com.kooo.evcam.settings.speaker;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One open bench per process. {@link #open} closes any bench already open.
 */
public final class SpeakerBench {

    private static final Object OPEN = new Object();
    private static SpeakerBench openBench;

    private static final String DASH = "—";
    private static final int SAMPLE_RATE = 48000;
    private static final int TONE_HZ = 480;
    private static final int DURATION_MS = 400;
    private static final int EDGE_MS = 8;
    private static final double AMPLITUDE = 0.2;
    private static final String CAR = "android.car.Car";
    private static final String CAR_AUDIO = "android.car.media.CarAudioManager";

    private final Context context;
    private final AudioManager audio;
    private final Handler main;
    private final Object playbackLock = new Object();
    private final Object zoneLock = new Object();
    private final ArrayList<OutputBinding> routes = new ArrayList<>();

    private PlayState state = PlayState.Idle.instance();
    private volatile int epoch = state.generation;
    private volatile boolean closed;
    private Listener listener;
    private Thread player;
    private AudioTrack track;

    private Object car;
    private Object carAudio;
    private CarAudioNote carNote = CarAudioNote.CLASS_MISSING;
    private String carQueryDetail = "";
    private String restoreFailure = "";
    private boolean zoneHeld;
    private int savedZone;

    private SpeakerBench(Context context) {
        Context app = context.getApplicationContext();
        this.context = app != null ? app : context;
        this.audio = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        this.main = new Handler(Looper.getMainLooper());
    }

    /** @throws IllegalArgumentException if context is null. Programmer error, not a failed route. */
    public static SpeakerBench open(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context");
        }
        synchronized (OPEN) {
            if (openBench != null) {
                openBench.close();
            }
            SpeakerBench bench = new SpeakerBench(context);
            openBench = bench;
            return bench;
        }
    }

    /**
     * Bindings this process can address right now.
     * Does not start a tone. Replaces the list {@link #play} will accept.
     * If the sounding id is absent from the new list, stops and becomes Failed(DISAPPEARED).
     */
    public Snapshot list() {
        if (closed) {
            return snapshot();
        }
        routes.clear();
        carNote = CarAudioNote.CLASS_MISSING;
        carQueryDetail = "";
        try {
            ensureCar();
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            carNote = CarAudioNote.CLASS_MISSING;
            carQueryDetail = raw(e);
        } catch (Unavailable e) {
            carNote = CarAudioNote.SERVICE_UNAVAILABLE;
            carQueryDetail = e.getMessage() == null ? "" : e.getMessage();
        } catch (Exception e) {
            carNote = CarAudioNote.SERVICE_UNAVAILABLE;
            carQueryDetail = raw(e);
        }
        if (carAudio != null) {
            try {
                addZones(routes);
                carNote = CarAudioNote.LISTED;
            } catch (Exception e) {
                routes.clear();
                carNote = CarAudioNote.QUERY_FAILED;
                carQueryDetail = raw(e);
            }
        }
        addDevices(routes);
        if (state instanceof PlayState.Sounding) {
            BindingId id = ((PlayState.Sounding) state).id;
            if (find(id) == null) {
                adopt(new PlayState.Failed(id, Fail.DISAPPEARED, "", state.generation + 1));
                stopPlayer();
            }
        }
        return snapshot();
    }

    /**
     * At most one sounding binding.
     * Same id while Sounding stops. Any other known id replaces it.
     * Null does nothing. An id not in the latest snapshot becomes Failed(UNKNOWN_ROUTE).
     * After {@link #close}, does nothing.
     */
    public void play(BindingId id) {
        if (closed || id == null) {
            return;
        }
        OutputBinding binding = find(id);
        if (binding == null) {
            adopt(new PlayState.Failed(id, Fail.UNKNOWN_ROUTE, "", state.generation + 1));
            stopPlayer();
            return;
        }
        PlayState next = reduce(state, PlayEvent.play(id));
        adopt(next);
        stopPlayer();
        if (next instanceof PlayState.Sounding) {
            startPlayer(id, next.generation);
        }
    }

    /** Main thread. Replacing the listener does not change state. Passed the current state immediately. */
    public void setListener(Listener listener) {
        if (closed) {
            return;
        }
        this.listener = listener;
        if (listener != null) {
            listener.onPlayState(state);
        }
    }

    /**
     * Idempotent. Releases the track, restores the uid zone saved for a ZONE_USAGE tone, then drops the listener.
     * A restore failure is remembered and appended to the next {@link #list()}'s car-audio detail.
     */
    public void close() {
        synchronized (OPEN) {
            if (closed) {
                return;
            }
            closed = true;
            if (openBench == this) {
                openBench = null;
            }
        }
        listener = null;
        state = reduce(state, PlayEvent.cut());
        epoch = state.generation;
        stopPlayer();
        restoreZone();
        disconnectCar();
        routes.clear();
    }

    static PlayState reduce(PlayState current, PlayEvent event) {
        switch (event.kind) {
            case PLAY:
                if (current instanceof PlayState.Sounding
                        && ((PlayState.Sounding) current).id.equals(event.id)) {
                    return new PlayState.Idle(current.generation + 1);
                }
                return new PlayState.Sounding(event.id, current.generation + 1);
            case FINISHED:
                if (event.generation != current.generation || !(current instanceof PlayState.Sounding)) {
                    return current;
                }
                return new PlayState.Completed(((PlayState.Sounding) current).id, current.generation);
            case FAULT:
                if (event.generation != current.generation || !(current instanceof PlayState.Sounding)) {
                    return current;
                }
                return new PlayState.Failed(((PlayState.Sounding) current).id, event.reason,
                        event.detail, current.generation);
            case CUT:
                return new PlayState.Idle(current.generation + 1);
            case STARTED:
            default:
                return current;
        }
    }

    public interface Listener {
        void onPlayState(PlayState state);
    }

    private void adopt(PlayState next) {
        state = next;
        epoch = next.generation;
        Listener current = listener;
        if (current != null) {
            current.onPlayState(next);
        }
    }

    private Snapshot snapshot() {
        return new Snapshot(routes, carNote, carDetail());
    }

    private String carDetail() {
        if (restoreFailure.isEmpty()) {
            return carQueryDetail;
        }
        if (carQueryDetail.isEmpty()) {
            return restoreFailure;
        }
        return carQueryDetail + "\n" + restoreFailure;
    }

    private OutputBinding find(BindingId id) {
        for (int i = 0; i < routes.size(); i++) {
            OutputBinding binding = routes.get(i);
            if (binding.id.equals(id)) {
                return binding;
            }
        }
        return null;
    }

    private void startPlayer(BindingId id, int generation) {
        String token = id.token;
        player = new Thread(() -> playToken(token, generation), "speaker-probe");
        player.start();
    }

    private void stopPlayer() {
        AudioTrack current = track;
        if (current != null) {
            try {
                current.pause();
                current.flush();
            } catch (IllegalStateException ignored) {
                // The track may not have reached play() yet.
            }
        }
        Thread running = player;
        if (running == null || Thread.currentThread() == running) {
            return;
        }
        try {
            running.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void playToken(String token, int generation) {
        synchronized (playbackLock) {
            if (epoch != generation || closed) {
                return;
            }
            try {
                if (token.startsWith("device:")) {
                    playDevice(Integer.parseInt(token.substring("device:".length())), generation);
                    return;
                }
                int split = token.indexOf(":usage:");
                if (token.startsWith("zone:") && split > 5) {
                    int zone = Integer.parseInt(token.substring("zone:".length(), split));
                    int usage = Integer.parseInt(token.substring(split + ":usage:".length()));
                    playZone(zone, usage, generation);
                    return;
                }
                postFault(generation, Fail.UNKNOWN_ROUTE, "");
            } catch (RuntimeException e) {
                postFault(generation, Fail.TRACK_INIT, raw(e));
            }
        }
    }

    private void playDevice(int deviceId, int generation) {
        AudioDeviceInfo device = findDevice(deviceId);
        if (device == null) {
            postFault(generation, Fail.DISAPPEARED, "");
            return;
        }
        int[] encodings = device.getEncodings();
        if (!acceptsPcm(encodings)) {
            postFault(generation, Fail.NOT_PCM, Arrays.toString(encodings));
            return;
        }
        playPcm(AudioAttributes.USAGE_MEDIA, device, smallestChannels(device.getChannelCounts()),
                generation, true, deviceId);
    }

    private void playZone(int zone, int usage, int generation) {
        if (carAudio == null) {
            postFault(generation, Fail.ZONE_FAILED, "CarAudioManager");
            return;
        }
        int uid = Process.myUid();
        int saved;
        try {
            saved = (Integer) call(carAudio.getClass().getMethod("getZoneIdForUid", int.class),
                    carAudio, uid);
        } catch (Exception e) {
            postFault(generation, zoneFail(e), raw(e));
            return;
        }
        synchronized (zoneLock) {
            savedZone = saved;
            zoneHeld = true;
        }
        try {
            call(carAudio.getClass().getMethod("setZoneIdForUid", int.class, int.class),
                    carAudio, zone, uid);
            if (epoch != generation || closed) {
                return;
            }
            AudioDeviceInfo reported = reportedInfo(zone, usage);
            if (reported != null && !acceptsPcm(reported.getEncodings())) {
                postFault(generation, Fail.NOT_PCM, Arrays.toString(reported.getEncodings()));
                return;
            }
            int channels = reported == null
                    ? 2 : smallestChannels(reported.getChannelCounts());
            playPcm(usage, null, channels, generation, false, 0);
        } catch (Exception e) {
            postFault(generation, zoneFail(e), raw(e));
        } finally {
            restoreZone();
        }
    }

    private void playPcm(int usage, AudioDeviceInfo device, int channels, int generation,
                         boolean prefer, int deviceId) {
        short[] pcm = tone(channels);
        AudioTrack local = null;
        boolean writing = false;
        try {
            local = buildTrack(usage, channels, pcm.length);
            track = local;
            if (prefer) {
                if (device == null || !local.setPreferredDevice(device)) {
                    postFault(generation, Fail.PREFERRED_DEVICE, "");
                    return;
                }
            }
            writing = true;
            if (epoch != generation || closed) {
                return;
            }
            int wrote = local.write(pcm, 0, pcm.length);
            if (wrote != pcm.length) {
                postFault(generation, Fail.WRITE, Integer.toString(wrote));
                return;
            }
            local.play();
            if (prefer && !routedMatches(local, deviceId, generation)) {
                return;
            }
            awaitPlayback(local, pcm.length / channels, generation);
            if (epoch != generation || closed) {
                return;
            }
            postFinished(generation);
        } catch (Exception e) {
            postFault(generation, writing ? Fail.WRITE : Fail.TRACK_INIT, raw(e));
        } finally {
            if (track == local) {
                track = null;
            }
            releaseTrack(local);
        }
    }

    /**
     * A missing routed device is not a miss. Some builds never fill getRoutedDevice.
     * A reported id that is not the requested device is a miss.
     */
    private boolean routedMatches(AudioTrack local, int deviceId, int generation) {
        AudioDeviceInfo routed = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            if (epoch != generation || closed) {
                return false;
            }
            routed = local.getRoutedDevice();
            if (routed != null) {
                break;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                return false;
            }
        }
        if (routed == null || routed.getId() == deviceId) {
            return true;
        }
        postFault(generation, Fail.ROUTE_MISMATCH, deviceId + " " + routed.getId());
        return false;
    }

    private void awaitPlayback(AudioTrack local, int frames, int generation) {
        long deadline = SystemClock.uptimeMillis() + DURATION_MS + 200L;
        while (SystemClock.uptimeMillis() < deadline) {
            if (epoch != generation || closed) {
                return;
            }
            try {
                if (local.getPlaybackHeadPosition() >= frames) {
                    return;
                }
            } catch (IllegalStateException e) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void postFinished(int generation) {
        main.post(() -> apply(PlayEvent.finished(generation)));
    }

    private void postFault(int generation, Fail reason, String detail) {
        main.post(() -> apply(PlayEvent.fault(generation, reason, detail)));
    }

    private void apply(PlayEvent event) {
        if (closed) {
            return;
        }
        PlayState next = reduce(state, event);
        if (next == state) {
            return;
        }
        adopt(next);
    }

    private AudioDeviceInfo findDevice(int id) {
        if (audio == null) {
            return null;
        }
        AudioDeviceInfo[] devices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        if (devices == null) {
            return null;
        }
        for (AudioDeviceInfo device : devices) {
            if (device.getId() == id) {
                return device;
            }
        }
        return null;
    }

    private void addDevices(ArrayList<OutputBinding> into) {
        if (audio == null) {
            return;
        }
        AudioDeviceInfo[] devices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        if (devices == null) {
            return;
        }
        for (AudioDeviceInfo device : devices) {
            into.add(deviceBinding(device));
        }
    }

    private static OutputBinding deviceBinding(AudioDeviceInfo device) {
        Facts facts = facts(device);
        return new OutputBinding(BindingId.device(device.getId()),
                new Report(Mechanism.DEVICE, facts.platformId, facts.type, facts.address,
                        facts.product, facts.channels, DASH, DASH));
    }

    private void ensureCar() throws Exception {
        if (carAudio != null) {
            return;
        }
        Class<?> carClass = Class.forName(CAR);
        Object created = connect(carClass);
        if (created == null) {
            throw new Unavailable("createCar");
        }
        try {
            Object manager = audioManager(carClass, created);
            if (manager == null) {
                throw new Unavailable("getCarManager");
            }
            car = created;
            carAudio = manager;
        } catch (Exception e) {
            disconnect(created);
            throw e;
        }
    }

    private Object connect(Class<?> carClass) throws Exception {
        try {
            Method create = carClass.getMethod("createCar", Context.class);
            return call(create, null, context);
        } catch (NoSuchMethodException ignored) {
            Method create = carClass.getMethod("createCar", Context.class, Handler.class);
            return call(create, null, context, main);
        }
    }

    private Object audioManager(Class<?> carClass, Object created) throws Exception {
        try {
            String service = (String) carClass.getField("AUDIO_SERVICE").get(null);
            return call(carClass.getMethod("getCarManager", String.class), created, service);
        } catch (NoSuchFieldException | NoSuchMethodException ignored) {
            Class<?> type = Class.forName(CAR_AUDIO);
            return call(carClass.getMethod("getCarManager", Class.class), created, type);
        }
    }

    private void addZones(ArrayList<OutputBinding> into) throws Exception {
        int[] zones = (int[]) call(carAudio.getClass().getMethod("getAudioZoneIds"), carAudio);
        if (zones == null) {
            zones = new int[0];
        }
        int[] sorted = zones.clone();
        Arrays.sort(sorted);
        int previousZone = 0;
        boolean seenZone = false;
        for (int zone : sorted) {
            if (seenZone && zone == previousZone) {
                continue;
            }
            seenZone = true;
            previousZone = zone;
            int groups = (Integer) call(
                    carAudio.getClass().getMethod("getVolumeGroupCount", int.class), carAudio, zone);
            int[] usages = new int[0];
            for (int group = 0; group < groups; group++) {
                int[] part = (int[]) call(carAudio.getClass().getMethod(
                        "getUsagesForVolumeGroupId", int.class, int.class), carAudio, zone, group);
                usages = merge(usages, part);
            }
            Arrays.sort(usages);
            int previousUsage = 0;
            boolean seenUsage = false;
            for (int usage : usages) {
                if (seenUsage && usage == previousUsage) {
                    continue;
                }
                seenUsage = true;
                previousUsage = usage;
                into.add(zoneBinding(zone, usage));
            }
        }
    }

    private OutputBinding zoneBinding(int zone, int usage) {
        String platform = DASH;
        String type = DASH;
        String address = DASH;
        String product = DASH;
        String channels = DASH;
        try {
            Object reported = call(carAudio.getClass().getMethod(
                    "getOutputDeviceForUsage", int.class, int.class), carAudio, zone, usage);
            Facts facts = firstFacts(reported);
            if (facts != null) {
                platform = facts.platformId;
                type = facts.type;
                address = facts.address;
                product = facts.product;
                channels = facts.channels;
            }
        } catch (Exception ignored) {
        }
        return new OutputBinding(BindingId.zone(zone, usage),
                new Report(Mechanism.ZONE_USAGE, platform, type, address, product, channels,
                        Integer.toString(zone), usageName(usage)));
    }

    private AudioDeviceInfo reportedInfo(int zone, int usage) {
        try {
            Object reported = call(carAudio.getClass().getMethod(
                    "getOutputDeviceForUsage", int.class, int.class), carAudio, zone, usage);
            return asDevice(reported);
        } catch (Exception e) {
            return null;
        }
    }

    private Facts firstFacts(Object reported) {
        if (reported == null) {
            return null;
        }
        if (reported instanceof AudioDeviceInfo) {
            return facts((AudioDeviceInfo) reported);
        }
        if (reported instanceof AudioDeviceInfo[]) {
            AudioDeviceInfo[] all = (AudioDeviceInfo[]) reported;
            return all.length == 0 ? null : facts(all[0]);
        }
        if (reported instanceof List) {
            List<?> values = (List<?>) reported;
            if (values.isEmpty()) {
                return null;
            }
            Object first = values.get(0);
            if (first instanceof AudioDeviceInfo || first instanceof List
                    || first instanceof AudioDeviceInfo[]) {
                return firstFacts(first);
            }
            return factsOf(first);
        }
        return factsOf(reported);
    }

    private static AudioDeviceInfo asDevice(Object reported) {
        if (reported instanceof AudioDeviceInfo) {
            return (AudioDeviceInfo) reported;
        }
        if (reported instanceof AudioDeviceInfo[]) {
            AudioDeviceInfo[] all = (AudioDeviceInfo[]) reported;
            return all.length == 0 ? null : all[0];
        }
        if (reported instanceof List) {
            for (Object item : (List<?>) reported) {
                AudioDeviceInfo device = asDevice(item);
                if (device != null) {
                    return device;
                }
            }
        }
        return null;
    }

    private static Facts facts(AudioDeviceInfo device) {
        CharSequence product = device.getProductName();
        return new Facts(Integer.toString(device.getId()), typeName(device.getType()),
                fact(device.getAddress()), fact(product == null ? null : product.toString()),
                channelsText(device.getChannelCounts()));
    }

    private static Facts factsOf(Object reported) {
        Integer type = intGetter(reported, "getType");
        String name = stringGetter(reported, "getProductName");
        if (name == null) {
            name = stringGetter(reported, "getName");
        }
        return new Facts(DASH, type == null ? DASH : typeName(type),
                fact(stringGetter(reported, "getAddress")), fact(name), DASH);
    }

    private void restoreZone() {
        int saved;
        Object manager;
        synchronized (zoneLock) {
            if (!zoneHeld) {
                return;
            }
            zoneHeld = false;
            saved = savedZone;
            manager = carAudio;
        }
        if (manager == null) {
            restoreFailure = "CarAudioManager";
            return;
        }
        try {
            call(manager.getClass().getMethod("setZoneIdForUid", int.class, int.class),
                    manager, saved, Process.myUid());
        } catch (Exception e) {
            restoreFailure = raw(e);
        }
    }

    private void disconnectCar() {
        Object held = car;
        car = null;
        carAudio = null;
        if (held != null) {
            disconnect(held);
        }
    }

    private static void disconnect(Object held) {
        try {
            call(held.getClass().getMethod("disconnect"), held);
        } catch (Exception ignored) {
        }
    }

    private static AudioTrack buildTrack(int usage, int channels, int shorts) {
        int bytes = Math.max(shorts, 1) * 2;
        AudioTrack built = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .setAudioFormat(format(channels))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bytes)
                .build();
        int trackState = built.getState();
        if (!opened(trackState)) {
            built.release();
            throw new IllegalStateException(Integer.toString(trackState));
        }
        return built;
    }

    /** Static mode reports {@link AudioTrack#STATE_NO_STATIC_DATA} until {@code write}. */
    static boolean opened(int trackState) {
        return trackState == AudioTrack.STATE_INITIALIZED
                || trackState == AudioTrack.STATE_NO_STATIC_DATA;
    }

    private static AudioFormat format(int channels) {
        AudioFormat.Builder builder = new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT);
        if (channels <= 1) {
            builder.setChannelMask(AudioFormat.CHANNEL_OUT_MONO);
        } else if (channels == 2) {
            builder.setChannelMask(AudioFormat.CHANNEL_OUT_STEREO);
        } else {
            builder.setChannelIndexMask((1 << Math.min(channels, 30)) - 1);
        }
        return builder.build();
    }

    private static short[] tone(int channels) {
        int count = channels < 1 ? 2 : channels;
        int frames = SAMPLE_RATE * DURATION_MS / 1000;
        int edge = SAMPLE_RATE * EDGE_MS / 1000;
        short[] interleaved = new short[frames * count];
        for (int i = 0; i < frames; i++) {
            double envelope = 1.0;
            if (i < edge) {
                envelope = i / (double) edge;
            } else if (i > frames - 1 - edge) {
                envelope = (frames - 1 - i) / (double) edge;
            }
            double sample = Math.sin(2.0 * Math.PI * TONE_HZ * i / SAMPLE_RATE)
                    * AMPLITUDE * envelope;
            short pcm = (short) Math.round(sample * 32767.0);
            for (int channel = 0; channel < count; channel++) {
                interleaved[i * count + channel] = pcm;
            }
        }
        return interleaved;
    }

    private static boolean acceptsPcm(int[] encodings) {
        if (encodings == null || encodings.length == 0) {
            return true;
        }
        for (int encoding : encodings) {
            if (encoding == AudioFormat.ENCODING_PCM_16BIT
                    || encoding == AudioFormat.ENCODING_PCM_8BIT
                    || encoding == AudioFormat.ENCODING_PCM_FLOAT
                    || encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED
                    || encoding == AudioFormat.ENCODING_PCM_32BIT) {
                return true;
            }
        }
        return false;
    }

    private static int smallestChannels(int[] counts) {
        if (counts == null || counts.length == 0) {
            return 2;
        }
        int min = Integer.MAX_VALUE;
        for (int count : counts) {
            if (count > 0 && count < min) {
                min = count;
            }
        }
        return min == Integer.MAX_VALUE ? 2 : min;
    }

    private static int[] merge(int[] left, int[] right) {
        if (right == null || right.length == 0) {
            return left;
        }
        int[] both = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right, 0, both, left.length, right.length);
        return both;
    }

    private static void releaseTrack(AudioTrack local) {
        if (local == null) {
            return;
        }
        try {
            local.pause();
        } catch (IllegalStateException ignored) {
        }
        try {
            local.flush();
        } catch (IllegalStateException ignored) {
        }
        try {
            local.stop();
        } catch (IllegalStateException ignored) {
        }
        try {
            local.release();
        } catch (RuntimeException ignored) {
        }
    }

    private static Fail zoneFail(Throwable error) {
        Throwable cause = error;
        if (cause instanceof InvocationTargetException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof SecurityException) {
            return Fail.ZONE_DENIED;
        }
        return Fail.ZONE_FAILED;
    }

    private static Object call(Method method, Object target, Object... args) throws Exception {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        }
    }

    private static String raw(Throwable error) {
        if (error == null) {
            return "";
        }
        String message = error.getMessage();
        if (message == null || message.isEmpty()) {
            return error.getClass().getName();
        }
        return error.getClass().getName() + ": " + message;
    }

    /** Platform name when the device has it. The method is newer than minSdk, so it is reflected. */
    private static String typeName(int type) {
        try {
            Method method = AudioDeviceInfo.class.getMethod("typeToString", int.class);
            Object name = method.invoke(null, type);
            if (name != null) {
                return name.toString();
            }
        } catch (Exception ignored) {
        }
        return Integer.toString(type);
    }

    private static String usageName(int usage) {
        try {
            Method method = AudioAttributes.class.getMethod("usageToString", int.class);
            Object name = method.invoke(null, usage);
            if (name != null) {
                return name.toString();
            }
        } catch (Exception ignored) {
        }
        return Integer.toString(usage);
    }

    private static String channelsText(int[] counts) {
        if (counts == null || counts.length == 0) {
            return DASH;
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < counts.length; i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(counts[i]);
        }
        return text.toString();
    }

    private static String fact(String value) {
        if (value == null || value.trim().isEmpty()) {
            return DASH;
        }
        return value;
    }

    private static Integer intGetter(Object target, String name) {
        try {
            Object value = call(target.getClass().getMethod(name), target);
            return value instanceof Integer ? (Integer) value : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String stringGetter(Object target, String name) {
        try {
            Object value = call(target.getClass().getMethod(name), target);
            return value == null ? null : value.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static final class Unavailable extends Exception {
        Unavailable(String detail) {
            super(detail);
        }
    }

    private static final class Facts {
        final String platformId;
        final String type;
        final String address;
        final String product;
        final String channels;

        Facts(String platformId, String type, String address, String product, String channels) {
            this.platformId = platformId;
            this.type = type;
            this.address = address;
            this.product = product;
            this.channels = channels;
        }
    }

    static final class PlayEvent {
        enum Kind { PLAY, STARTED, FINISHED, FAULT, CUT }

        final Kind kind;
        final BindingId id;
        final int generation;
        final Fail reason;
        final String detail;

        private PlayEvent(Kind kind, BindingId id, int generation, Fail reason, String detail) {
            this.kind = kind;
            this.id = id;
            this.generation = generation;
            this.reason = reason;
            this.detail = detail == null ? "" : detail;
        }

        static PlayEvent play(BindingId id) {
            return new PlayEvent(Kind.PLAY, id, 0, null, "");
        }

        static PlayEvent started(int generation) {
            return new PlayEvent(Kind.STARTED, null, generation, null, "");
        }

        static PlayEvent finished(int generation) {
            return new PlayEvent(Kind.FINISHED, null, generation, null, "");
        }

        static PlayEvent fault(int generation, Fail reason, String detail) {
            return new PlayEvent(Kind.FAULT, null, generation, reason, detail);
        }

        static PlayEvent cut() {
            return new PlayEvent(Kind.CUT, null, 0, null, "");
        }
    }

    /** Opaque. Only the bench creates one. */
    public static final class BindingId {
        private final String token;

        private BindingId(String token) {
            this.token = token;
        }

        static BindingId device(int id) {
            return new BindingId("device:" + id);
        }

        static BindingId zone(int zoneId, int usage) {
            return new BindingId("zone:" + zoneId + ":usage:" + usage);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof BindingId && token.equals(((BindingId) other).token);
        }

        @Override
        public int hashCode() {
            return token.hashCode();
        }

        /** Logs only. Not a parse format for callers. */
        @Override
        public String toString() {
            return token;
        }
    }

    public enum Mechanism { DEVICE, ZONE_USAGE }

    public static final class Report {
        public final Mechanism mechanism;
        public final String platformId;
        public final String androidType;
        public final String address;
        public final String productName;
        public final String channelCounts;
        public final String zone;
        public final String usage;

        private Report(Mechanism mechanism, String platformId, String androidType, String address,
                       String productName, String channelCounts, String zone, String usage) {
            this.mechanism = mechanism;
            this.platformId = present(platformId);
            this.androidType = present(androidType);
            this.address = present(address);
            this.productName = present(productName);
            this.channelCounts = present(channelCounts);
            this.zone = present(zone);
            this.usage = present(usage);
        }

        private static String present(String value) {
            return fact(value);
        }
    }

    public static final class OutputBinding {
        public final BindingId id;
        public final Report report;

        private OutputBinding(BindingId id, Report report) {
            this.id = id;
            this.report = report;
        }
    }

    public enum CarAudioNote {
        LISTED,
        CLASS_MISSING,
        SERVICE_UNAVAILABLE,
        QUERY_FAILED
    }

    public static final class Snapshot {
        private final List<OutputBinding> routes;
        public final CarAudioNote carAudio;
        public final String carAudioDetail;

        Snapshot(List<OutputBinding> routes, CarAudioNote carAudio, String carAudioDetail) {
            this.routes = Collections.unmodifiableList(new ArrayList<>(routes));
            this.carAudio = carAudio;
            this.carAudioDetail = carAudioDetail == null ? "" : carAudioDetail;
        }

        public List<OutputBinding> routes() {
            return routes;
        }
    }

    public abstract static class PlayState {
        private final int generation;

        private PlayState(int generation) {
            this.generation = generation;
        }

        int generation() {
            return generation;
        }

        public static final class Idle extends PlayState {
            private static final Idle START = new Idle(0);

            private Idle(int generation) {
                super(generation);
            }

            public static Idle instance() {
                return START;
            }
        }

        public static final class Sounding extends PlayState {
            public final BindingId id;

            private Sounding(BindingId id, int generation) {
                super(generation);
                this.id = id;
            }
        }

        public static final class Completed extends PlayState {
            public final BindingId id;

            private Completed(BindingId id, int generation) {
                super(generation);
                this.id = id;
            }
        }

        public static final class Failed extends PlayState {
            public final BindingId id;
            public final Fail reason;
            public final String detail;

            private Failed(BindingId id, Fail reason, String detail, int generation) {
                super(generation);
                this.id = id;
                this.reason = reason;
                this.detail = detail == null ? "" : detail;
            }
        }
    }

    public enum Fail {
        UNKNOWN_ROUTE,
        DISAPPEARED,
        NOT_PCM,
        TRACK_INIT,
        WRITE,
        PREFERRED_DEVICE,
        ZONE_DENIED,
        ZONE_FAILED,
        ROUTE_MISMATCH
    }
}
