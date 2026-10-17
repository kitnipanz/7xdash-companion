package com.kooo.evcam.home;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.R;
import com.kooo.evcam.input.MegaphoneService;
import com.kooo.evcam.input.ShortcutAction;
import com.kooo.evcam.input.ShortcutPerformer;
import com.kooo.evcam.recording.RecordingCoordinator;

import java.util.ArrayList;
import java.util.List;

/**
 * 7xDash 的固定控制栏：极氪 OS 7 左边那一条，每一页都在，永远在同一个位置。
 *
 * <p>从上到下：主页、录像、调暗、后视、即时抓拍、喊话、均衡器、设置。除了主页和设置，都是开关 ——
 * 点了不换页，走的是快捷键那条路（{@link ShortcutPerformer}），开着的那一格点亮
 * （录像是录制红，其余是极氪橙；主页在主页上时点亮）。</p>
 *
 * <p>不用每个布局各放一份：{@link #install} 在每个界面第一次显示之前（onStart，窗口还没挂上）
 * 把整页包进一个横排、旁边插上这一条。那时还没有任何画布，预览的 TextureView 不会被拆一次。
 * 「侧边菜单位置」选了右边就插在右边。</p>
 */
public class SideRail extends LinearLayout {

    private static final long REFRESH_MS = 1_000L;
    private static final int TAG_WRAPPED = R.id.side_rail;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Runnable> refreshers = new ArrayList<>();
    private final AppConfig config;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    private interface State {
        boolean get();
    }

    public SideRail(Context context) {
        super(context);
        config = new AppConfig(context);
        setId(R.id.side_rail);
        setOrientation(VERTICAL);
        int pad = dp(16);
        setPadding(pad, pad, 0, pad);
        build();
    }

    /** 每个界面开始显示时（onStart）包一次；包过的不再包。 */
    public static void install(Application app) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityStarted(@NonNull Activity activity) {
                wrap(activity);
            }

            @Override
            public void onActivityCreated(@NonNull Activity a, @Nullable Bundle b) {
            }

            @Override
            public void onActivityResumed(@NonNull Activity a) {
            }

            @Override
            public void onActivityPaused(@NonNull Activity a) {
            }

            @Override
            public void onActivityStopped(@NonNull Activity a) {
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) {
            }

            @Override
            public void onActivityDestroyed(@NonNull Activity a) {
            }
        });
    }

    private static void wrap(Activity activity) {
        if (!activity.getPackageName().equals(activity.getApplicationContext().getPackageName())
                || !activity.getClass().getName().startsWith("com.kooo.evcam.")) {
            return;
        }
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() != 1 || content.getTag(TAG_WRAPPED) != null) {
            return;
        }
        content.setTag(TAG_WRAPPED, Boolean.TRUE);
        View page = content.getChildAt(0);
        content.removeView(page);
        // FrameLayout，不用横排 LinearLayout：主界面最外层是 DrawerLayout，它只接受确切的尺寸，
        // 横排带权重会先拿「最多这么宽」量一遍，DrawerLayout 当场抛异常。这里页面铺满、留出控制栏那一条的边距
        android.widget.FrameLayout row = new android.widget.FrameLayout(activity);
        row.setBackgroundColor(ContextCompat.getColor(activity, R.color.bg));
        SideRail rail = new SideRail(activity);
        int railWidth = rail.dp(112 + 16);
        boolean right = new AppConfig(activity).isMenuOnRight();
        if (right) {
            rail.setPadding(0, rail.dp(16), rail.dp(16), rail.dp(16));
        }
        android.widget.FrameLayout.LayoutParams pageLp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        if (right) {
            pageLp.rightMargin = railWidth;
        } else {
            pageLp.leftMargin = railWidth;
        }
        android.widget.FrameLayout.LayoutParams railLp = new android.widget.FrameLayout.LayoutParams(
                railWidth, ViewGroup.LayoutParams.MATCH_PARENT,
                right ? android.view.Gravity.END : android.view.Gravity.START);
        row.addView(page, pageLp);
        row.addView(rail, railLp);
        content.addView(row, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    private void build() {
        add(R.drawable.ic_home, R.string.nav_home, this::goHome, this::onHome, false);
        add(R.drawable.ic_record, R.string.home_rail_record,
                () -> perform(ShortcutAction.RECORD), this::recording, true);
        add(R.drawable.ic_dim, R.string.home_rail_dim,
                () -> perform(ShortcutAction.DIM), config::isDimOverlayEnabled, false);
        add(R.drawable.ic_rearview, R.string.home_rail_rear,
                () -> perform(ShortcutAction.MIRROR), config::isRearViewEnabled, false);
        add(R.drawable.ic_flash, R.string.home_rail_capture,
                () -> perform(ShortcutAction.INSTANT_CAPTURE), null, false);
        add(R.drawable.ic_megaphone, R.string.home_rail_megaphone,
                () -> perform(ShortcutAction.TOGGLE_SPEAK), MegaphoneService::isOpen, false);
        // 均衡器：点了进均衡器那一页；均衡器开着时这一格点亮
        add(R.drawable.ic_eq, R.string.home_rail_eq,
                () -> open(MainActivity.PAGE_EQ),
                () -> new com.kooo.evcam.audio.CabinEqStore(getContext()).enabled(), false);
        // AirPlay：点了进投屏那一页；接收开着时这一格点亮
        add(R.drawable.ic_airplay, R.string.airplay_title,
                () -> open(MainActivity.PAGE_AIRPLAY),
                com.kooo.evcam.airplay.AirPlayService::isRunning, false);
        add(R.drawable.ic_settings, R.string.home_rail_settings,
                () -> open(MainActivity.PAGE_SETTINGS_PREFIX + "screen_recording"), null, false);
    }

    private void add(int icon, int label, Runnable onTap, @Nullable State on, boolean recordingColor) {
        View cell = LayoutInflater.from(getContext()).inflate(R.layout.item_home_rail, this, false);
        ImageView image = cell.findViewById(R.id.rail_icon);
        TextView text = cell.findViewById(R.id.rail_label);
        image.setImageResource(icon);
        text.setText(label);
        cell.setContentDescription(getContext().getString(label));
        cell.setOnClickListener(v -> {
            onTap.run();
            handler.postDelayed(this::refresh, 300);
        });
        addView(cell);
        if (on == null) {
            return;
        }
        refreshers.add(() -> {
            boolean active = on.get();
            if (recordingColor) {
                cell.setBackgroundResource(active ? R.drawable.bg_home_rail_recording : R.drawable.bg_home_rail);
            } else {
                cell.setActivated(active);
            }
            int color = ContextCompat.getColor(getContext(), active ? R.color.on_energy : R.color.text_primary);
            image.setImageTintList(ColorStateList.valueOf(color));
            text.setTextColor(color);
        });
    }

    private void refresh() {
        for (Runnable refresher : refreshers) {
            refresher.run();
        }
    }

    private boolean onHome() {
        return getContext() instanceof MainActivity && ((MainActivity) getContext()).isHomeShown();
    }

    private boolean recording() {
        return RecordingCoordinator.get(getContext()).isRecordingOrResuming();
    }

    private void perform(ShortcutAction action) {
        ShortcutPerformer.perform(getContext(), action.key);
    }

    private void goHome() {
        open(MainActivity.PAGE_HOME);
    }

    /** 在主界面上直接换页；在别的界面上回到主界面那一页（singleTask，上面的界面一并收掉）。 */
    private void open(String page) {
        Context context = getContext();
        if (context instanceof MainActivity) {
            ((MainActivity) context).openPage(page);
            return;
        }
        Intent intent = new Intent(context, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_PAGE, page)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(intent);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
