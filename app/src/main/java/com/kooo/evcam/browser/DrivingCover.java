package com.kooo.evcam.browser;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.kooo.evcam.R;

/**
 * The black card that hides a video while the car is driving (the browser and the Netflix app):
 * what is hidden, the warning, and a "Show for passengers" button that has to be held, so it is
 * not switched on by a stray tap. Holding it turns on {@link BrowserPrefs#setShowWhileDriving}.
 */
final class DrivingCover {

    private DrivingCover() {
    }

    /** {@code message}: what is hidden. {@code onAllowed} runs after the hold, on the main thread. */
    static View build(Context context, int message, Runnable onAllowed) {
        float d = context.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(0xFF000000);
        int pad = Math.round(20 * d);
        box.setPadding(pad, pad, pad, pad);
        box.setClickable(true);

        TextView title = new TextView(context);
        title.setText(message);
        title.setTextAppearance(R.style.TextAppearance_Cam_Body);
        title.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        TextView warning = new TextView(context);
        warning.setText(R.string.airplay_drive_warning);
        warning.setTextAppearance(R.style.TextAppearance_Cam_Caption);
        warning.setTextColor(ContextCompat.getColor(context, R.color.recording_text));
        warning.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wp.topMargin = Math.round(8 * d);
        wp.setMarginStart(Math.round(24 * d));
        wp.setMarginEnd(Math.round(24 * d));
        box.addView(warning, wp);

        TextView allow = new TextView(context);
        allow.setText(R.string.browser_show_for_passengers);
        allow.setTextAppearance(R.style.TextAppearance_Cam_Body);
        allow.setTextColor(ContextCompat.getColor(context, R.color.text_primary));
        allow.setGravity(Gravity.CENTER);
        int h = Math.round(18 * d);
        int v = Math.round(10 * d);
        allow.setPadding(h, v, h, v);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(ContextCompat.getColor(context, R.color.surface));
        shape.setStroke(Math.round(2 * d), ContextCompat.getColor(context, R.color.line));
        shape.setCornerRadius(24 * d);
        allow.setBackground(shape);
        allow.setOnClickListener(x -> Toast.makeText(context, R.string.browser_show_hold_hint, Toast.LENGTH_SHORT).show());
        allow.setOnLongClickListener(x -> {
            BrowserPrefs.setShowWhileDriving(context, true);
            onAllowed.run();
            return true;
        });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ap.topMargin = Math.round(16 * d);
        box.addView(allow, ap);
        return box;
    }
}
