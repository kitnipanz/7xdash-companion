package com.kooo.evcam.home;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.ui.CamDialogs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Customize Home: what is on the page, left, in its order; what is not, right. Tabs split the
 * cards into stable and beta, and go away while Beta features is off, because every card offered
 * is then stable. Every change saves at once, so there is nothing to lose by leaving. {@link HomeFragment} hands over the cards
 * it offers now, so this page never has its own list to keep in step with the home page.
 */
public class HomeEditActivity extends AppCompatActivity {

    static final String EXTRA_IDS = "ids";
    static final String EXTRA_TITLES = "titles";
    static final String EXTRA_ICONS = "icons";
    /** Index in the id list where the beta cards start. */
    static final String EXTRA_FIRST_BETA = "first_beta";
    private static final String KEPT_BETA_TAB = "beta_tab";
    /** Same as the home page on a head unit, so a card sits where it will on Home. */
    private static final int COLUMNS = 4;

    private final Map<String, Integer> titles = new HashMap<>();
    private final Map<String, Integer> icons = new HashMap<>();
    private final List<String> shown = new ArrayList<>();
    private final List<String> more = new ArrayList<>();
    /** The cards of the open tab, as the two lists show them. */
    private final List<String> tabShown = new ArrayList<>();
    private final List<String> tabMore = new ArrayList<>();
    private final Set<String> betaIds = new HashSet<>();
    private boolean betaTab;
    private List<String> all;
    private LinearLayout tabs;
    private ItemTouchHelper dragger;
    private AppConfig config;
    private TextView shownTitle;
    private TextView moreHint;
    private final Tiles shownTiles = new Tiles(tabShown, R.drawable.ic_close, R.string.home_edit_remove);
    private final Tiles moreTiles = new Tiles(tabMore, R.drawable.ic_add, R.string.home_edit_add);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home_edit);
        config = new AppConfig(this);
        all = getIntent().getStringArrayListExtra(EXTRA_IDS);
        int[] titleRes = getIntent().getIntArrayExtra(EXTRA_TITLES);
        int[] iconRes = getIntent().getIntArrayExtra(EXTRA_ICONS);
        for (int i = 0; i < all.size(); i++) {
            titles.put(all.get(i), titleRes[i]);
            icons.put(all.get(i), iconRes[i]);
        }
        int firstBeta = getIntent().getIntExtra(EXTRA_FIRST_BETA, all.size());
        betaIds.addAll(all.subList(firstBeta, all.size()));
        betaTab = savedInstanceState != null && savedInstanceState.getBoolean(KEPT_BETA_TAB);
        tabs = findViewById(R.id.home_edit_tabs);
        shownTitle = findViewById(R.id.home_edit_shown_title);
        moreHint = findViewById(R.id.home_edit_more_hint);
        RecyclerView shownList = findViewById(R.id.home_edit_shown);
        shownList.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        shownList.setAdapter(shownTiles);
        dragger = new ItemTouchHelper(new Drag());
        dragger.attachToRecyclerView(shownList);
        RecyclerView moreList = findViewById(R.id.home_edit_more);
        moreList.setLayoutManager(new LinearLayoutManager(this));
        moreList.setAdapter(moreTiles);
        findViewById(R.id.home_edit_close).setOnClickListener(v -> finish());
        findViewById(R.id.home_edit_done).setOnClickListener(v -> finish());
        findViewById(R.id.home_edit_reset).setOnClickListener(v -> askReset());
        load();
    }

    private void load() {
        List<String> order = HomeCardOrder.parse(config.getHomeCardOrder());
        Set<String> hidden = new HashSet<>(HomeCardOrder.parse(config.getHomeCardsHidden()));
        shown.clear();
        shown.addAll(HomeCardOrder.visible(all, order, hidden));
        more.clear();
        more.addAll(HomeCardOrder.hidden(all, hidden));
        paint();
    }

    /** The screen is rebuilt when the car's day or night theme changes: stay on the same tab. */
    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean(KEPT_BETA_TAB, betaTab);
    }

    private void paint() {
        tabShown.clear();
        tabShown.addAll(inTab(shown, betaTab));
        tabMore.clear();
        tabMore.addAll(inTab(more, betaTab));
        shownTiles.notifyDataSetChanged();
        moreTiles.notifyDataSetChanged();
        shownTitle.setText(getString(R.string.home_edit_shown, tabShown.size()));
        moreHint.setText(tabMore.isEmpty() ? R.string.home_edit_more_empty : R.string.home_edit_more_hint);
        paintTabs();
    }

    private List<String> inTab(List<String> ids, boolean beta) {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            if (betaIds.contains(id) == beta) {
                out.add(id);
            }
        }
        return out;
    }

    private void paintTabs() {
        tabs.removeAllViews();
        if (betaIds.isEmpty()) {
            tabs.setVisibility(View.GONE);
            return;
        }
        tabs.setVisibility(View.VISIBLE);
        tabs.addView(tab(R.string.home_edit_tab_stable, false));
        tabs.addView(tab(R.string.home_edit_tab_beta, true));
    }

    /** The open tab is solid orange, the other outlined, like the tabs of the action picker. */
    private MaterialButton tab(int label, boolean beta) {
        MaterialButton tab = new MaterialButton(new ContextThemeWrapper(this, R.style.ThemeOverlay_Cam_RailSmall),
                null, com.google.android.material.R.attr.materialButtonStyle);
        int count = inTab(all, beta).size();
        tab.setText(getString(label, count));
        boolean on = betaTab == beta;
        tab.setBackgroundTintList(ColorStateList.valueOf(on
                ? ContextCompat.getColor(this, R.color.energy) : Color.TRANSPARENT));
        tab.setTextColor(ContextCompat.getColor(this, on ? R.color.on_energy : R.color.text_secondary));
        tab.setStrokeColorResource(R.color.line);
        tab.setStrokeWidth(on ? 0 : dp(1));
        tab.setOnClickListener(v -> {
            betaTab = beta;
            paint();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(180), dp(48));
        params.setMarginEnd(dp(8));
        tab.setLayoutParams(params);
        return tab;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void remove(String id) {
        shown.remove(id);
        Set<String> hidden = new HashSet<>(HomeCardOrder.parse(config.getHomeCardsHidden()));
        hidden.add(id);
        config.setHomeCardsHidden(HomeCardOrder.write(hidden));
        saveOrder();
        load();
    }

    /** Back at the end of the page: that is where the person is looking for it. */
    private void add(String id) {
        shown.add(id);
        Set<String> hidden = new HashSet<>(HomeCardOrder.parse(config.getHomeCardsHidden()));
        hidden.remove(id);
        config.setHomeCardsHidden(HomeCardOrder.write(hidden));
        saveOrder();
        load();
    }

    private void saveOrder() {
        config.setHomeCardOrder(HomeCardOrder.write(
                HomeCardOrder.merged(shown, HomeCardOrder.parse(config.getHomeCardOrder()))));
    }

    private void askReset() {
        CamDialogs.show(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.home_edit_reset_title)
                .setPositiveButton(R.string.home_edit_reset, (dialog, which) -> {
                    config.setHomeCardOrder(null);
                    config.setHomeCardsHidden(null);
                    load();
                })
                .setNegativeButton(android.R.string.cancel, null));
    }

    /** Hold a tile and drag it; the order saves when it is let go. */
    private final class Drag extends ItemTouchHelper.SimpleCallback {

        Drag() {
            super(ItemTouchHelper.UP | ItemTouchHelper.DOWN | ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT, 0);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView list, @NonNull RecyclerView.ViewHolder from,
                              @NonNull RecyclerView.ViewHolder to) {
            int a = from.getBindingAdapterPosition();
            int b = to.getBindingAdapterPosition();
            if (a < 0 || b < 0) {
                return false;
            }
            tabShown.add(b, tabShown.remove(a));
            shownTiles.notifyItemMoved(a, b);
            return true;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder holder, int direction) {
            // No swipe: a card only leaves through its ×
        }

        @Override
        public void clearView(@NonNull RecyclerView list, @NonNull RecyclerView.ViewHolder holder) {
            super.clearView(list, holder);
            List<String> placed = HomeCardOrder.withTabOrder(shown, tabShown);
            shown.clear();
            shown.addAll(placed);
            saveOrder();
        }
    }

    private final class Tiles extends RecyclerView.Adapter<Tile> {

        private final List<String> ids;
        private final int actionIcon;
        private final int actionLabel;

        Tiles(List<String> ids, int actionIcon, int actionLabel) {
            this.ids = ids;
            this.actionIcon = actionIcon;
            this.actionLabel = actionLabel;
        }

        @NonNull
        @Override
        public Tile onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Tile(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_home_edit_tile, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Tile tile, int position) {
            String id = ids.get(position);
            String name = getString(titles.get(id));
            tile.title.setText(name);
            tile.icon.setImageResource(icons.get(id));
            tile.action.setImageResource(actionIcon);
            tile.action.setContentDescription(getString(actionLabel, name));
            boolean adds = ids == tabMore;
            tile.grip.setVisibility(adds ? View.INVISIBLE : View.VISIBLE);
            tile.grip.setOnTouchListener(adds ? null : (v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    dragger.startDrag(tile);
                }
                return false;
            });
            View.OnClickListener tap = v -> {
                if (adds) {
                    add(id);
                } else {
                    remove(id);
                }
            };
            tile.action.setOnClickListener(tap);
            // A hidden card adds from anywhere on it; a card on Home goes only through its ×
            tile.itemView.setOnClickListener(adds ? tap : null);
            tile.itemView.setClickable(adds);
        }

        @Override
        public int getItemCount() {
            return ids.size();
        }
    }

    private static final class Tile extends RecyclerView.ViewHolder {
        final View grip;
        final ImageView icon;
        final TextView title;
        final ImageView action;

        Tile(View view) {
            super(view);
            grip = view.findViewById(R.id.tile_grip);
            icon = view.findViewById(R.id.tile_icon);
            title = view.findViewById(R.id.tile_title);
            action = view.findViewById(R.id.tile_action);
        }
    }
}
