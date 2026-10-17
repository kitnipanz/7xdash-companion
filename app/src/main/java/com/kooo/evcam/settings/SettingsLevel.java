package com.kooo.evcam.settings;

import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 设置分两档：「常用」只摆每天会动的那些，「全部」才摆细调、印章、探测页。
 *
 * <p>7xDash 的设置有一百多项，大半是装车时调一次就不再碰的（侧视的偏航俯仰、窗口宽高、按钮透明度……）。
 * 极氪 OS 7 的设置一页只放几张卡，这里照着收：默认「常用」，标题区的两段开关切到「全部」。
 * 没有删掉任何一项，只是藏起来。</p>
 *
 * <p>记的是<b>要藏的</b>那些，不是要留的：以后新加的设置默认看得见，不会因为忘了登记就找不到。
 * 开发者选项另有自己的解锁（{@link DeveloperMode}），不归这里管。</p>
 */
public final class SettingsLevel {

    /** 「常用」里藏起来的设置项和分组。 */
    public static final Set<String> ADVANCED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            // 录像
            "pref_profile_editor",
            "pref_photo_via_jpeg",
            "pref_watermark_brand",
            "pref_watermark_spec",
            // 存储
            "pref_relay_write",
            "pref_photo_limit",
            "pref_footage_lock_horn",
            "pref_footage_locked",
            // 超级后视镜：窗口和画面的细调
            "pref_rearview_fov",
            "pref_rearview_width",
            "pref_rearview_height",
            "pref_rearview_button_mode",
            // 转向侧视：位置、大小、视角的细调
            "pref_side_popup_hud_width",
            "pref_side_popup_hud_height",
            "pref_side_popup_hud_left",
            "pref_both_mirrors_hud",
            "pref_side_popup_straighten",
            "pref_side_popup_fov",
            "pref_side_popup_yaw",
            "pref_side_popup_pitch",
            "pref_side_popup_roll_left",
            "pref_side_popup_roll_right",
            "pref_side_popup_instant",
            "pref_side_popup_close_delay_ms",
            "pref_side_popup_size",
            "pref_side_popup_vertical",
            // 悬浮按钮的外观
            "pref_button_size",
            "pref_floating_alpha",
            "pref_floating_lock",
            "pref_floating_duration",
            "pref_button_text_size",
            // 调暗的细调
            "pref_dim_brightness",
            "pref_dim_warmth",
            "pref_dim_passthrough",
            "pref_dim_reset",
            // 界面
            "pref_recording_stats",
            "pref_reduce_motion",
            "pref_vehicle_status",
            "pref_fisheye_projection",
            "pref_fisheye_fov",
            // 系统：探测页和座舱窗口
            "pref_vehicle_info",
            "pref_screen_probe",
            "pref_controller",
            "pref_ble_signal",
            "pref_tap_to_speak",
            "cat_cabin_passenger",
            // 检查更新
            "pref_update_beta"
    )));

    private SettingsLevel() {
    }

    public static boolean isAdvanced(String key) {
        return key != null && ADVANCED.contains(key);
    }

    /**
     * 「常用」档：把登记过的藏起来，藏完变空的分组也藏起来。只会藏、不会亮 ——
     * 绑定时按别的条件藏起来的项（比如开发者选项没开）不能被这里翻出来。
     */
    public static void apply(PreferenceGroup group, boolean showAll) {
        if (group == null || showAll) {
            return;
        }
        hide(group);
    }

    /** 返回这一组里还有没有看得见的。 */
    private static boolean hide(PreferenceGroup group) {
        boolean anyVisible = false;
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference child = group.getPreference(i);
            if (isAdvanced(child.getKey())) {
                child.setVisible(false);
                continue;
            }
            if (child instanceof PreferenceGroup && !hide((PreferenceGroup) child)) {
                child.setVisible(false);
                continue;
            }
            anyVisible |= child.isVisible();
        }
        return anyVisible;
    }
}
