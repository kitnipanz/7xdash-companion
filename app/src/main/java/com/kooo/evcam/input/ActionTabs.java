package com.kooo.evcam.input;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.kooo.evcam.R;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The action picker for buttons and stickers: a row of tabs ({@link ShortcutAction.Group}) and,
 * under it, the actions on the picked tab, two to a row.
 */
final class ActionTabs {

    interface Pick {
        void action(ShortcutAction action);
    }

    private final LinearLayout tabs;
    private final LinearLayout list;
    private final int rowHeight;
    private final ShortcutAction marked;
    private final Pick pick;
    private ShortcutAction.Group shown;

    private ActionTabs(LinearLayout tabs, LinearLayout list, int rowHeightDp,
                       ShortcutAction marked, Pick pick) {
        this.tabs = tabs;
        this.list = list;
        this.rowHeight = dp(tabs.getResources(), rowHeightDp);
        this.marked = marked;
        this.pick = pick;
    }

    /**
     * Fills {@code tabs} and {@code list}. Opens on the tab of {@code marked} (may be null), and
     * that action is drawn as the current one.
     */
    static void fill(LinearLayout tabs, LinearLayout list, int rowHeightDp,
                     ShortcutAction marked, Pick pick) {
        ActionTabs view = new ActionTabs(tabs, list, rowHeightDp, marked, pick);
        view.buildTabs();
        view.show(marked == null ? ShortcutAction.Group.values()[0] : marked.group());
    }

    private void buildTabs() {
        tabs.removeAllViews();
        int gap = dp(tabs.getResources(), 8);
        for (ShortcutAction.Group group : ShortcutAction.Group.values()) {
            MaterialButton tab = button(tabs.getContext());
            tab.setText(group.labelRes);
            tab.setIconResource(group.iconRes);
            tab.setTag(group);
            tab.setOnClickListener(v -> show(group));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, rowHeight, 1f);
            params.setMarginEnd(group.ordinal() == ShortcutAction.Group.values().length - 1 ? 0 : gap);
            tabs.addView(tab, params);
        }
    }

    private void show(ShortcutAction.Group group) {
        shown = group;
        for (int i = 0; i < tabs.getChildCount(); i++) {
            paintTab((MaterialButton) tabs.getChildAt(i));
        }
        list.removeAllViews();
        Context context = list.getContext();
        Resources res = list.getResources();
        int gap = dp(res, 8);
        List<ShortcutAction> actions = sorted(context, ShortcutAction.in(group));
        LinearLayout row = null;
        for (int i = 0; i < actions.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, rowHeight);
                rowParams.bottomMargin = gap;
                list.addView(row, rowParams);
            }
            ShortcutAction action = actions.get(i);
            MaterialButton button = button(context);
            button.setText(action.labelRes);
            button.setIconResource(action.iconRes);
            // A list to read down: icon and name on the left, not centred.
            button.setIconGravity(MaterialButton.ICON_GRAVITY_START);
            button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            button.setPaddingRelative(dp(res, 16), 0, dp(res, 12), 0);
            button.setIconPadding(dp(res, 14));
            button.setMaxLines(2);
            button.setOnClickListener(v -> pick.action(action));
            if (action == marked) {
                button.setStrokeWidth(dp(res, 2));
                button.setStrokeColorResource(R.color.text_primary);
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, rowHeight, 1f);
            if (i % 2 == 0) {
                params.setMarginEnd(gap);
            }
            row.addView(button, params);
        }
        if (actions.size() % 2 == 1) {
            // Keeps the last button half width, like the others.
            row.addView(new View(context), new LinearLayout.LayoutParams(0, rowHeight, 1f));
        }
    }

    /** The picked tab is solid orange, the others outlined, so the row reads as tabs, not as choices. */
    private void paintTab(MaterialButton tab) {
        boolean on = tab.getTag() == shown;
        Context context = tab.getContext();
        tab.setSelected(on);
        tab.setBackgroundTintList(ColorStateList.valueOf(on
                ? ContextCompat.getColor(context, R.color.energy) : Color.TRANSPARENT));
        int text = ContextCompat.getColor(context, on ? R.color.on_energy : R.color.text_secondary);
        tab.setTextColor(text);
        tab.setIconTint(ColorStateList.valueOf(text));
        tab.setStrokeColorResource(R.color.line);
        tab.setStrokeWidth(on ? 0 : dp(tab.getResources(), 1));
    }

    /** By name as shown, in the app's language. */
    static List<ShortcutAction> sorted(Context context, List<ShortcutAction> actions) {
        Collator collator = Collator.getInstance(context.getResources().getConfiguration().getLocales().get(0));
        List<ShortcutAction> out = new ArrayList<>(actions);
        Collections.sort(out, (a, b) -> collator.compare(context.getString(a.labelRes),
                context.getString(b.labelRes)));
        return out;
    }

    private static MaterialButton button(Context context) {
        return new MaterialButton(new ContextThemeWrapper(context, R.style.ThemeOverlay_Cam_RailSmall),
                null, com.google.android.material.R.attr.materialButtonStyle);
    }

    private static int dp(Resources res, int dp) {
        return Math.round(dp * res.getDisplayMetrics().density);
    }
}
