package com.kooo.evcam.announce;

import android.app.Activity;
import android.content.Context;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.R;
import com.kooo.evcam.input.ShortcutAction;

import java.util.List;
import java.util.function.BiConsumer;

/** The "Play an announcement" shortcut: choosing which one, and naming it in shortcut lists. */
public final class AnnouncePick {

    private AnnouncePick() {
    }

    /**
     * Asks which announcement the button should play, then hands back the stored action key and the
     * line to show for it.
     */
    public static void choose(Activity activity, BiConsumer<String, String> chosen) {
        List<Announcement> all = new AnnouncementStore(activity).all();
        String[] names = new String[all.size()];
        for (int i = 0; i < all.size(); i++) {
            names[i] = Labels.of(activity, all.get(i));
        }
        com.kooo.evcam.ui.CamDialogs.show(new MaterialAlertDialogBuilder(activity, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.announce_pick_title)
                .setItems(names, (dialog, which) -> {
                    Announcement a = all.get(which);
                    String key = ShortcutAction.keyWith(ShortcutAction.ANNOUNCE_PLAY, a.id);
                    chosen.accept(key, label(activity, key));
                })
                .setNegativeButton(android.R.string.cancel, null));
    }

    /** "Play: Siren" for an announce-play key, null for any other action. */
    public static String label(Context context, String actionKey) {
        if (ShortcutAction.fromKey(actionKey) != ShortcutAction.ANNOUNCE_PLAY) {
            return null;
        }
        Announcement a = new AnnouncementStore(context).find(ShortcutAction.choice(actionKey));
        return context.getString(R.string.announce_play_named,
                a == null ? context.getString(R.string.announce_missing) : Labels.of(context, a));
    }
}
