package com.kooo.evcam.recording;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.StorageHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Dashcam "save this moment": protect the previous segment group, the one being
 * written now, and the next one after the next rollover. Protected groups are
 * never auto-deleted.
 *
 * <p>Segment length is about one minute, so this covers roughly the last minute
 * and the next minute around the press.</p>
 */
public final class SaveMoment {

    private static final String TAG = "SaveMoment";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** When true, the next completed segment's group is protected too. */
    private static volatile boolean protectNext;

    private SaveMoment() {
    }

    public static void perform(Context context) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        boolean recording = RecordingCoordinator.get(app).isRecording();
        File videoDir = StorageHelper.getVideoDir(app);
        List<String> stamps = listGroupStamps(videoDir);
        Set<String> toProtect = new LinkedHashSet<>();
        if (stamps.isEmpty()) {
            toast(app, R.string.save_moment_none);
            return;
        }
        MomentNote.start(app);
        // Newest is current (being written while recording, or last finished).
        toProtect.add(stamps.get(stamps.size() - 1));
        if (stamps.size() >= 2) {
            toProtect.add(stamps.get(stamps.size() - 2));
        }
        AppConfig config = new AppConfig(app);
        config.addSavedClipGroups(toProtect);
        if (recording) {
            protectNext = true;
            toast(app, R.string.save_moment_armed);
            AppLog.i(TAG, "已保护 " + toProtect + "，下一段切完后再保护一段");
        } else {
            protectNext = false;
            toast(app, R.string.save_moment_done);
            AppLog.i(TAG, "已保护 " + toProtect + "（当前没在录）");
        }
        boolean usb = config.isSaveMomentUsb();
        boolean drive = config.isSaveMomentDrive()
                && config.hasDriveClient() && config.hasDriveRefreshToken();
        if (usb || drive) {
            SaveMomentUpload.enqueueFinished(app, toProtect);
        } else {
            MomentNote.finish(app, recording ? R.string.save_moment_armed : R.string.save_moment_done);
        }
    }

    /** Call from the recording segment-switch path with the file that just finished. */
    public static void onSegmentCompleted(Context context, String completedFilePath) {
        if (!protectNext || completedFilePath == null) {
            return;
        }
        String stamp = SavedClips.stampOf(new File(completedFilePath).getName());
        if (stamp == null) {
            return;
        }
        protectNext = false;
        Context app = context.getApplicationContext();
        new AppConfig(app).addSavedClipGroups(Collections.singleton(stamp));
        // The file that just finished is the "current" at press time. Also protect
        // the brand-new group that just started: find newest on disk after a short wait.
        MAIN.postDelayed(() -> {
            List<String> stamps = listGroupStamps(StorageHelper.getVideoDir(app));
            if (!stamps.isEmpty()) {
                String newest = stamps.get(stamps.size() - 1);
                if (!newest.equals(stamp)) {
                    new AppConfig(app).addSavedClipGroups(Collections.singleton(newest));
                    SaveMomentUpload.enqueueFinished(app, Collections.singleton(newest));
                }
            }
            SaveMomentUpload.enqueueFinished(app, Collections.singleton(stamp));
            AppLog.i(TAG, "下一段已保护: 完成=" + stamp);
        }, 1500L);
    }

    /** Group stamps on disk, oldest first. */
    static List<String> listGroupStamps(File videoDir) {
        LinkedHashSet<String> stamps = new LinkedHashSet<>();
        File[] files = videoDir == null ? null : videoDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    String stamp = SavedClips.stampOf(file.getName());
                    if (stamp != null) {
                        stamps.add(stamp);
                    }
                }
            }
        }
        List<String> ordered = new ArrayList<>(stamps);
        Collections.sort(ordered);
        return ordered;
    }

    private static void toast(Context context, int res) {
        MAIN.post(() -> Toast.makeText(context, res, Toast.LENGTH_SHORT).show());
    }
}
