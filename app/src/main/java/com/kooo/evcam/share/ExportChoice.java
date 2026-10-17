package com.kooo.evcam.share;

import android.app.Activity;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 导出前让人选这一刻的哪些画面。只有一路时直接导出。
 */
public final class ExportChoice {

    public static final class Item {
        public final String label;
        public final File file;

        public Item(String label, File file) {
            this.label = label;
            this.file = file;
        }
    }

    public interface Sink {
        void export(Activity activity, List<File> files);
    }

    private ExportChoice() {
    }

    public static void ask(Activity activity, List<Item> items, int confirm, Sink sink) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        List<Item> available = new ArrayList<>();
        if (items != null) {
            for (Item item : items) {
                if (item != null && item.file != null && item.file.isFile() && item.file.length() > 0) {
                    available.add(item);
                }
            }
        }
        if (available.isEmpty()) {
            Toast.makeText(activity, R.string.share_phone_no_file, Toast.LENGTH_SHORT).show();
            return;
        }
        if (available.size() == 1) {
            sink.export(activity, java.util.Collections.singletonList(available.get(0).file));
            return;
        }
        String[] labels = new String[available.size()];
        boolean[] checked = new boolean[available.size()];
        for (int i = 0; i < available.size(); i++) {
            labels[i] = available.get(i).label;
            checked[i] = true;
        }
        com.kooo.evcam.ui.CamDialogs.show(new MaterialAlertDialogBuilder(
                activity, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.export_pick_views)
                .setMultiChoiceItems(labels, checked, (d, which, on) -> checked[which] = on)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(confirm, (d, w) -> {
                    List<File> picked = new ArrayList<>();
                    for (int i = 0; i < available.size(); i++) {
                        if (checked[i]) {
                            picked.add(available.get(i).file);
                        }
                    }
                    if (picked.isEmpty()) {
                        Toast.makeText(activity, R.string.share_phone_no_file, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    sink.export(activity, picked);
                }));
    }
}
