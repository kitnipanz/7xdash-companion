package com.kooo.evcam.home;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.R;
import com.kooo.evcam.input.MegaphoneService;
import com.kooo.evcam.input.ShortcutAction;
import com.kooo.evcam.input.ShortcutPerformer;
import com.kooo.evcam.overlay.OverlayCoordinator;
import com.kooo.evcam.recording.RecordingCoordinator;
import com.kooo.evcam.storage.AutoLock;
import com.kooo.evcam.zeekr.HudDisplay;
import com.kooo.evcam.zeekr.SideViewPopupService;

import java.util.ArrayList;
import java.util.List;

/**
 * 7xDash 主页，照极氪 OS 7 的沉浸壁纸桌面排：左边一列小组件（时钟、行车记录），右边壁纸上 4×3 功能卡片，
 * 每个功能一张。控制栏是每一页都有的那条（{@link SideRail}），不在这里。
 *
 * <p>功能本身一行没重写：开关走 {@link ShortcutPerformer}（和贴纸、实体键同一条路），
 * 进页面走 {@link MainActivity#openPage}，设置走各自的分区。这里只排版和显示开 / 关。</p>
 *
 * <p>开关的状态散在各处（设置、服务的静态量），没有一个统一的通知；主页开着时每秒对一次，便宜。</p>
 */
public class HomeFragment extends Fragment {

    public static final String TAG = "home";
    private static final long REFRESH_MS = 1_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Runnable> refreshers = new ArrayList<>();
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    /** 小画面多久换一帧：两帧一秒够看出车在动，又不和录像抢太多。 */
    private static final long PREVIEW_MS = 500L;

    private AppConfig config;
    private TextView recState;
    private ImageView preview;
    private View previewWait;
    private Bitmap shownFrame;
    private final Runnable previewTick = new Runnable() {
        @Override
        public void run() {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).grabHomePreview(HomeFragment.this::showFrame);
            }
            handler.postDelayed(this, PREVIEW_MS);
        }
    };
    private com.google.android.material.button.MaterialButton recToggle;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        config = new AppConfig(requireContext());
        recState = view.findViewById(R.id.home_rec_state);
        recToggle = view.findViewById(R.id.home_rec_toggle);
        recToggle.setOnClickListener(v -> {
            perform(ShortcutAction.RECORD);
            handler.postDelayed(this::refresh, 300);
        });
        view.findViewById(R.id.home_rec_open).setOnClickListener(v -> page(MainActivity.PAGE_CAMERA));
        preview = view.findViewById(R.id.home_rec_preview);
        previewWait = view.findViewById(R.id.home_rec_preview_wait);
        View previewFrame = view.findViewById(R.id.home_rec_preview_frame);
        previewFrame.setClipToOutline(true);
        previewFrame.setOnClickListener(v -> page(MainActivity.PAGE_CAMERA));
        View recCard = view.findViewById(R.id.home_rec_card);
        recCard.setOnClickListener(v -> page(MainActivity.PAGE_CAMERA));
        recCard.setOnLongClickListener(v -> {
            page(MainActivity.PAGE_SETTINGS_PREFIX + "screen_recording");
            return true;
        });
        refreshers.clear();
        if (config.isMenuOnRight()) {
            mirror((LinearLayout) view);
        }
        buildGrid(view.findViewById(R.id.home_grid));
    }

    @Override
    public void onResume() {
        super.onResume();
        handler.post(tick);
        handler.post(previewTick);
    }

    @Override
    public void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        handler.removeCallbacks(previewTick);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (preview != null) {
            preview.setImageDrawable(null);
        }
        if (shownFrame != null) {
            shownFrame.recycle();
            shownFrame = null;
        }
    }

    /** 新的一帧换上去，换下来的那张不再画了，收掉。 */
    private void showFrame(Bitmap frame) {
        if (preview == null || !isAdded()) {
            frame.recycle();
            return;
        }
        preview.setImageBitmap(frame);
        previewWait.setVisibility(View.GONE);
        if (shownFrame != null) {
            shownFrame.recycle();
        }
        shownFrame = frame;
    }

    /**
     * 「侧边菜单位置」选了右边：控制栏、小组件列、卡片区三列倒过来排，控制栏贴右边。
     * 列与列之间的 16dp 缝跟着挪到新的左手边。
     */
    private void mirror(LinearLayout row) {
        List<View> columns = new ArrayList<>();
        for (int i = row.getChildCount() - 1; i >= 0; i--) {
            columns.add(row.getChildAt(i));
        }
        row.removeAllViews();
        int gap = Math.round(16 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < columns.size(); i++) {
            View column = columns.get(i);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) column.getLayoutParams();
            lp.setMarginStart(i == 0 ? 0 : gap);
            lp.setMarginEnd(0);
            row.addView(column, lp);
        }
    }

    // ---------------- 功能卡片 ----------------

    private void buildGrid(LinearLayout grid) {
        // 第一行：拍照、回看、后视镜头、盲区
        addCard(grid, 0, 0, R.drawable.ic_photo, R.string.home_card_photo,
                R.string.home_card_photo_sub, this::takePhotoHere,
                () -> startActivity(new Intent(requireContext(),
                        com.kooo.evcam.playback.PhotoPlaybackActivity.class)));
        addCard(grid, 0, 1, R.drawable.ic_video, R.string.home_card_videos,
                R.string.home_card_videos_sub, () -> startActivity(new Intent(requireContext(),
                        com.kooo.evcam.zeekr.TimelinePlayerActivity.class)), null);
        addCard(grid, 0, 2, R.drawable.ic_rearview, R.string.home_card_rear,
                R.string.home_card_rear_sub, () -> perform(ShortcutAction.MIRROR),
                () -> page(MainActivity.PAGE_SETTINGS_PREFIX + "screen_rearview"))
                .onOff(() -> config.isRearViewEnabled());
        addCard(grid, 0, 3, R.drawable.ic_blindspot, R.string.home_card_blind,
                R.string.home_card_blind_sub, this::cycleBlindSpot,
                () -> page(MainActivity.PAGE_SETTINGS_PREFIX + "screen_side_mirror"))
                .state(this::blindSpotText, () -> config.isSidePopupEnabled());

        // 第二行：即时抓拍、抓拍列表、远程观看、喊话。
        // 即时抓拍和闪远光是同一件事、两种触发：点卡片当场抓；卡片右上角那颗「闪远光」小开关
        // 决定录像时闪一下远光算不算也抓一次。长按卡片看抓拍列表
        addCard(grid, 1, 0, R.drawable.ic_flash, R.string.home_card_capture,
                R.string.home_card_capture_sub, () -> perform(ShortcutAction.INSTANT_CAPTURE),
                this::openCaptures)
                .pill(() -> getString(R.string.home_capture_flash, getString(
                                AutoLock.flashEnabled(requireContext()) ? R.string.home_state_on : R.string.home_state_off)),
                        () -> AutoLock.flashEnabled(requireContext()), this::toggleFlash);
        addCard(grid, 1, 1, R.drawable.ic_play, R.string.rail_captures_list,
                R.string.home_card_saved_sub, this::openCaptures, null);
        addCard(grid, 1, 2, R.drawable.ic_remote, R.string.home_card_remote,
                R.string.home_card_remote_sub, () -> page(MainActivity.PAGE_REMOTE), null);
        addCard(grid, 1, 3, R.drawable.ic_megaphone, R.string.home_rail_megaphone,
                R.string.home_card_megaphone_sub, () -> perform(ShortcutAction.TOGGLE_SPEAK),
                () -> page(MainActivity.PAGE_SETTINGS_PREFIX + "screen_megaphone"))
                .onOff(MegaphoneService::isOpen);

        // 第三行：Google 云端硬盘、智能贴、快捷键、抬头显示通知
        addCard(grid, 2, 0, R.drawable.ic_drive, R.string.home_card_drive,
                R.string.home_card_drive_sub, () -> page(MainActivity.PAGE_SETTINGS_PREFIX + "screen_drive"), null);
        addCard(grid, 2, 1, R.drawable.ic_sticker, R.string.home_card_sticker,
                R.string.home_card_sticker_sub, () -> startActivity(new Intent(requireContext(),
                        com.kooo.evcam.input.StickerActivity.class)), null);
        addCard(grid, 2, 2, R.drawable.ic_shortcut, R.string.home_card_shortcuts,
                R.string.home_card_shortcuts_sub, () -> startActivity(new Intent(requireContext(),
                        com.kooo.evcam.input.ShortcutListActivity.class)), null);
        addCard(grid, 2, 3, R.drawable.ic_hud, R.string.home_card_hud_notice,
                R.string.home_card_hud_notice_sub, this::toggleHudNotices,
                () -> page(MainActivity.PAGE_SETTINGS_PREFIX + "screen_rearview"))
                .onOff(() -> config.isHudNotificationsEnabled());

        // 第四行：停在哪。点卡片当场读一次；胶囊开关决定挂 P 时自动读
        addCard(grid, 3, 0, R.drawable.ic_parking, R.string.home_card_parking,
                R.string.home_card_parking_sub, () -> perform(ShortcutAction.WHERE_PARKED), null)
                .pill(() -> getString(R.string.home_parking_auto, getString(
                                config.isParkingAutoEnabled() ? R.string.home_state_on : R.string.home_state_off)),
                        () -> config.isParkingAutoEnabled(), this::toggleParkingAuto);
        padRow(grid, 3, 4);
    }

    /** 一行没排满时补空位，卡片宽度和上面几行对齐。 */
    private void padRow(LinearLayout grid, int row, int columns) {
        LinearLayout line = rowOf(grid, row);
        int gap = getResources().getDimensionPixelSize(R.dimen.gutter) / 2;
        while (line.getChildCount() < columns) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            lp.setMargins(gap, gap, gap, gap);
            line.addView(new View(grid.getContext()), lp);
        }
    }

    private Card addCard(LinearLayout grid, int row, int column, int icon, int title, int sub,
                         Runnable onTap, @Nullable Runnable onHold) {
        View view = LayoutInflater.from(grid.getContext()).inflate(R.layout.item_home_card, grid, false);
        ((ImageView) view.findViewById(R.id.card_icon)).setImageResource(icon);
        ((TextView) view.findViewById(R.id.card_title)).setText(title);
        ((TextView) view.findViewById(R.id.card_sub)).setText(sub);
        view.setOnClickListener(v -> {
            onTap.run();
            handler.postDelayed(this::refresh, 300);
        });
        if (onHold != null) {
            view.setOnLongClickListener(v -> {
                onHold.run();
                return true;
            });
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        int gap = getResources().getDimensionPixelSize(R.dimen.gutter) / 2;
        lp.setMargins(gap, gap, gap, gap);
        rowOf(grid, row).addView(view, column, lp);
        return new Card(view);
    }

    /** 第 row 行的横排，没有就建（行按顺序建，一行权重 1）。 */
    private LinearLayout rowOf(LinearLayout grid, int row) {
        while (grid.getChildCount() <= row) {
            LinearLayout line = new LinearLayout(grid.getContext());
            line.setOrientation(LinearLayout.HORIZONTAL);
            grid.addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        return (LinearLayout) grid.getChildAt(row);
    }

    private final class Card {
        private final View view;
        private final TextView state;

        Card(View view) {
            this.view = view;
            this.state = view.findViewById(R.id.card_state);
        }

        Card onOff(State on) {
            return state(() -> getString(on.get() ? R.string.home_state_on : R.string.home_state_off), on);
        }

        /**
         * 右上角换成一颗能点的小开关（胶囊）：点它只拨开关，不触发整张卡片。
         * 开着时胶囊是实心橙，关着是灰；卡片本身不跟着点亮（卡片是「动作」，胶囊才是「开关」）。
         */
        Card pill(Label label, State on, Runnable toggle) {
            state.setClickable(true);
            state.setFocusable(true);
            state.setBackgroundResource(R.drawable.bg_segment);
            int padH = Math.round(14 * getResources().getDisplayMetrics().density);
            int padV = Math.round(6 * getResources().getDisplayMetrics().density);
            state.setPadding(padH, padV, padH, padV);
            state.setOnClickListener(v -> {
                toggle.run();
                handler.postDelayed(HomeFragment.this::refresh, 300);
            });
            refreshers.add(() -> {
                boolean active = on.get();
                state.setText(label.get());
                state.setActivated(active);
                state.setTextColor(ContextCompat.getColor(requireContext(),
                        active ? R.color.on_energy : R.color.text_secondary));
            });
            return this;
        }

        Card state(Label label, State on) {
            refreshers.add(() -> {
                state.setText(label.get());
                view.setActivated(on.get());
            });
            return this;
        }
    }

    private interface State {
        boolean get();
    }

    private interface Label {
        String get();
    }

    // ---------------- 动作 ----------------

    private void refresh() {
        if (!isAdded()) {
            return;
        }
        for (Runnable refresher : refreshers) {
            refresher.run();
        }
        boolean rec = recording();
        Context context = requireContext();
        recState.setText(rec ? R.string.home_rec_on : R.string.home_rec_off);
        recState.setTextColor(ContextCompat.getColor(context, rec ? R.color.recording_text : R.color.text_primary));
        // 没在录：极氪橙「开始录像」；在录：录制红「停止录像」
        recToggle.setText(rec ? R.string.home_action_stop : R.string.home_action_start);
        recToggle.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(context, rec ? R.color.recording : R.color.energy)));
        recToggle.setTextColor(ContextCompat.getColor(context, R.color.on_energy));
    }

    private boolean recording() {
        Context context = getContext();
        return context != null && RecordingCoordinator.get(context).isRecordingOrResuming();
    }

    private void perform(ShortcutAction action) {
        ShortcutPerformer.perform(requireContext(), action.key);
    }

    /** 拍照卡片：就地拍，留在主页（结果照常弹提示，开着抬头显示通知时也上抬头显示）。 */
    private void takePhotoHere() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).takePhotoHere();
        }
    }

    private void page(String page) {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).openPage(page);
        }
    }

    /** 盲区：关 → 中控 → 抬头显示 → 关。没有抬头显示时跳过那一档。 */
    private void cycleBlindSpot() {
        Context context = requireContext();
        if (!config.isSidePopupEnabled()) {
            config.setSidePopupOnHud(false);
            if (!OverlayCoordinator.setSidePopupEnabled(context, true)) {
                com.kooo.evcam.ui.Notice.makeText(context, R.string.msg_need_overlay, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (!config.isSidePopupOnHud() && HudDisplay.find(context) != null) {
            config.setSidePopupOnHud(true);
            SideViewPopupService.applyConfig();
            SideViewPopupService.start(context);
            return;
        }
        config.setSidePopupOnHud(false);
        OverlayCoordinator.setSidePopupEnabled(context, false);
    }

    private String blindSpotText() {
        if (!config.isSidePopupEnabled()) {
            return getString(R.string.home_state_off);
        }
        return getString(config.isSidePopupOnHud() ? R.string.home_state_hud : R.string.home_state_screen);
    }

    /** 闪远光抓拍：打开时把它依赖的「锁定影像」一起打开，不然开关拨了不起作用。 */
    private void openCaptures() {
        startActivity(new Intent(requireContext(), com.kooo.evcam.recording.InstantCapturesActivity.class));
    }

    private void toggleParkingAuto() {
        Context context = requireContext();
        config.setParkingAutoEnabled(!config.isParkingAutoEnabled());
        com.kooo.evcam.parking.WhereParked.get().settingsChanged(context);
    }

    private void toggleFlash() {
        Context context = requireContext();
        boolean on = !AutoLock.flashEnabled(context);
        if (on) {
            config.setFootageLockEnabled(true);
        }
        config.setFlashLockEnabled(on);
        AutoLock.get().settingsChanged(context);
    }

    /** 抬头显示通知：7xDash 自己的提示也写到抬头显示上（{@link com.kooo.evcam.hud.HudNotice}）。不要任何权限。 */
    private void toggleHudNotices() {
        Context context = requireContext();
        if (config.isHudNotificationsEnabled()) {
            config.setHudNotificationsEnabled(false);
            return;
        }
        if (HudDisplay.find(context) == null) {
            com.kooo.evcam.ui.Notice.makeText(context, R.string.msg_no_hud, Toast.LENGTH_SHORT).show();
            return;
        }
        config.setHudNotificationsEnabled(true);
        com.kooo.evcam.hud.HudNotice.post(context, getString(R.string.home_card_hud_notice),
                getString(R.string.home_state_on));
    }
}
