package com.kooo.evcam.service;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.R;

/**
 * Starts or stops recording for a shortcut (steering-wheel key, sticker, home card, Dash panel record
 * button). Has no window of its own.
 *
 * <h3>Why the name still says Floating</h3>
 *
 * <p>This used to be the floating button. Since 1.9 its buttons live on the Dash panel
 * ({@link com.kooo.evcam.overlay.DashPanelService}) and the window is gone. What stayed is the hard
 * part: starting or stopping recording whether the app is in front, in the background or not running.
 * The class kept its name so the manifest entry, the broadcast and every caller stay as they were.</p>
 */
public class RecordingFloatingService extends Service {
    private static final String TAG = "RecordingFloatingService";

    /** Start or stop recording. */
    public static final String ACTION_TOGGLE_RECORDING = "com.kooo.evcam.action.SHORTCUT_TOGGLE_RECORDING";

    /**
     * Recording changed (started, stopped, waiting for cameras). Sent by RecordingCoordinator; it is
     * only a nudge, listeners read the coordinator for the state.
     */
    public static final String ACTION_RECORDING_STATE_CHANGED = "com.kooo.evcam.RECORDING_STATE_CHANGED";

    public static void sendRecordingStateChanged(Context context) {
        Intent intent = new Intent(ACTION_RECORDING_STATE_CHANGED);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }

    private Handler mainHandler;

    // 录制服务
    private CameraRecordingService recordingService;
    private boolean isServiceBound = false;
    /** 录像意图开着（开录中、在录、等待摄像头）：按一下是停。照协调器读，见 {@link #readRecordingState}。 */
    private boolean isRecording = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            CameraRecordingService.LocalBinder binder = (CameraRecordingService.LocalBinder) service;
            recordingService = binder.getService();
            isServiceBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            recordingService = null;
            isServiceBound = false;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        new Thread(this::bindRecordingService).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (com.kooo.evcam.UserExit.blocks(this, TAG)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_TOGGLE_RECORDING.equals(intent.getAction())) {
            mainHandler.post(this::toggleRecording);
        }
        // Anything else (an old SHOW from a stale caller) has nothing to do: there is no window any more.
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (isServiceBound) {
            unbindService(serviceConnection);
        }
    }

    private void bindRecordingService() {
        Intent intent = new Intent(this, CameraRecordingService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    /** 在不在录只问协调器，和主界面录制键、前台通知同一个答案。 */
    private void readRecordingState() {
        isRecording = com.kooo.evcam.recording.RecordingCoordinator.get(this).view()
                != com.kooo.evcam.recording.RecordingCoordinator.View.IDLE;
    }

    // ========== 录制控制 ==========

    private void toggleRecording() {
        readRecordingState();
        AppLog.d(TAG, "toggleRecording called, recordingService=" + recordingService + ", isRecording=" + isRecording);

        // 获取应用状态
        AppState appState = getAppState();
        AppLog.d(TAG, "App state: " + appState);

        // 根据应用状态选择录制方式
        switch (appState) {
            case FOREGROUND:
                // 应用在前台，通过 MainActivity 停止录制
                AppLog.d(TAG, "App is in foreground, sending stop recording broadcast to MainActivity");
                Intent stopIntent = new Intent("com.kooo.evcam.action.TOGGLE_RECORDING");
                stopIntent.setPackage(getPackageName());
                sendBroadcast(stopIntent);
                return;
            case BACKGROUND:
                // 应用在后台，通过服务启动/停止录制，不拉起 MainActivity
                AppLog.d(TAG, "App is in background, starting/stopping recording via service...");
                startRecordingViaService();
                return;
            case NOT_RUNNING:
                // 应用未运行，需要启动 MainActivity
                AppLog.d(TAG, "App not running, starting MainActivity with auto_start_recording...");
                Intent intent = new Intent(this, MainActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                intent.putExtra("auto_start_recording", true);
                startActivity(intent);
                com.kooo.evcam.ui.Notice.makeText(this, R.string.msg_starting_recording,
                        Toast.LENGTH_SHORT).show();
                return;
        }
    }

    /**
     * 通过服务启动/停止录制（用于应用在后台时）
     */
    private void startRecordingViaService() {
        AppLog.d(TAG, "Recording via service... isRecording=" + isRecording);

        // 确保录制服务已启动
        Intent serviceIntent = new Intent(this, CameraRecordingService.class);
        startService(serviceIntent);

        // 如果服务未绑定，先绑定
        if (recordingService == null) {
            bindRecordingService();
        }

        // 使用递归检查确保服务绑定完成
        tryStartOrStopRecording(0);
    }

    /**
     * 尝试启动或停止录制（带重试机制）
     * @param retryCount 重试次数
     */
    private void tryStartOrStopRecording(int retryCount) {
        final int MAX_RETRIES = 5;
        final long RETRY_DELAY_MS = 500;

        if (recordingService != null) {
            // 服务已绑定，执行录制操作
            if (isRecording) {
                AppLog.d(TAG, "Stopping recording via service");
                // 在后台线程执行停止操作，避免阻塞主线程
                new Thread(() -> {
                    try {
                        recordingService.stopRecording();
                    } catch (Exception e) {
                        AppLog.e(TAG, "Error stopping recording in background", e);
                    }
                }, "StopRecordingBg").start();
            } else {
                AppLog.d(TAG, "Starting recording via service");
                recordingService.startRecording();
            }
        } else if (retryCount < MAX_RETRIES) {
            // 服务未绑定，重试
            AppLog.w(TAG, "Recording service not bound yet, retrying... (" + (retryCount + 1) + "/" + MAX_RETRIES + ")");
            bindRecordingService();
            mainHandler.postDelayed(() -> tryStartOrStopRecording(retryCount + 1), RETRY_DELAY_MS);
        } else {
            // 重试次数耗尽，显示错误
            AppLog.e(TAG, "Failed to bind recording service after " + MAX_RETRIES + " retries");
            com.kooo.evcam.ui.Notice.makeText(this, R.string.msg_recording_service_failed,
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 应用状态枚举
     */
    private enum AppState {
        FOREGROUND,     // 应用在前台运行
        BACKGROUND,     // 应用在后台运行
        NOT_RUNNING     // 应用未运行
    }

    /**
     * 获取应用当前状态
     */
    private AppState getAppState() {
        android.app.ActivityManager activityManager = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager != null) {
            java.util.List<android.app.ActivityManager.RunningTaskInfo> tasks = activityManager.getRunningTasks(10);
            if (tasks != null) {
                for (android.app.ActivityManager.RunningTaskInfo task : tasks) {
                    if (task.baseActivity != null && 
                        task.baseActivity.getPackageName().equals(getPackageName())) {
                        // 应用的任务存在
                        String topActivity = task.topActivity.getClassName();
                        if (topActivity.equals("com.kooo.evcam.MainActivity")) {
                            return AppState.FOREGROUND;
                        } else {
                            return AppState.BACKGROUND;
                        }
                    }
                }
            }
        }
        return AppState.NOT_RUNNING;
    }
}
