package com.kooo.evcam.share;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把导出的文件传到 Google Drive 的「Zeekr Shortcut」文件夹。
 *
 * <p>拉直开关开着时，环视先拉直再传。开关关着，或者座舱和其他相机，按原文件传。录像本身不动。
 * 车机上没有 Drive 应用，所以登录用设备码：手机打开二维码，输入屏幕上的那一串。</p>
 */
public final class DriveExport {

    private static final String TAG = "DriveExport";

    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    private static final AtomicBoolean SIGNING = new AtomicBoolean(false);

    private DriveExport() {
    }

    public static void upload(Activity activity, File file) {
        upload(activity, file == null ? null : java.util.Collections.singletonList(file));
    }

    public static void upload(Activity activity, List<File> files) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        if (files == null || files.isEmpty()) {
            toast(activity, activity.getString(R.string.share_phone_no_file));
            return;
        }
        if (BUSY.get() || SIGNING.get()) {
            toast(activity, activity.getString(R.string.drive_busy));
            return;
        }
        AppConfig config = new AppConfig(activity);
        if (!config.hasDriveClient()) {
            com.kooo.evcam.ui.CamDialogs.show(new MaterialAlertDialogBuilder(
                    activity, R.style.Theme_Cam_MaterialAlertDialog)
                    .setTitle(R.string.drive_need_client_title)
                    .setMessage(R.string.drive_need_client_msg)
                    .setPositiveButton(R.string.action_got_it, null));
            return;
        }
        List<File> batch = new ArrayList<>(files);
        if (!config.hasDriveRefreshToken()) {
            signIn(activity, () -> startUpload(activity, batch));
            return;
        }
        startUpload(activity, batch);
    }

    /**
     * Background upload with no dialog. Returns how many files were sent.
     * 0 when Drive is busy, not signed in, or every file failed / unfinished.
     * Straighten runs when that setting is on.
     */
    public static int uploadQuiet(Context context, List<File> files) {
        return uploadQuiet(context, files, true);
    }

    /**
     * Same as {@link #uploadQuiet(Context, List)}. {@code straighten} is false when the
     * caller already flattened surround clips, so they are not corrected twice.
     */
    public static int uploadQuiet(Context context, List<File> files, boolean straighten) {
        if (context == null || files == null || files.isEmpty()) {
            return 0;
        }
        AppConfig config = new AppConfig(context);
        if (!config.hasDriveClient() || !config.hasDriveRefreshToken()) {
            return 0;
        }
        if (!BUSY.compareAndSet(false, true)) {
            return 0;
        }
        Context app = context.getApplicationContext();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        int sent = 0;
        try {
            String access = accessToken(config, false);
            String folderId;
            try {
                folderId = folder(config, access);
            } catch (DriveClient.AuthExpired expired) {
                config.setDriveFolderId("");
                access = accessToken(config, true);
                folderId = folder(config, access);
            }
            for (File file : files) {
                if (file == null || !file.isFile() || file.length() <= 0 || !UsbExport.playable(file)) {
                    continue;
                }
                File payload = file;
                File temp = null;
                try {
                    if (straighten && SurroundDefish.wanted(app, file)) {
                        File dir = new File(app.getCacheDir(), "drive");
                        if (!dir.exists() && !dir.mkdirs()) {
                            throw new java.io.IOException("mkdir " + dir.getAbsolutePath());
                        }
                        temp = new File(dir, file.getName() + ".part");
                        SurroundDefish.write(app, file, temp, cancelled, null);
                        if (!UsbExport.playable(temp)) {
                            throw new java.io.IOException("unfinished " + file.getName());
                        }
                        payload = temp;
                    }
                    try {
                        DriveClient.upload(access, folderId, payload, file.getName(), null, cancelled);
                    } catch (DriveClient.AuthExpired expired) {
                        access = accessToken(config, true);
                        folderId = folder(config, access);
                        DriveClient.upload(access, folderId, payload, file.getName(), null, cancelled);
                    }
                    sent++;
                } catch (Exception e) {
                    AppLog.e(TAG, "后台上传失败: " + file.getAbsolutePath(), e);
                } finally {
                    if (temp != null && temp.exists() && !temp.delete()) {
                        AppLog.w(TAG, "删不掉临时文件: " + temp.getAbsolutePath());
                    }
                }
            }
        } catch (Exception e) {
            AppLog.e(TAG, "后台上传准备失败", e);
        } finally {
            BUSY.set(false);
        }
        return sent;
    }

    /** 设置里的「登录」。已经登录过的再点一次会换成新账号。 */
    public static void signIn(Activity activity, Runnable after) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        AppConfig config = new AppConfig(activity);
        if (!config.hasDriveClient()) {
            com.kooo.evcam.ui.CamDialogs.show(new MaterialAlertDialogBuilder(
                    activity, R.style.Theme_Cam_MaterialAlertDialog)
                    .setTitle(R.string.drive_need_client_title)
                    .setMessage(R.string.drive_need_client_msg)
                    .setPositiveButton(R.string.action_got_it, null));
            return;
        }
        if (!SIGNING.compareAndSet(false, true)) {
            toast(activity, activity.getString(R.string.drive_busy));
            return;
        }
        Context app = activity.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            DriveProtocol.DeviceCode code;
            try {
                code = DriveClient.requestDeviceCode(config.getDriveClientId());
            } catch (Exception e) {
                AppLog.w(TAG, "申请设备码失败: " + e);
                main.post(() -> {
                    SIGNING.set(false);
                    say(activity, app, R.string.drive_sign_in_failed, String.valueOf(e.getMessage()));
                });
                return;
            }
            main.post(() -> showCode(activity, app, config, code, after));
        }, "drive-sign-in").start();
    }

    private static void showCode(Activity activity, Context app, AppConfig config,
                                 DriveProtocol.DeviceCode code, Runnable after) {
        if (activity.isFinishing()) {
            SIGNING.set(false);
            return;
        }
        float density = activity.getResources().getDisplayMetrics().density;
        int pad = Math.round(24 * density);
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad / 2, pad, 0);
        column.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView qr = new ImageView(activity);
        int qrPx = Math.round(220 * density);
        Bitmap bitmap = QrCode.encode(code.qrUrl, qrPx);
        if (bitmap != null) {
            qr.setImageBitmap(bitmap);
            LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(qrPx, qrPx);
            qrLp.gravity = Gravity.CENTER_HORIZONTAL;
            qrLp.bottomMargin = Math.round(12 * density);
            column.addView(qr, qrLp);
        }

        TextView userCode = new TextView(activity);
        userCode.setText(code.userCode);
        userCode.setGravity(Gravity.CENTER);
        userCode.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        userCode.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        userCode.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
        column.addView(userCode);

        TextView steps = new TextView(activity);
        steps.setText(activity.getString(R.string.drive_sign_in_msg, code.userCode, code.verificationUrl));
        steps.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        steps.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary));
        LinearLayout.LayoutParams stepsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stepsLp.topMargin = Math.round(8 * density);
        column.addView(steps, stepsLp);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        AlertDialog dialog = com.kooo.evcam.ui.CamDialogs.show(new MaterialAlertDialogBuilder(
                activity, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.drive_sign_in_title)
                .setView(column)
                .setNegativeButton(R.string.action_cancel, (d, w) -> cancelled.set(true))
                .setOnCancelListener(d -> cancelled.set(true)));

        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            long deadline = System.currentTimeMillis() + code.expiresInSec * 1000L;
            int waitSec = code.intervalSec;
            String failure = null;
            boolean granted = false;
            while (!cancelled.get() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(waitSec * 1000L);
                } catch (InterruptedException e) {
                    break;
                }
                if (cancelled.get()) {
                    break;
                }
                try {
                    DriveClient.Reply reply = DriveClient.pollOnce(
                            config.getDriveClientId(), config.getDriveClientSecret(), code.deviceCode);
                    DriveProtocol.Poll poll = DriveClient.poll(reply.body);
                    if (poll == DriveProtocol.Poll.PENDING) {
                        continue;
                    }
                    if (poll == DriveProtocol.Poll.SLOW_DOWN) {
                        waitSec += 5;
                        continue;
                    }
                    if (poll == DriveProtocol.Poll.GRANTED) {
                        DriveProtocol.Tokens tokens = DriveProtocol.parseTokens(
                                reply.body, "", System.currentTimeMillis());
                        if (tokens == null || tokens.refreshToken.isEmpty()) {
                            failure = "no refresh token";
                        } else {
                            config.setDriveTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresAtMs);
                            granted = true;
                        }
                        break;
                    }
                    failure = reply.body;
                    break;
                } catch (Exception e) {
                    failure = String.valueOf(e.getMessage());
                    break;
                }
            }
            final boolean ok = granted;
            final String error = failure;
            main.post(() -> {
                SIGNING.set(false);
                if (dialog.isShowing()) {
                    dialog.dismiss();
                }
                if (cancelled.get()) {
                    return;
                }
                if (ok) {
                    say(activity, app, R.string.drive_signed_in);
                    if (after != null) {
                        after.run();
                    }
                } else if (error != null) {
                    say(activity, app, R.string.drive_sign_in_failed, error);
                }
            });
        }, "drive-poll").start();
    }

    private static void startUpload(Activity activity, List<File> files) {
        if (activity.isFinishing() || !BUSY.compareAndSet(false, true)) {
            if (!activity.isFinishing()) {
                toast(activity, activity.getString(R.string.drive_busy));
            }
            return;
        }
        boolean defish = false;
        for (File file : files) {
            if (SurroundDefish.wanted(activity, file)) {
                defish = true;
                break;
            }
        }
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setIndeterminate(!defish);
        bar.setProgress(0);
        TextView label = new TextView(activity);
        label.setText(activity.getString(defish
                ? R.string.drive_uploading_defish : R.string.drive_uploading));
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                activity.getResources().getDimension(R.dimen.text_body));
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24,
                activity.getResources().getDisplayMetrics());
        box.setPadding(pad, pad, pad, pad);
        box.addView(label, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(bar, barLayout(activity));
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AlertDialog dialog = com.kooo.evcam.ui.CamDialogs.style(new MaterialAlertDialogBuilder(
                activity, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.drive_progress_title)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton(R.string.action_cancel, (d, w) -> {
                    cancelled.set(true);
                    DriveClient.abortPut();
                })
                .create());
        dialog.show();

        Context app = activity.getApplicationContext();
        AppConfig config = new AppConfig(app);
        Handler main = new Handler(Looper.getMainLooper());
        int fileCount = 0;
        long totalBytes = 0;
        for (File file : files) {
            if (file != null && file.isFile() && file.length() > 0 && UsbExport.playable(file)) {
                fileCount++;
                totalBytes += file.length();
            }
        }
        final int filesTotal = fileCount;
        final long[] bytesTotal = {Math.max(1, totalBytes)};
        final long[] bytesDone = {0};
        final long[] lastUiMs = {0};
        new Thread(() -> {
            int sent = 0;
            int unfinished = 0;
            String failure = null;
            try {
                String access = accessToken(config, false);
                String folder;
                try {
                    folder = folder(config, access);
                } catch (DriveClient.AuthExpired expired) {
                    config.setDriveFolderId("");
                    access = accessToken(config, true);
                    folder = folder(config, access);
                }
                int seen = 0;
                for (File file : files) {
                    if (cancelled.get()) {
                        break;
                    }
                    if (file == null || !file.isFile() || file.length() <= 0) {
                        continue;
                    }
                    if (!UsbExport.playable(file)) {
                        unfinished++;
                        continue;
                    }
                    seen++;
                    File payload = file;
                    File temp = null;
                    final int fileIndex = seen;
                    try {
                        if (SurroundDefish.wanted(app, file)) {
                            final String straightening = file.getName();
                            File dir = new File(app.getCacheDir(), "drive");
                            if (!dir.exists() && !dir.mkdirs()) {
                                throw new java.io.IOException("mkdir " + dir.getAbsolutePath());
                            }
                            temp = new File(dir, straightening + ".part");
                            main.post(() -> showStraighten(activity, bar, label, straightening, 0));
                            SurroundDefish.write(app, file, temp, cancelled, pct -> {
                                long now = android.os.SystemClock.uptimeMillis();
                                if (pct < 100 && now - lastUiMs[0] < 200) {
                                    return;
                                }
                                lastUiMs[0] = now;
                                main.post(() -> showStraighten(activity, bar, label, straightening, pct));
                            });
                            if (!UsbExport.playable(temp)) {
                                throw new java.io.IOException("unfinished " + file.getName());
                            }
                            payload = temp;
                            bytesTotal[0] += payload.length() - file.length();
                        }
                        DriveClient.Progress progress = (done, total) -> {
                            long overall = bytesDone[0] + done;
                            long now = android.os.SystemClock.uptimeMillis();
                            if (done < total && now - lastUiMs[0] < 200) {
                                return;
                            }
                            lastUiMs[0] = now;
                            long shownTotal = Math.max(overall, bytesTotal[0]);
                            main.post(() -> showProgress(activity, bar, label, fileIndex, filesTotal,
                                    file.getName(), overall, shownTotal));
                        };
                        try {
                            DriveClient.upload(access, folder, payload, file.getName(), progress, cancelled);
                        } catch (DriveClient.AuthExpired expired) {
                            if (cancelled.get()) {
                                break;
                            }
                            access = accessToken(config, true);
                            folder = folder(config, access);
                            DriveClient.upload(access, folder, payload, file.getName(), progress, cancelled);
                        }
                        bytesDone[0] += payload.length();
                        sent++;
                    } catch (Exception e) {
                        if (cancelled.get()) {
                            break;
                        }
                        AppLog.e(TAG, "上传失败: " + file.getAbsolutePath(), e);
                        failure = String.valueOf(e.getMessage());
                    } finally {
                        if (temp != null && temp.exists() && !temp.delete()) {
                            AppLog.w(TAG, "删不掉临时文件: " + temp.getAbsolutePath());
                        }
                    }
                }
            } catch (Exception e) {
                if (!cancelled.get()) {
                    AppLog.e(TAG, "上传准备失败", e);
                    failure = String.valueOf(e.getMessage());
                }
            }
            final int sentCount = sent;
            final int unfinishedCount = unfinished;
            final String error = failure;
            main.post(() -> {
                if (!activity.isFinishing() && dialog.isShowing()) {
                    dialog.dismiss();
                }
                if (cancelled.get()) {
                    say(activity, app, R.string.drive_upload_cancelled);
                } else if (sentCount > 0) {
                    say(activity, app, R.string.drive_uploaded_count, sentCount);
                } else if (error != null) {
                    say(activity, app, R.string.drive_upload_failed, error);
                } else if (unfinishedCount == 0) {
                    say(activity, app, R.string.share_phone_no_file);
                }
                if (!cancelled.get() && unfinishedCount > 0) {
                    say(activity, app, R.string.drive_unfinished, unfinishedCount);
                }
                BUSY.set(false);
            });
        }, "drive-upload").start();
    }

    private static LinearLayout.LayoutParams barLayout(Activity activity) {
        int height = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20,
                activity.getResources().getDisplayMetrics());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, height);
        lp.topMargin = height / 2;
        return lp;
    }

    private static void showStraighten(Activity activity, ProgressBar bar, TextView label,
                                       String name, int percent) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        bar.setIndeterminate(false);
        bar.setProgress(percent);
        label.setText(activity.getString(R.string.drive_progress_prepare, name, percent));
    }

    private static void showProgress(Activity activity, ProgressBar bar, TextView label,
                                     int index, int count, String name, long done, long total) {
        if (activity.isFinishing()) {
            return;
        }
        int percent = total <= 0 ? 0 : (int) Math.min(100, done * 100 / total);
        bar.setIndeterminate(false);
        bar.setProgress(percent);
        label.setText(activity.getString(R.string.drive_progress_file,
                index, Math.max(count, 1), name, percent, mb(done), mb(total)));
    }

    private static String mb(long bytes) {
        return String.format(java.util.Locale.US, "%.1f", bytes / 1024f / 1024f);
    }

    private static String accessToken(AppConfig config, boolean forceRefresh) throws java.io.IOException {
        long now = System.currentTimeMillis();
        if (!forceRefresh && DriveProtocol.accessFresh(
                config.getDriveAccessToken(), config.getDriveAccessExpiry(), now)) {
            return config.getDriveAccessToken();
        }
        DriveProtocol.Tokens tokens = DriveClient.refresh(
                config.getDriveClientId(), config.getDriveClientSecret(),
                config.getDriveRefreshToken(), now);
        config.setDriveTokens(tokens.accessToken, tokens.refreshToken, tokens.expiresAtMs);
        return tokens.accessToken;
    }

    private static String folder(AppConfig config, String access) throws java.io.IOException {
        String cached = config.getDriveFolderId();
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        String id = DriveClient.ensureFolder(access);
        config.setDriveFolderId(id);
        return id;
    }

    /** 字从界面上取。Application 上下文不认应用内语言，取出来的是默认中文。 */
    private static void say(Activity activity, Context fallback, int res, Object... args) {
        Context ui = activity != null && !activity.isDestroyed() ? activity : fallback;
        Toast.makeText(fallback, ui.getString(res, args), Toast.LENGTH_LONG).show();
    }

    private static void toast(Context context, String text) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show();
    }
}
