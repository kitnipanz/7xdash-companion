package com.kooo.evcam.automation;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.announce.AnnouncePick;
import com.kooo.evcam.input.ActionTabs;
import com.kooo.evcam.input.ShortcutAction;
import com.kooo.evcam.nav.Navigator;
import com.kooo.evcam.nav.SavePlaceDialog;
import com.kooo.evcam.nav.SavedPlaces;
import com.kooo.evcam.telemetry.Telemetry;
import com.kooo.evcam.telemetry.VehicleState;
import com.kooo.evcam.ui.CamDialogs;
import com.kooo.evcam.ui.Notice;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * One automation on one page. Everything to choose from is in view: When on the left, Do on the
 * right, a sentence at the bottom beside Save that reads back what the choices add up to. A tap picks, a second tap
 * on the same choice takes it back. An action holds one choice; shortcuts can be several.
 */
public class AutomationEditActivity extends AppCompatActivity {

    /** The rule as {@link AutomationRule#toJson}, a list of one. */
    static final String EXTRA_RULE = "rule";
    /** Not in the list yet: no Delete, and Save adds it. */
    static final String EXTRA_NEW = "new";

    private static final String KEPT_DRAFT = "draft";
    private static final String KEPT_START = "started_as";
    /** Registered while this page shows, so "Save this spot" has a position. */
    private static final String TELEMETRY_USER = "automation_edit";

    private AutomationRule draft;
    private String startedAs;
    private boolean isNew;
    private LinearLayout whenPane;
    private LinearLayout doPane;
    private TextView sentence;
    private View tryButton;
    private View saveButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_automation_edit);
        isNew = getIntent().getBooleanExtra(EXTRA_NEW, true);
        String json = getIntent().getStringExtra(EXTRA_RULE);
        if (savedInstanceState != null) {
            draft = AutomationRule.fromDraft(savedInstanceState.getString(KEPT_DRAFT));
            startedAs = savedInstanceState.getString(KEPT_START);
        } else {
            // A blank new rule has no trigger yet, so parseAll drops it and this starts a fresh one
            List<AutomationRule> read = AutomationRule.parseAll(json);
            draft = read.isEmpty() ? new AutomationRule() : read.get(0);
            startedAs = isNew ? null : state();
        }
        whenPane = findViewById(R.id.automation_edit_when);
        doPane = findViewById(R.id.automation_edit_do);
        sentence = findViewById(R.id.automation_edit_sentence);
        tryButton = findViewById(R.id.automation_edit_try);
        saveButton = findViewById(R.id.automation_edit_save);
        ((TextView) findViewById(R.id.automation_edit_title))
                .setText(isNew ? R.string.automation_new_title : R.string.automation_edit_title);
        View delete = findViewById(R.id.automation_edit_delete);
        delete.setVisibility(isNew ? View.GONE : View.VISIBLE);
        delete.setOnClickListener(v -> askDelete());
        findViewById(R.id.automation_edit_close).setOnClickListener(v -> leave());
        tryButton.setOnClickListener(v -> Automation.tryNow(draft.steps));
        saveButton.setOnClickListener(v -> save());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                leave();
            }
        });
        draw();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Telemetry.get().acquire(this, TELEMETRY_USER);
    }

    @Override
    protected void onStop() {
        Telemetry.get().release(TELEMETRY_USER);
        super.onStop();
    }

    /** The screen is rebuilt when the car's day or night theme changes: keep what was picked. */
    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(KEPT_DRAFT, draft.toDraft());
        out.putString(KEPT_START, startedAs);
    }

    private String state() {
        return AutomationRule.toJson(Collections.singletonList(draft));
    }

    private void draw() {
        drawWhen();
        drawDo();
        paintSentence();
    }

    // ---- When

    private void drawWhen() {
        whenPane.removeAllViews();
        whenPane.addView(heading(R.string.automation_when_heading));
        AutomationTrigger picked = draft.trigger();
        for (AutomationTrigger.Group group : AutomationTrigger.Group.values()) {
            ChipGroup chips = addSection(whenPane, group.iconRes, getString(group.labelRes));
            for (AutomationTrigger trigger : AutomationTrigger.values()) {
                if (trigger.group == group) {
                    chips.addView(chip(getString(trigger.chipRes), trigger == picked, v -> pickTrigger(trigger)));
                }
            }
            if (picked != null && picked.group == group && picked.measure != null) {
                whenPane.addView(number(picked));
            }
            if (group == AutomationTrigger.Group.PLACE) {
                drawPlaces(picked != null && picked.isPlace() ? picked : null);
            }
        }
        drawConditions();
    }

    // ---- And only if

    /**
     * States that must be true as well when the trigger happens, one per group: the days, dark or
     * light, rain or dry, at or away from a saved place. Nothing picked means any.
     */
    private void drawConditions() {
        TextView heading = heading(R.string.automation_and_heading);
        heading.setPadding(0, dp(28), 0, dp(4));
        whenPane.addView(heading);
        whenPane.addView(AutomationActivity.text(this, getString(R.string.automation_and_hint), false));
        drawDays();
        drawTime();
        drawStates(AutomationCondition.Group.DAYLIGHT);
        drawStates(AutomationCondition.Group.RAIN);
        drawPlaceCondition();
    }

    private void drawDays() {
        AutomationCondition.Group group = AutomationCondition.Group.DAYS;
        ChipGroup chips = addSection(whenPane, group.iconRes, getString(group.labelRes));
        AutomationRule.Condition current = draft.condition(group);
        int mask = current == null ? 0 : current.value;
        chips.addView(chip(getString(R.string.automation_days_workdays_chip), mask == AutomationCondition.WORKDAYS,
                v -> setDays(mask == AutomationCondition.WORKDAYS ? 0 : AutomationCondition.WORKDAYS)));
        chips.addView(chip(getString(R.string.automation_days_weekend_chip), mask == AutomationCondition.WEEKEND,
                v -> setDays(mask == AutomationCondition.WEEKEND ? 0 : AutomationCondition.WEEKEND)));
        ChipGroup days = chipRow(whenPane);
        days.setPaddingRelative(dp(34), dp(8), 0, 0);
        String[] names = AutomationCondition.shortDayNames();
        for (int i = 0; i < 7; i++) {
            int bit = AutomationCondition.MONDAY << i;
            TextView day = chip(names[i], (mask & bit) != 0, v -> setDays(mask ^ bit));
            day.setMinWidth(dp(56));
            day.setPadding(dp(10), 0, dp(10), 0);
            days.addView(day);
        }
    }

    /**
     * After or before a time of day: pick which, then the time ("After 18:00"). A second tap on
     * After or Before takes it back; the time chip opens the clock.
     */
    private void drawTime() {
        AutomationCondition.Group group = AutomationCondition.Group.TIME;
        ChipGroup chips = addSection(whenPane, group.iconRes, getString(group.labelRes));
        AutomationRule.Condition current = draft.condition(group);
        AutomationCondition picked = current == null ? null : current.kind();
        for (AutomationCondition kind : new AutomationCondition[]{
                AutomationCondition.AFTER_TIME, AutomationCondition.BEFORE_TIME}) {
            boolean on = kind == picked;
            int label = kind == AutomationCondition.AFTER_TIME
                    ? R.string.automation_chip_after : R.string.automation_chip_before;
            chips.addView(chip(getString(label), on, v -> {
                if (on) {
                    draft.setCondition(group, null, 0);
                    draw();
                } else if (current != null) {
                    draft.setCondition(group, kind, current.value);
                    draw();
                } else {
                    pickTime(kind, AutomationCondition.DEFAULT_MINUTE);
                }
            }));
        }
        if (picked == null) {
            return;
        }
        ChipGroup row = chipRow(whenPane);
        row.setPaddingRelative(dp(34), dp(8), 0, 0);
        TextView time = chip(AutomationCondition.clock(this, current.value), true,
                v -> pickTime(picked, current.value));
        time.setMinWidth(dp(96));
        row.addView(time);
    }

    /** A 24-hour clock picker; the condition is set only when a time is chosen. */
    private void pickTime(AutomationCondition kind, int minute) {
        new android.app.TimePickerDialog(this, (view, hour, min) -> {
            draft.setCondition(AutomationCondition.Group.TIME, kind, hour * 60 + min);
            draw();
        }, minute / 60, minute % 60, true).show();
    }

    /** No day at all, or every day, is the same as not asking. */
    private void setDays(int mask) {
        boolean any = mask == 0 || (mask & AutomationCondition.EVERY_DAY) == AutomationCondition.EVERY_DAY;
        draft.setCondition(AutomationCondition.Group.DAYS, any ? null : AutomationCondition.DAYS, mask);
        draw();
    }

    /** Dark or light, raining or dry: one of the two, a second tap takes it back. */
    private void drawStates(AutomationCondition.Group group) {
        ChipGroup chips = addSection(whenPane, group.iconRes, getString(group.labelRes));
        AutomationRule.Condition current = draft.condition(group);
        for (AutomationCondition kind : AutomationCondition.values()) {
            if (kind.group != group) {
                continue;
            }
            boolean on = current != null && kind == current.kind();
            chips.addView(chip(getString(stateChip(kind)), on, v -> {
                draft.setCondition(group, on ? null : kind, 0);
                draw();
            }));
        }
    }

    private static int stateChip(AutomationCondition kind) {
        switch (kind) {
            case DARK: return R.string.automation_chip_is_dark;
            case LIGHT: return R.string.automation_chip_is_light;
            case RAINING: return R.string.automation_chip_raining;
            default: return R.string.automation_chip_dry;
        }
    }

    /** At or away from a saved place: pick which, then the place. */
    private void drawPlaceCondition() {
        AutomationCondition.Group group = AutomationCondition.Group.PLACE;
        ChipGroup chips = addSection(whenPane, group.iconRes, getString(group.labelRes));
        AutomationRule.Condition current = draft.condition(group);
        AutomationCondition picked = current == null ? null : current.kind();
        List<SavedPlaces.Place> places = SavedPlaces.load(this);
        for (AutomationCondition kind : new AutomationCondition[]{
                AutomationCondition.AT_PLACE, AutomationCondition.AWAY_FROM_PLACE}) {
            boolean on = kind == picked;
            int label = kind == AutomationCondition.AT_PLACE ? R.string.automation_chip_at : R.string.automation_chip_away;
            chips.addView(chip(getString(label), on, v -> {
                if (on) {
                    draft.setCondition(group, null, 0);
                    draw();
                } else if (current != null) {
                    draft.setCondition(group, kind, current.value);
                    draw();
                } else if (places.isEmpty()) {
                    askSaveSpot(place -> draft.setCondition(group, kind, place.id));
                } else {
                    draft.setCondition(group, kind, places.get(0).id);
                    draw();
                }
            }));
        }
        if (picked == null) {
            return;
        }
        ChipGroup row = chipRow(whenPane);
        row.setPaddingRelative(dp(34), dp(8), 0, 0);
        for (SavedPlaces.Place place : places) {
            row.addView(chip(place.name, current.value == place.id, v -> {
                draft.setCondition(group, picked, place.id);
                draw();
            }));
        }
        row.addView(saveSpotChip(place -> draft.setCondition(group, picked, place.id)));
    }

    /** The saved places under Arrive and Leave; hold one to delete it. */
    private void drawPlaces(AutomationTrigger picked) {
        ChipGroup chips = chipRow(whenPane);
        chips.setPaddingRelative(dp(34), dp(8), 0, 0);
        for (SavedPlaces.Place place : SavedPlaces.load(this)) {
            TextView row = chip(place.name, picked != null && draft.value == place.id,
                    v -> setPlace(picked == null ? AutomationTrigger.PLACE_ARRIVE : picked, place.id));
            row.setOnLongClickListener(v -> {
                askDeletePlace(place);
                return true;
            });
            chips.addView(row);
        }
        chips.addView(saveSpotChip(place -> setPlace(picked == null ? AutomationTrigger.PLACE_ARRIVE : picked, place.id)));
    }

    private void setPlace(AutomationTrigger trigger, int placeId) {
        draft.trigger = trigger.key;
        draft.value = placeId;
        draw();
    }

    private void askDeletePlace(SavedPlaces.Place place) {
        CamDialogs.show(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(getString(R.string.place_delete_title, place.name))
                .setMessage(R.string.place_delete_message)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    List<SavedPlaces.Place> places = SavedPlaces.load(this);
                    places.removeIf(other -> other.id == place.id);
                    SavedPlaces.save(this, places);
                    Automation.reload();
                    draw();
                })
                .setNegativeButton(android.R.string.cancel, null));
    }

    private TextView saveSpotChip(Consumer<SavedPlaces.Place> saved) {
        TextView add = chip(getString(R.string.place_save_here), false, v -> askSaveSpot(saved));
        add.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_add, 0, 0, 0);
        add.setCompoundDrawablePadding(dp(8));
        return add;
    }

    /** Names the car's position now and saves it; {@code saved} gets it, then the page redraws. */
    private void askSaveSpot(Consumer<SavedPlaces.Place> saved) {
        VehicleState car = Telemetry.get().latest();
        if (car == null || car.latitude == null || car.longitude == null) {
            Notice.makeText(this, R.string.place_no_fix, Toast.LENGTH_LONG).show();
            return;
        }
        SavePlaceDialog.ask(this, car.latitude, car.longitude, "", place -> {
            saved.accept(place);
            draw();
        });
    }

    private void pickTrigger(AutomationTrigger trigger) {
        if (trigger.isPlace()) {
            AutomationTrigger was = draft.trigger();
            if (was != null && was.isPlace()) {
                // Arrive and Leave swap, the place stays
                setPlace(trigger, draft.value);
                return;
            }
            List<SavedPlaces.Place> places = SavedPlaces.load(this);
            if (places.isEmpty()) {
                askSaveSpot(place -> setPlace(trigger, place.id));
            } else {
                setPlace(trigger, places.get(0).id);
            }
            return;
        }
        boolean sameMeasure = draft.trigger() != null && draft.trigger().measure == trigger.measure;
        draft.trigger = trigger.key;
        if (trigger.measure == null) {
            draft.value = 0;
        } else if (!sameMeasure) {
            draft.value = trigger.measure.fallback;
        }
        draw();
    }

    /** A slider for the number, with the value in large type over it. */
    private View number(AutomationTrigger trigger) {
        AutomationTrigger.Measure m = trigger.measure;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(44), dp(6), 0, dp(4));
        TextView value = new TextView(this);
        value.setTextAppearance(R.style.TextAppearance_Cam_Title);
        value.setTextColor(ContextCompat.getColor(this, R.color.energy));
        value.setText(getString(trigger.group.unitRes, draft.value));
        box.addView(value);
        SeekBar bar = new SeekBar(this);
        bar.setMax((m.to - m.from) / m.step);
        bar.setProgress((draft.value - m.from) / m.step);
        bar.setContentDescription(getString(trigger.group.labelRes));
        ColorStateList energy = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.energy));
        bar.setProgressTintList(energy);
        bar.setThumbTintList(energy);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    draft.value = m.from + progress * m.step;
                    value.setText(getString(trigger.group.unitRes, draft.value));
                    paintSentence();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        box.addView(bar);
        return box;
    }

    // ---- Do

    private void drawDo() {
        doPane.removeAllViews();
        doPane.addView(heading(R.string.automation_do_heading));
        TextView hint = AutomationActivity.text(this, getString(R.string.automation_do_hint), false);
        hint.setPadding(0, 0, 0, dp(4));
        doPane.addView(hint);
        for (AutomationAction action : AutomationAction.values()) {
            if (action != AutomationAction.SHORTCUT && offered(action)) {
                drawAction(action);
            }
        }
        drawShortcuts();
    }

    /** Navigation needs the TomTom key. */
    private boolean offered(AutomationAction action) {
        return action != AutomationAction.NAVIGATE || (Navigator.active(this) && Navigator.hasKey());
    }

    private void drawAction(AutomationAction action) {
        ChipGroup chips = addSection(doPane, action.iconRes, getString(action.labelRes));
        String current = choiceOf(action);
        for (AutomationAction.Option option : action.options(this)) {
            boolean on = option.choice.equals(current);
            View.OnClickListener tap = v -> {
                setChoice(action, on ? null : option.choice);
                draw();
            };
            chips.addView(option.color < 0 ? chip(option.label, on, tap) : swatch(option, on, tap));
        }
        if (action == AutomationAction.NAVIGATE) {
            chips.addView(saveSpotChip(place -> setChoice(action, Integer.toString(place.id))));
        }
    }

    private void drawShortcuts() {
        ChipGroup chips = addSection(doPane, AutomationAction.SHORTCUT.iconRes,
                getString(AutomationAction.SHORTCUT.labelRes));
        for (AutomationRule.Step step : draft.steps) {
            if (AutomationAction.SHORTCUT.key.equals(step.action)) {
                TextView remove = chip(AutomationAction.describe(this, step), true, v -> {
                    draft.steps.remove(step);
                    draw();
                });
                remove.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_close, 0);
                remove.setCompoundDrawablePadding(dp(8));
                remove.setCompoundDrawableTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.on_energy)));
                chips.addView(remove);
            }
        }
        TextView add = chip(getString(R.string.automation_add_shortcut), false, v -> pickShortcut());
        add.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_add, 0, 0, 0);
        add.setCompoundDrawablePadding(dp(8));
        chips.addView(add);
    }

    /** The same tabs as buttons and the Macro pad, without what needs a person there. */
    private void pickShortcut() {
        int pad = dp(24);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(pad, dp(8), pad, 0);
        LinearLayout tabs = new LinearLayout(this);
        LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabParams.bottomMargin = dp(12);
        body.addView(tabs, tabParams);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        body.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog[] shown = new AlertDialog[1];
        ActionTabs.fill(tabs, list, 52, null, AutomationAction::offered, picked -> {
            if (shown[0] != null) {
                shown[0].dismiss();
            }
            if (picked == ShortcutAction.ANNOUNCE_PLAY) {
                AnnouncePick.choose(this, (key, name) -> addShortcut(key));
            } else {
                addShortcut(picked.key);
            }
        });
        shown[0] = CamDialogs.show(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.automation_do_shortcut)
                .setView(body)
                .setNegativeButton(android.R.string.cancel, null));
        if (shown[0] != null && shown[0].getWindow() != null) {
            shown[0].getWindow().setLayout(Math.round(getResources().getDisplayMetrics().widthPixels * 0.7f),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void addShortcut(String key) {
        draft.steps.add(new AutomationRule.Step(AutomationAction.SHORTCUT.key, key));
        draw();
    }

    private String choiceOf(AutomationAction action) {
        for (AutomationRule.Step step : draft.steps) {
            if (action.key.equals(step.action)) {
                return step.choice;
            }
        }
        return null;
    }

    /** Sets the action's one choice, or removes it when {@code choice} is null. */
    private void setChoice(AutomationAction action, String choice) {
        for (Iterator<AutomationRule.Step> it = draft.steps.iterator(); it.hasNext(); ) {
            AutomationRule.Step step = it.next();
            if (action.key.equals(step.action)) {
                if (choice == null) {
                    it.remove();
                } else {
                    step.choice = choice;
                }
                return;
            }
        }
        if (choice != null) {
            draft.steps.add(new AutomationRule.Step(action.key, choice));
        }
    }

    // ---- Top of the page

    /** Reads the rule back, or says what is still missing. */
    private void paintSentence() {
        boolean hasWhen = draft.trigger() != null;
        boolean hasDo = !draft.steps.isEmpty();
        if (!hasWhen) {
            sentence.setText(R.string.automation_pick_when);
        } else if (!hasDo) {
            sentence.setText(getString(R.string.automation_pick_do, AutomationActivity.whenText(this, draft)));
        } else {
            sentence.setText(getString(R.string.automation_sentence, AutomationActivity.whenText(this, draft),
                    AutomationAction.describeAll(this, draft.steps)));
        }
        tryButton.setEnabled(hasDo);
        tryButton.setAlpha(hasDo ? 1f : 0.4f);
        saveButton.setAlpha(hasWhen && hasDo ? 1f : 0.4f);
    }

    private void save() {
        if (draft.trigger() == null || draft.steps.isEmpty()) {
            Notice.makeText(this, draft.trigger() == null ? R.string.automation_needs_when
                    : R.string.automation_needs_action, Toast.LENGTH_SHORT).show();
            return;
        }
        store(draft, false);
        finish();
    }

    private void store(AutomationRule rule, boolean remove) {
        AppConfig config = new AppConfig(this);
        List<AutomationRule> rules = AutomationRule.parseAll(config.getAutomationRules());
        int at = -1;
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).id.equals(rule.id)) {
                at = i;
            }
        }
        if (at >= 0) {
            rules.remove(at);
        }
        if (!remove) {
            rules.add(at >= 0 ? at : rules.size(), rule);
        }
        config.setAutomationRules(AutomationRule.toJson(rules));
        Automation.reload();
    }

    private void askDelete() {
        CamDialogs.show(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.automation_delete_title)
                .setMessage(AutomationActivity.whenText(this, draft))
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    store(draft, true);
                    finish();
                })
                .setNegativeButton(android.R.string.cancel, null));
    }

    /** Leaves, after asking when there are choices that would be lost. */
    private void leave() {
        boolean blank = draft.trigger() == null && draft.steps.isEmpty();
        if (blank || state().equals(startedAs)) {
            finish();
            return;
        }
        CamDialogs.show(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.automation_discard_title)
                .setPositiveButton(R.string.automation_discard, (dialog, which) -> finish())
                .setNegativeButton(R.string.automation_keep_editing, null));
    }

    // ---- Views

    private TextView heading(int text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextAppearance(R.style.TextAppearance_Cam_Row);
        view.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        view.setPadding(0, 0, 0, dp(4));
        return view;
    }

    /** A small icon and name with a wrapping row of choices beside it; returns that row. */
    private ChipGroup addSection(LinearLayout pane, int icon, String name) {
        LinearLayout title = new LinearLayout(this);
        title.setOrientation(LinearLayout.HORIZONTAL);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(0, dp(14), 0, dp(6));
        ImageView image = new ImageView(this);
        image.setImageResource(icon);
        title.addView(image, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView label = AutomationActivity.text(this, name, false);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.setMarginStart(dp(10));
        title.addView(label, labelParams);
        pane.addView(title);
        return chipRow(pane);
    }

    /** A wrapping row of choices, added to {@code pane}. */
    private ChipGroup chipRow(LinearLayout pane) {
        ChipGroup chips = new ChipGroup(this);
        chips.setChipSpacingHorizontal(dp(8));
        chips.setChipSpacingVertical(dp(8));
        chips.setPaddingRelative(dp(34), 0, 0, 0);
        pane.addView(chips, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return chips;
    }

    private TextView chip(String label, boolean on, View.OnClickListener tap) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setGravity(Gravity.CENTER);
        view.setTextAppearance(R.style.TextAppearance_Cam_Caption);
        view.setTextColor(ContextCompat.getColor(this, on ? R.color.on_energy : R.color.text_primary));
        view.setBackgroundResource(R.drawable.bg_automation_chip);
        view.setActivated(on);
        view.setPadding(dp(18), 0, dp(18), 0);
        view.setMinWidth(dp(64));
        view.setMinHeight(dp(46));
        view.setOnClickListener(tap);
        view.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)));
        return view;
    }

    /** A colour: a dot of it, ringed when picked. */
    private View swatch(AutomationAction.Option option, boolean on, View.OnClickListener tap) {
        View view = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(0xFF000000 | option.color);
        dot.setStroke(dp(on ? 4 : 1), ContextCompat.getColor(this, on ? R.color.energy : R.color.line));
        view.setBackground(dot);
        view.setContentDescription(option.label);
        view.setActivated(on);
        view.setOnClickListener(tap);
        view.setLayoutParams(new ViewGroup.LayoutParams(dp(46), dp(46)));
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
