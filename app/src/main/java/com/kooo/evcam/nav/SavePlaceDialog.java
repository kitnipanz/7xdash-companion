package com.kooo.evcam.nav;

import android.content.Context;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.R;
import com.kooo.evcam.automation.Automation;
import com.kooo.evcam.ui.CamDialogs;
import com.kooo.evcam.ui.Notice;

import java.util.List;
import java.util.function.Consumer;

/** Asks what to call a place and saves it. */
public final class SavePlaceDialog {

    private SavePlaceDialog() {
    }

    /** {@code saved} gets the new place; never called when the person cancels. */
    public static void ask(Context context, double lat, double lng, String name, Consumer<SavedPlaces.Place> saved) {
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setText(name);
        input.setHint(R.string.place_name_hint);
        input.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(context);
        int pad = Math.round(24 * context.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        CamDialogs.show(new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.place_save_title)
                .setView(box)
                .setPositiveButton(R.string.place_save, (dialog, which) -> {
                    String named = input.getText().toString().trim();
                    if (named.isEmpty()) {
                        return;
                    }
                    List<SavedPlaces.Place> places = SavedPlaces.load(context);
                    SavedPlaces.Place place = SavedPlaces.add(places, named, lat, lng);
                    SavedPlaces.save(context, places);
                    Automation.reload();
                    Notice.makeText(context, context.getString(R.string.place_saved, named), Toast.LENGTH_SHORT).show();
                    saved.accept(place);
                })
                .setNegativeButton(android.R.string.cancel, null));
    }
}
