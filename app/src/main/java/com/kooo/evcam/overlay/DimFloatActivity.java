package com.kooo.evcam.overlay;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/**
 * Dim test: a full-screen dark floating activity in its own task, over whatever is open. No
 * permission needed, but on Android 12+ a window of another app with any opacity can stop taps
 * from reaching the app underneath; the test shows whether this car does.
 */
public class DimFloatActivity extends Activity implements com.kooo.evcam.home.SideRail.Bare {

    private static final String EXTRA_STRENGTH = "strength";
    /** Closes by itself after this long. */
    static final long SHOW_MS = 15_000L;
    private static DimFloatActivity live;

    static void open(Context context, int strength) {
        context.startActivity(new Intent(context, DimFloatActivity.class)
                .putExtra(EXTRA_STRENGTH, strength)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
    }

    static void close() {
        if (live != null) {
            live.finish();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        live = this;
        int strength = getIntent().getIntExtra(EXTRA_STRENGTH, 50);
        setFinishOnTouchOutside(false);
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        View shade = new View(this);
        shade.setBackgroundColor(Color.BLACK);
        setContentView(shade);
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        WindowManager.LayoutParams lp = window.getAttributes();
        // at most 0.8: the most Android 12+ lets taps pass through another app's window, if it does at all
        lp.alpha = Math.min(0.8f, strength / 100f);
        window.setAttributes(lp);
        // taps may not get through it, and the row that turns it off is under it: it goes by itself
        shade.postDelayed(this::finish, SHOW_MS);
    }

    @Override
    protected void onDestroy() {
        if (live == this) {
            live = null;
            DimRoutes.floatingClosed();
        }
        super.onDestroy();
    }
}
