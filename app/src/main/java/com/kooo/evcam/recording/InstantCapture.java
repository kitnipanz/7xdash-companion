package com.kooo.evcam.recording;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.FileTransferManager;
import com.kooo.evcam.R;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.camera.CameraSlots;
import com.kooo.evcam.camera.StoragePlan;
import com.kooo.evcam.profile.RecordSpecs;
import com.kooo.evcam.repair.Mp4Repair;
import com.kooo.evcam.share.DriveExport;
import com.kooo.evcam.share.SurroundDefish;
import com.kooo.evcam.share.UsbExport;
import com.kooo.evcam.storage.LockWindow;
import com.kooo.evcam.zeekr.RecordingTimeline;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Flash-to-pass writes the surround view, 10 seconds before the flash and 10
 * seconds after, into {@code <usb>/instant captures}. Other cameras and the
 * rest of the trip stay in the video folder. Drive upload follows the
 * save-moment Drive switch.
 */
public final class InstantCapture {

    private static final String TAG = "InstantCapture";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object GATE = new Object();

    private InstantCapture() {
    }

    public static void perform(Context context) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        CaptureLog.attach(app);
        long momentMs = System.currentTimeMillis();
        t("capture start at " + stampOf(momentMs));
        toast(app, app.getString(R.string.instant_capture_start));
        new Thread(() -> {
            synchronized (GATE) {
                try {
                    run(app, momentMs);
                } catch (Exception e) {
                    AppLog.e(TAG, "即时片段失败", e);
                    t("FAILED: crash " + e);
                    toast(app, app.getString(R.string.instant_capture_failed));
                }
            }
        }, "instant-capture").start();
    }

    private static void run(Context app, long momentMs) {
        LockWindow window = LockWindow.around(momentMs);
        long longest = longestSegment(app);
        long deadline = window.endMs + longest * 2L + 15_000L;
        File videoDir = StorageHelper.getVideoDir(app);
        t("window " + stampOf(window.startMs) + " .. " + stampOf(window.endMs)
                + " segment=" + longest / 1000 + "s videoDir=" + (videoDir == null ? "NONE" : videoDir.getAbsolutePath()
                + (videoDir.isDirectory() ? "" : " (missing)")));
        boolean waitedOpen = false;
        List<File> sources = new ArrayList<>();
        List<File> repaired = new ArrayList<>();
        long lastRepair = 0L;
        while (true) {
            long now = System.currentTimeMillis();
            if (now >= window.endMs + LockWindow.SLACK_MS) {
                List<File> ready = closedOverlap(app, window);
                if (ready != null) {
                    t("files covering the flash (closed): " + names(ready));
                    sources = ready;
                    break;
                }
                if (!waitedOpen) {
                    waitedOpen = true;
                    t("a covering file is still recording: " + names(candidates(app, window)));
                }
                if (now - lastRepair >= 5_000L) {
                    lastRepair = now;
                    dropOpenCopies(repaired);
                    repaired = repairOverlap(app, window);
                    if (!repaired.isEmpty()) {
                        t("repaired copy of the open file: " + names(repaired));
                        sources = repaired;
                        break;
                    }
                    t("repair of the open file not possible yet");
                }
            }
            if (now >= deadline) {
                sources = playableOverlap(app, window);
                t("gave up waiting; playable files: " + names(sources));
                break;
            }
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                dropOpenCopies(repaired);
                return;
            }
        }
        List<File> before = sources;
        sources = oneClip(sources, momentMs);
        if (sources.isEmpty()) {
            t("FAILED: no surround file contains the flash moment. candidates=" + names(before)
                    + " all surround files=" + names(candidatesAll(app)));
            dropOpenCopies(repaired);
            toast(app, app.getString(R.string.instant_capture_failed));
            return;
        }
        t("cutting from " + names(sources));
        File cache = new File(app.getCacheDir(), "instant");
        if (!cache.exists() && !cache.mkdirs()) {
            dropOpenCopies(repaired);
            toast(app, app.getString(R.string.instant_capture_failed));
            return;
        }
        List<File> clips = new ArrayList<>();
        try {
            clips.addAll(cut(app, cache, sources, window, momentMs));
            if (clips.isEmpty()) {
                t("FAILED: cutting produced no clip (see the cut line above)");
                toast(app, app.getString(R.string.instant_capture_failed));
                return;
            }
            int copied = copyToUsb(app, clips);
            if (copied > 0) {
                t("SAVED " + copied + " clip(s)");
                final int count = copied;
                toast(app, app.getString(R.string.instant_capture_saved, count));
            } else if (StorageHelper.getExternalSdCardRoot(app) == null) {
                t("FAILED: no USB stick found");
                toast(app, app.getString(R.string.save_moment_no_usb));
            } else {
                t("FAILED: copy to the USB stick (see the copy line above)");
                toast(app, app.getString(R.string.instant_capture_failed));
            }
            AppConfig config = new AppConfig(app);
            boolean drive = config.isSaveMomentDrive()
                    && config.hasDriveClient() && config.hasDriveRefreshToken();
            if (drive) {
                int sent = DriveExport.uploadQuiet(app, clips, false);
                if (sent > 0) {
                    final int count = sent;
                    toast(app, app.getString(R.string.instant_capture_uploaded, count));
                } else {
                    toast(app, app.getString(R.string.instant_capture_upload_failed));
                }
            }
        } finally {
            for (File clip : clips) {
                if (clip != null && clip.exists() && !clip.delete()) {
                    AppLog.w(TAG, "删不掉临时片段: " + clip.getAbsolutePath());
                }
            }
            dropOpenCopies(repaired);
        }
    }

    /** Null while a file that overlaps the window is still open. */
    private static List<File> closedOverlap(Context app, LockWindow window) {
        List<File> hits = candidates(app, window);
        List<File> ready = new ArrayList<>();
        for (File file : hits) {
            long start = RecordingTimeline.parseStartEpochMs(file.getName());
            if (start < 0 || start >= window.endMs) {
                continue;
            }
            if (InstantSpan.inFile(start, window.startMs, window.endMs) == null) {
                continue;
            }
            if (!UsbExport.playable(file)) {
                return null;
            }
            ready.add(file);
        }
        return ready;
    }

    private static List<File> playableOverlap(Context app, LockWindow window) {
        List<File> ready = new ArrayList<>();
        for (File file : candidates(app, window)) {
            long start = RecordingTimeline.parseStartEpochMs(file.getName());
            if (start < 0 || InstantSpan.inFile(start, window.startMs, window.endMs) == null) {
                continue;
            }
            if (UsbExport.playable(file)) {
                ready.add(file);
            }
        }
        return ready;
    }

    private static List<File> candidates(Context app, LockWindow window) {
        List<File> dirs = new ArrayList<>();
        File video = StorageHelper.getVideoDir(app);
        if (video != null) {
            dirs.add(video);
        }
        dirs.add(new File(app.getCacheDir(), FileTransferManager.TEMP_VIDEO_DIR));
        Set<String> names = new LinkedHashSet<>();
        Map<String, File> byName = new HashMap<>();
        for (File dir : dirs) {
            File[] listed = dir.listFiles();
            if (listed == null) {
                continue;
            }
            for (File file : listed) {
                if (!file.isFile() || !StoragePlan.isOwnClip(file.getName()) || !surround(file)) {
                    continue;
                }
                names.add(file.getName());
                File previous = byName.get(file.getName());
                if (previous == null || video != null && video.equals(file.getParentFile())) {
                    byName.put(file.getName(), file);
                }
            }
        }
        Map<String, Long> lengths = segmentLengths(app);
        Set<String> hit = window.overlapping(names, lengths, longest(lengths));
        List<File> out = new ArrayList<>();
        for (String name : hit) {
            File file = byName.get(name);
            if (file != null) {
                out.add(file);
            }
        }
        return out;
    }

    /** One flash, one file: the segment that contains the flash, not its neighbor. */
    private static List<File> oneClip(List<File> sources, long momentMs) {
        long[] starts = new long[sources.size()];
        for (int i = 0; i < sources.size(); i++) {
            starts[i] = RecordingTimeline.parseStartEpochMs(sources.get(i).getName());
        }
        int index = InstantSpan.containing(starts, momentMs);
        if (index < 0 || index >= sources.size()) {
            return new ArrayList<>();
        }
        return java.util.Collections.singletonList(sources.get(index));
    }

    private static List<File> cut(Context app, File cache, List<File> sources,
                                  LockWindow window, long momentMs) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(momentMs));
        Set<String> used = new LinkedHashSet<>();
        List<File> clips = new ArrayList<>();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        for (File source : sources) {
            long start = RecordingTimeline.parseStartEpochMs(source.getName());
            InstantSpan span = InstantSpan.inFile(start, window.startMs, window.endMs);
            if (span == null) {
                continue;
            }
            String slot = RecordingTimeline.parseCameraSlot(source.getName());
            if (slot == null || slot.isEmpty()) {
                slot = "cam";
            }
            String name = unique(used, stamp + "_" + slot + ".mp4");
            File raw = new File(cache, name + ".part");
            File clip = new File(cache, name);
            try {
                InstantClip.write(source, raw, span.startUs, span.endUs);
                if (!UsbExport.playable(raw)) {
                    throw new java.io.IOException("unfinished " + name);
                }
                File payload = raw;
                if (SurroundDefish.wanted(app, source)) {
                    SurroundDefish.write(app, raw, clip, cancelled, null);
                    if (!UsbExport.playable(clip)) {
                        throw new java.io.IOException("unfinished " + name);
                    }
                    if (!raw.delete()) {
                        AppLog.w(TAG, "删不掉未拉直的片段: " + raw.getAbsolutePath());
                    }
                    payload = clip;
                } else if (!raw.renameTo(clip)) {
                    throw new java.io.IOException("rename " + raw.getAbsolutePath());
                }
                clips.add(payload);
            } catch (Exception e) {
                AppLog.e(TAG, "切即时片段失败: " + source.getAbsolutePath(), e);
                t("cut failed " + source.getName() + " span=" + span.startUs / 1000 + ".." + span.endUs / 1000
                        + "ms defish=" + SurroundDefish.wanted(app, source) + ": " + e);
                if (raw.exists() && !raw.delete()) {
                    AppLog.w(TAG, "删不掉半成品: " + raw.getAbsolutePath());
                }
                if (clip.exists() && !clips.contains(clip) && !clip.delete()) {
                    AppLog.w(TAG, "删不掉半成品: " + clip.getAbsolutePath());
                }
            }
        }
        return clips;
    }

    private static int copyToUsb(Context app, List<File> clips) {
        File root = StorageHelper.getExternalSdCardRoot(app);
        if (root == null) {
            AppLog.w(TAG, "要存即时片段，但没有 U 盘");
            return 0;
        }
        File dest = instantFolder(root, StorageHelper.getVideoDir(app));
        if (dest == null) {
            AppLog.w(TAG, "要存即时片段，但目录建不出来");
            t("copy: cannot create the folder under " + root.getAbsolutePath());
            return 0;
        }
        t("copy to " + dest.getAbsolutePath() + (dest.isDirectory() ? "" : " (folder missing)")
                + " canWrite=" + dest.canWrite());
        int copied = 0;
        for (File clip : clips) {
            try {
                UsbExport.copy(clip, dest);
                copied++;
            } catch (Exception e) {
                AppLog.e(TAG, "拷到 instant captures 失败: " + clip.getAbsolutePath(), e);
                t("copy failed " + clip.getName() + " (" + clip.length() / 1024 + " KB): " + e);
            }
        }
        AppLog.i(TAG, "即时片段拷到 U 盘 " + copied + "/" + clips.size());
        return copied;
    }

    private static String unique(Set<String> used, String name) {
        if (used.add(name)) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int n = 2;
        String next;
        do {
            next = stem + "_" + n + ext;
            n++;
        } while (!used.add(next));
        return next;
    }

    /**
     * Copy a still-open surround file and rebuild its index from a finished sibling.
     * The live file stays untouched, because the recorder is still writing it.
     */
    private static List<File> repairOverlap(Context app, LockWindow window) {
        List<File> hits = candidates(app, window);
        File templateFile = healthyTemplate(app, hits);
        if (templateFile == null) {
            return new ArrayList<>();
        }
        Mp4Repair.Template template;
        try {
            template = Mp4Repair.templateFrom(templateFile);
        } catch (IOException e) {
            AppLog.e(TAG, "即时片段没有可参考的完好文件", e);
            return new ArrayList<>();
        }
        // Same name as the live file, in its own folder: the start time and camera are read
        // from the name. An "open-" prefix made it unreadable, so the copy never covered the
        // flash and the capture failed or cut the file before (capture log 2026-10-17).
        File cache = new File(new File(app.getCacheDir(), "instant"), OPEN_DIR);
        if (!cache.exists() && !cache.mkdirs()) {
            return new ArrayList<>();
        }
        List<File> out = new ArrayList<>();
        for (File file : hits) {
            long start = RecordingTimeline.parseStartEpochMs(file.getName());
            if (start < 0 || InstantSpan.inFile(start, window.startMs, window.endMs) == null) {
                continue;
            }
            if (UsbExport.playable(file)) {
                out.add(file);
                continue;
            }
            if (!snapshotOpenFile(Mp4Repair.scan(file).status)) {
                continue;
            }
            File copy = new File(cache, file.getName());
            try {
                copySnapshot(file, copy);
                Mp4Repair.repair(copy, template, null);
                if (UsbExport.playable(copy)) {
                    out.add(copy);
                }
            } catch (IOException e) {
                AppLog.e(TAG, "补即时片段索引失败: " + file.getAbsolutePath(), e);
                if (copy.exists() && !copy.delete()) {
                    AppLog.w(TAG, "删不掉半成品: " + copy.getAbsolutePath());
                }
            }
        }
        return out;
    }

    private static File healthyTemplate(Context app, List<File> hits) {
        for (File file : hits) {
            if (UsbExport.playable(file)) {
                return file;
            }
        }
        File video = StorageHelper.getVideoDir(app);
        if (video == null) {
            return null;
        }
        File[] listed = video.listFiles();
        if (listed == null) {
            return null;
        }
        for (File file : listed) {
            if (file.isFile() && surround(file) && UsbExport.playable(file)) {
                return file;
            }
        }
        return null;
    }

    /** Under the instant cache: repaired copies of still-open files, named like the live one. */
    private static final String OPEN_DIR = "open";

    private static void dropOpenCopies(List<File> repaired) {
        for (File extra : repaired) {
            if (extra != null && extra.getParentFile() != null
                    && OPEN_DIR.equals(extra.getParentFile().getName())
                    && extra.exists() && !extra.delete()) {
                AppLog.w(TAG, "删不掉补过索引的副本: " + extra.getAbsolutePath());
            }
        }
    }

    private static void copySnapshot(File source, File dest) throws IOException {
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) {
                    out.write(buf, 0, n);
                }
            }
        }
    }

    /** An open segment has picture bytes and no index. The cut can use a repaired copy. */
    static boolean snapshotOpenFile(Mp4Repair.Status status) {
        return status == Mp4Repair.Status.REPAIRABLE;
    }

    /** Prefer the USB root. Fall back beside the video folder when that root cannot be created. */
    static File instantFolder(File usbRoot, File videoDir) {
        File atRoot = usbRoot == null ? null : new File(usbRoot, UsbExport.INSTANT_DIR);
        if (atRoot != null && (atRoot.isDirectory() || atRoot.mkdirs())) {
            return atRoot;
        }
        if (videoDir != null && videoDir.getParentFile() != null) {
            File beside = new File(videoDir.getParentFile(), UsbExport.INSTANT_DIR);
            if (beside.isDirectory() || beside.mkdirs()) {
                return beside;
            }
        }
        return atRoot;
    }

    /** 闪远光要的是车外那一路。座舱录像留在录像目录里。 */
    private static boolean surround(File file) {
        String slot = RecordingTimeline.parseCameraSlot(file.getName());
        if (slot == null) {
            return false;
        }
        return CameraSlots.SURROUND.equals(
                CameraSlots.canonical(slot.toLowerCase(Locale.US)));
    }

    private static Map<String, Long> segmentLengths(Context context) {
        Map<String, Long> out = new HashMap<>();
        for (String key : RecordSpecs.enabledCameraKeys(context)) {
            out.put(CameraSlots.suffixFor(key),
                    RecordSpecs.segmentMs(RecordSpecs.forCameraKey(context, key).segmentMinutes));
        }
        return out;
    }

    private static long longest(Map<String, Long> lengths) {
        long longest = RecordSpecs.segmentMs(RecordSpecs.DEFAULT_SEGMENT_MINUTES);
        for (Long ms : lengths.values()) {
            if (ms != null && ms > longest) {
                longest = ms;
            }
        }
        return longest;
    }

    private static long longestSegment(Context context) {
        return longest(segmentLengths(context));
    }

    private static void t(String line) {
        CaptureLog.line(line);
    }

    private static String stampOf(long ms) {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(ms));
    }

    private static String names(List<File> files) {
        if (files == null || files.isEmpty()) {
            return "[]";
        }
        StringBuilder out = new StringBuilder("[");
        for (File f : files) {
            if (out.length() > 1) {
                out.append(", ");
            }
            out.append(f.getName()).append(' ').append(f.length() / 1024).append("KB")
                    .append(UsbExport.playable(f) ? "" : " open");
        }
        return out.append(']').toString();
    }

    /** Every surround clip in the video folder, newest last, for the failure line. */
    private static List<File> candidatesAll(Context app) {
        List<File> out = new ArrayList<>();
        File video = StorageHelper.getVideoDir(app);
        File[] listed = video == null ? null : video.listFiles();
        if (listed == null) {
            return out;
        }
        java.util.Arrays.sort(listed, (a, b) -> a.getName().compareTo(b.getName()));
        for (File file : listed) {
            if (file.isFile() && StoragePlan.isOwnClip(file.getName()) && surround(file)) {
                out.add(file);
            }
        }
        // The last few are what matters.
        return out.size() > 4 ? out.subList(out.size() - 4, out.size()) : out;
    }

    private static void toast(Context app, String text) {
        MAIN.post(() -> Toast.makeText(app, text, Toast.LENGTH_SHORT).show());
    }
}
