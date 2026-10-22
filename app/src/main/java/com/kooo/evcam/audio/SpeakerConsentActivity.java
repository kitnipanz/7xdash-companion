package com.kooo.evcam.audio;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

import java.lang.ref.WeakReference;

/** No window of its own: shows the system "start recording or casting" prompt and passes the answer on. */
public class SpeakerConsentActivity extends Activity {

    private static final int REQUEST = 7103;
    private static WeakReference<SpeakerConsentActivity> shown = new WeakReference<>(null);

    private boolean answered;

    /** The speaker was turned off before the driver answered. */
    static void dismiss() {
        SpeakerConsentActivity activity = shown.get();
        if (activity != null && !activity.isFinishing()) {
            activity.finishActivity(REQUEST);
            activity.finish();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        shown = new WeakReference<>(this);
        if (savedInstanceState != null) {
            return;
        }
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        if (manager == null) {
            answer(RESULT_CANCELED, null);
            return;
        }
        try {
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST);
        } catch (RuntimeException e) {
            answer(RESULT_CANCELED, null);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST) {
            answer(resultCode, data);
        }
    }

    @Override
    protected void onDestroy() {
        if (!answered && isFinishing()) {
            OutsideSpeakerService.answered(getApplicationContext(), RESULT_CANCELED, null);
        }
        super.onDestroy();
    }

    private void answer(int resultCode, Intent data) {
        answered = true;
        OutsideSpeakerService.answered(getApplicationContext(), resultCode, data);
        finish();
        overridePendingTransition(0, 0);
    }
}
