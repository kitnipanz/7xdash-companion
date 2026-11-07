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
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;

import java.util.Collections;
import java.util.List;

/**
 * The list of automations and the master switch. A row reads as a sentence: when, then what. Tap
 * one to change it ({@link AutomationEditActivity}). With none yet, the list offers examples.
 */
public class AutomationActivity extends AppCompatActivity {

    /** The list goes faint while the master switch is off, so nobody expects those to run. */
    private static final float PAUSED_ALPHA = 0.5f;

    private AppConfig config;
    private SwitchMaterial toggle;
    private TextView state;
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
        findViewById(R.id.automation_add).setOnClickListener(v -> open(new AutomationRule(), true));
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
        if (rules.isEmpty()) {
            TextView heading = text(this, getString(R.string.automation_examples), false);
            heading.setPadding(dp(8), dp(4), 0, dp(8));
            list.addView(heading);
            for (AutomationRule example : AutomationExamples.all()) {
                list.addView(exampleRow(example));
            }
            return;
        }
        for (AutomationRule rule : rules) {
            list.addView(ruleRow(rule));
        }
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
        AutomationTrigger trigger = rule.trigger();
        return trigger.measure == null ? context.getString(trigger.labelRes)
                : context.getString(trigger.labelRes, rule.value);
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
