package com.kooo.evcam.automation;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.announce.NoticeVoiceDialog;
import com.kooo.evcam.nav.SavedPlaces;
import com.kooo.evcam.ui.CamDialogs;

import java.text.DateFormatSymbols;
import java.util.Collections;
import java.util.List;

/**
 * The list of automations and the master switch. A row reads as a sentence: when, then what. Tap
 * one to change it ({@link AutomationEditActivity}). Examples to start from sit under them.
 */
public class AutomationActivity extends AppCompatActivity {

    /** The list goes faint while the master switch is off, so nobody expects those to run. */
    private static final float PAUSED_ALPHA = 0.5f;

    private AppConfig config;
    private SwitchMaterial toggle;
    private TextView state;
    private TextView voiceState;
    private LinearLayout list;
    private List<AutomationRule> rules;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_automation);
        config = new AppConfig(this);
        toggle = findViewById(R.id.automation_switch);
        state = findViewById(R.id.automation_state);
        list = findViewById(R.id.automation_list);
        findViewById(R.id.automation_close).setOnClickListener(v -> finish());
        voiceState = findViewById(R.id.automation_voice_state);
        findViewById(R.id.automation_add).setOnClickListener(v -> open(new AutomationRule(), true));
        findViewById(R.id.automation_history).setOnClickListener(v -> showHistory());
        findViewById(R.id.automation_voice).setOnClickListener(v ->
                NoticeVoiceDialog.show(this, this::paintVoice));
        toggle.setOnCheckedChangeListener((b, on) -> {
            config.setAutomationOn(on);
            Automation.reload();
            paintState();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        rules = AutomationRule.parseAll(config.getAutomationRules());
        toggle.setChecked(config.isAutomationOn());
        paintVoice();
        build();
        paintState();
    }

    private void paintState() {
        boolean on = config.isAutomationOn();
        state.setText(on ? R.string.automation_hint : R.string.automation_paused);
        state.setTextColor(ContextCompat.getColor(this, on ? R.color.text_secondary : R.color.energy));
        list.setAlpha(on || rules.isEmpty() ? 1f : PAUSED_ALPHA);
    }

    private void build() {
        list.removeAllViews();
        for (AutomationRule rule : rules) {
            list.addView(ruleRow(rule));
        }
        TextView heading = text(this, getString(R.string.automation_examples), false);
        heading.setPadding(dp(8), dp(rules.isEmpty() ? 4 : 14), 0, dp(8));
        list.addView(heading);
        for (AutomationRule example : AutomationExamples.all(SavedPlaces.load(this))) {
            list.addView(exampleRow(example));
        }
    }

    private void paintVoice() {
        voiceState.setText(config.isNoticeSpeakOn() && !config.getNoticeSpeaker().isEmpty()
                ? getString(R.string.notice_speak_on, config.getNoticeSpeaker())
                : getString(R.string.notice_speak_off));
    }

    /** What ran, newest first. */
    private void showHistory() {
        List<AutomationHistory.Entry> entries = AutomationHistory.parse(config.getAutomationHistory());
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        if (entries.isEmpty()) {
            box.addView(text(this, getString(R.string.automation_history_empty), false));
        }
        for (AutomationHistory.Entry entry : entries) {
            box.addView(historyRow(entry));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.automation_history)
                .setView(scroll)
                .setPositiveButton(R.string.nav_hud_adjust_done, null);
        if (!entries.isEmpty()) {
            builder.setNeutralButton(R.string.automation_history_clear,
                    (dialog, which) -> config.setAutomationHistory(null));
        }
        AlertDialog dialog = CamDialogs.show(builder);
        if (dialog != null && dialog.getWindow() != null) {
            dialog.getWindow().setLayout(Math.round(getResources().getDisplayMetrics().widthPixels * 0.7f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private View historyRow(AutomationHistory.Entry entry) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(10));
        String moment = DateFormat.getMediumDateFormat(this).format(entry.at) + "  "
                + DateFormat.getTimeFormat(this).format(entry.at);
        row.addView(text(this, moment, false));
        row.addView(text(this, entry.tried ? getString(R.string.automation_try) : entry.when, true));
        row.addView(text(this, entry.what, false));
        boolean failed = entry.failed != null && !entry.failed.isEmpty();
        TextView outcome = text(this, failed ? getString(R.string.automation_history_failed, entry.failed)
                : getString(R.string.automation_history_sent), false);
        outcome.setTextColor(ContextCompat.getColor(this, failed ? R.color.energy : R.color.text_secondary));
        row.addView(outcome);
        return row;
    }

    private View ruleRow(AutomationRule rule) {
        LinearLayout row = row(rule, doText(rule));
        SwitchMaterial on = new SwitchMaterial(this);
        on.setChecked(rule.enabled);
        on.setContentDescription(whenText(this, rule));
        on.setOnCheckedChangeListener((b, checked) -> {
            rule.enabled = checked;
            config.setAutomationRules(AutomationRule.toJson(rules));
            Automation.reload();
        });
        row.addView(on);
        row.addView(chevron(R.drawable.ic_chevron_right));
        row.setOnClickListener(v -> open(rule, false));
        return row;
    }

    private View exampleRow(AutomationRule example) {
        LinearLayout row = row(example, AutomationAction.describeAll(this, example.steps));
        row.addView(chevron(R.drawable.ic_add));
        row.setOnClickListener(v -> open(example, true));
        return row;
    }

    /** A card: the trigger's icon, the when in bold, the what under it. */
    private LinearLayout row(AutomationRule rule, String what) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_surface_card);
        row.setPadding(dp(20), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(10);
        row.setLayoutParams(params);

        ImageView icon = new ImageView(this);
        icon.setImageResource(rule.trigger().group.iconRes);
        icon.setBackgroundResource(R.drawable.bg_home_icon);
        icon.setPadding(dp(10), dp(10), dp(10), dp(10));
        row.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text(this, whenText(this, rule), true));
        words.addView(text(this, what, false));
        LinearLayout.LayoutParams wordParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wordParams.setMarginStart(dp(16));
        row.addView(words, wordParams);
        return row;
    }

    private ImageView chevron(int icon) {
        ImageView view = new ImageView(this);
        view.setImageResource(icon);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(24), dp(24));
        params.setMarginStart(dp(12));
        view.setLayoutParams(params);
        return view;
    }

    private void open(AutomationRule rule, boolean isNew) {
        startActivity(new Intent(this, AutomationEditActivity.class)
                .putExtra(AutomationEditActivity.EXTRA_RULE,
                        AutomationRule.toJson(Collections.singletonList(rule)))
                .putExtra(AutomationEditActivity.EXTRA_NEW, isNew));
    }

    private String doText(AutomationRule rule) {
        String out = AutomationAction.describeAll(this, rule.steps);
        Long ran = Automation.lastRan(rule.id);
        return ran == null ? out : out + "\n" + getString(R.string.automation_last_ran,
                DateFormat.getTimeFormat(this).format(ran));
    }

    static String whenText(Context context, AutomationRule rule) {
        StringBuilder out = new StringBuilder();
        for (AutomationRule.Condition condition : rule.conditions()) {
            out.append(out.length() == 0 ? "" : context.getString(R.string.automation_and))
                    .append(conditionText(context, condition));
        }
        return out.toString();
    }

    private static String conditionText(Context context, AutomationRule.Condition condition) {
        AutomationTrigger trigger = condition.trigger();
        switch (trigger) {
            case TIME_AT:
                return context.getString(trigger.labelRes, AutomationClock.time(condition.value));
            case TIME_BETWEEN:
                return context.getString(trigger.labelRes, AutomationClock.time(AutomationClock.from(condition.value)),
                        AutomationClock.time(AutomationClock.to(condition.value)));
            case DAYS:
                return condition.value == AutomationClock.EVERY_DAY ? context.getString(R.string.automation_days_every)
                        : context.getString(trigger.labelRes, daysText(context, condition.value));
            case PLACE_ARRIVE:
            case PLACE_LEAVE:
                SavedPlaces.Place place = SavedPlaces.find(SavedPlaces.load(context), condition.value);
                return context.getString(trigger.labelRes,
                        place == null ? context.getString(R.string.place_deleted) : place.name);
            default:
                return trigger.measure == null ? context.getString(trigger.labelRes)
                        : context.getString(trigger.labelRes, condition.value);
        }
    }

    private static String daysText(Context context, int days) {
        if (days == AutomationClock.WEEKDAYS) {
            return context.getString(R.string.automation_days_weekdays);
        }
        if (days == AutomationClock.WEEKEND) {
            return context.getString(R.string.automation_days_weekend);
        }
        String[] names = DateFormatSymbols.getInstance(context.getResources().getConfiguration().getLocales().get(0))
                .getShortWeekdays();
        StringBuilder out = new StringBuilder();
        for (int day : AutomationClock.WEEK) {
            if ((days & AutomationClock.dayBit(day)) != 0) {
                out.append(out.length() == 0 ? "" : ", ").append(names[day]);
            }
        }
        return out.toString();
    }

    static TextView text(Context context, String value, boolean main) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextAppearance(main ? R.style.TextAppearance_Cam_Row : R.style.TextAppearance_Cam_Body);
        view.setTextColor(ContextCompat.getColor(context, main ? R.color.text_primary : R.color.text_secondary));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
