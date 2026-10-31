package com.kooo.evcam.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.kooo.evcam.R;

/**
 * A centred "nothing here" message: a large icon, a title and a line on what to do about it.
 * One TextView, so a screen only needs the {@code TextView} it already had for its empty text.
 */
public final class EmptyState {

    private EmptyState() {
    }

    public static void show(TextView view, int icon, int title, int hint) {
        Context c = view.getContext();
        float density = c.getResources().getDisplayMetrics().density;
        SpannableStringBuilder text = new SpannableStringBuilder();
        // the icon is part of the text, so it stays right above the title wherever the view centres it
        android.graphics.drawable.Drawable d = ContextCompat.getDrawable(c, icon);
        if (d != null) {
            d = d.mutate();
            int size = Math.round(56 * density);
            d.setBounds(0, 0, size, size);
            d.setTint(ContextCompat.getColor(c, R.color.text_tertiary));
            text.append("\uFFFC");
            text.setSpan(new android.text.style.ImageSpan(d), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.append("\n\n");
        }
        int start = text.length();
        text.append(c.getString(title));
        int end = text.length();
        text.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new AbsoluteSizeSpan(Math.round(24 * density)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(ContextCompat.getColor(c, R.color.text_primary)), start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (hint != 0) {
            text.append("\n\n").append(c.getString(hint));
        }
        view.setText(text);
        view.setGravity(android.view.Gravity.CENTER);
        view.setTextColor(ContextCompat.getColor(c, R.color.text_secondary));
    }
}
