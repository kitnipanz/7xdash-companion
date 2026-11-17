package com.kooo.evcam.screen;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.kooo.evcam.R;
import com.kooo.evcam.ui.Notice;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;

/**
 * Open an app in a window: every app on the head unit, tap one to open it floating, split or full
 * screen ({@link AppWindow}), whichever the head unit allows. The top line says which that is.
 */
public class AppWindowActivity extends AppCompatActivity {

    private static final class App {
        final ComponentName component;
        final CharSequence label;
        final Drawable icon;

        App(ComponentName component, CharSequence label, Drawable icon) {
            this.component = component;
            this.label = label;
            this.icon = icon;
        }
    }

    private final List<App> apps = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_window);
        findViewById(R.id.app_window_close).setOnClickListener(v -> finish());
        TextView mode = findViewById(R.id.app_window_mode);
        switch (AppWindow.modeFor(MultiWindowProbe.probe(this))) {
            case FLOATING:
                mode.setText(R.string.app_window_mode_floating);
                break;
            case SPLIT:
                mode.setText(R.string.app_window_mode_split);
                break;
            default:
                mode.setText(R.string.app_window_mode_full);
                break;
        }
        loadApps();
        GridView grid = findViewById(R.id.app_window_grid);
        grid.setAdapter(new Adapter());
        grid.setOnItemClickListener((parent, view, position, id) -> open(apps.get(position)));
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            if (getPackageName().equals(ri.activityInfo.packageName)) {
                continue;
            }
            apps.add(new App(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name),
                    ri.loadLabel(pm), ri.loadIcon(pm)));
        }
        Collator order = Collator.getInstance();
        apps.sort((a, b) -> order.compare(a.label.toString(), b.label.toString()));
    }

    private void open(App app) {
        AppWindow.Mode mode = AppWindow.open(this, app.component);
        if (mode == AppWindow.Mode.FULL) {
            Notice.makeText(this, R.string.app_window_opened_full, android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return apps.size();
        }

        @Override
        public Object getItem(int position) {
            return apps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            LinearLayout cell = (LinearLayout) convert;
            if (cell == null) {
                cell = new LinearLayout(AppWindowActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(dp(8), dp(12), dp(8), dp(12));
                ImageView icon = new ImageView(AppWindowActivity.this);
                cell.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(64)));
                TextView name = new TextView(AppWindowActivity.this);
                name.setTextAppearance(R.style.TextAppearance_Cam_Caption);
                name.setTextColor(ContextCompat.getColor(AppWindowActivity.this, R.color.text_primary));
                name.setGravity(Gravity.CENTER);
                name.setMaxLines(2);
                name.setPadding(0, dp(6), 0, 0);
                cell.addView(name);
            }
            App app = apps.get(position);
            ((ImageView) cell.getChildAt(0)).setImageDrawable(app.icon);
            ((TextView) cell.getChildAt(1)).setText(app.label);
            cell.setContentDescription(app.label);
            return cell;
        }
    }
}
