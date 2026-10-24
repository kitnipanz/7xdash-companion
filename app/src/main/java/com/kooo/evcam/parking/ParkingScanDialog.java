package com.kooo.evcam.parking;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.R;
import com.kooo.evcam.ui.CamDialogs;

/**
 * Hold the Where I parked card: scan the pillar signs once by hand and see what was read
 * (the photo, this scan's floor and bay, what the pillar scan remembers, the raw text).
 *
 * <p>A dialog over the main screen, not an activity: the frames come from its surround
 * preview, which must stay up.</p>
 */
public final class ParkingScanDialog {

    private ParkingScanDialog() {
    }

    public static void show(Context context) {
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_parking_scan, null);
        ImageView photo = view.findViewById(R.id.parking_scan_photo);
        TextView result = view.findViewById(R.id.parking_scan_result);
        TextView detail = view.findViewById(R.id.parking_scan_detail);
        result.setText(context.getString(R.string.parking_scan_remembered,
                label(context, WhereParked.get().remembered())));

        AlertDialog dialog = CamDialogs.show(new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.parking_scan_title)
                .setView(view)
                .setPositiveButton(R.string.parking_scan_now, null)
                .setNegativeButton(R.string.action_close, null));
        Bitmap[] shown = new Bitmap[1];
        dialog.setOnDismissListener(d -> {
            photo.setImageDrawable(null);
            if (shown[0] != null) {
                shown[0].recycle();
                shown[0] = null;
            }
        });
        // Scan keeps the dialog open; set after show so the button doesn't dismiss it
        Button scan = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        scan.setOnClickListener(v -> {
            scan.setEnabled(false);
            result.setText(R.string.parking_scan_running);
            detail.setText("");
            WhereParked.get().scanNow(context, r -> {
                if (!dialog.isShowing()) {
                    if (r.picture != null) {
                        r.picture.recycle();
                    }
                    return;
                }
                scan.setEnabled(true);
                showResult(context, r, photo, result, detail, shown);
            });
        });
    }

    private static void showResult(Context context, WhereParked.ScanResult r, ImageView photo,
            TextView result, TextView detail, Bitmap[] shown) {
        String remembered = context.getString(R.string.parking_scan_remembered, label(context, r.remembered));
        if (!r.ok) {
            result.setText(context.getString(R.string.parking_no_camera) + "\n" + remembered);
            detail.setText("");
            return;
        }
        photo.setImageBitmap(r.picture);
        if (shown[0] != null && shown[0] != r.picture) {
            shown[0].recycle();
        }
        shown[0] = r.picture;
        photo.setVisibility(r.picture == null ? View.GONE : View.VISIBLE);

        result.setText(context.getString(R.string.parking_scan_this, label(context, r.spot)) + "\n" + remembered);
        StringBuilder text = new StringBuilder();
        text.append(DateFormat.getTimeFormat(context).format(r.at));
        if (!r.bestLane.isEmpty()) {
            text.append("  ·  ").append(context.getString(R.string.parking_scan_best, r.bestLane));
        }
        text.append('\n').append(context.getString(R.string.parking_scan_web));
        text.append("\n\n").append(context.getString(R.string.parking_scan_lines, r.lines.size()));
        for (String line : r.lines) {
            text.append('\n').append(line);
        }
        detail.setText(text);
    }

    private static String label(Context context, ParkingText.Spot spot) {
        return spot == null || spot.isEmpty() ? context.getString(R.string.parking_scan_nothing) : spot.label();
    }
}
