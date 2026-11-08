package com.kooo.evcam.automation;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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
import com.kooo.evcam.nav.PlacePickActivity;
import com.kooo.evcam.nav.SavePlaceDialog;
import com.kooo.evcam.nav.SavedPlaces;
import com.kooo.evcam.telemetry.Telemetry;
import com.kooo.evcam.telemetry.VehicleState;
import com.kooo.evcam.ui.CamDialogs;
import com.kooo.evcam.ui.Notice;

import java.text.DateFormatSymbols;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * One automation on one page. Everything to choose from is in view: When on the left, Do on the
 * right, a sentence at the bottom beside Save that reads back what the choices add up to. A tap picks, a second tap
 * on the same choice takes it back. When can hold one choice per group, all of which must be true;
 * an action holds one choice; shortcuts can be several.
 */
public class AutomationEditActivity extends AppCompatActivity {

    /** The rule as {@link AutomationRule#toJson}, a list of one. */
    static final String EXTRA_RULE = "rule";
    /** Not in the list yet: no Delete, and Save adds it. */
    static final String EXTRA_NEW = "new";

    private static final String KEPT_DRAFT = "draft";
    private static final String KEPT_START = "started_as";
    private static final String KEPT_PICK_FOR = "pick_for";
    /** Registered while this page shows, so "Save this spot" has a position. */
    private static final String TELEMETRY_USER = "automation_edit";
    private static final int DEFAULT_TIME = 8 * 60;
    private static final int DEFAULT_SPAN = AutomationClock.span(7 * 60, 9 * 60);

    private AutomationRule draft;
    private String startedAs;
    private boolean isNew;
    private LinearLayout whenPane;
    private LinearLayout doPane;
    private TextView sentence;
    private View tryButton;
    private View saveButton;
    /**
     * What a place picked on the map is for: "add:" and a trigger key, "set" for the place
     * condition already there, "nav" for the Navigate action, "" to just save it. Kept with the draft.
     */
    private String pickFor = "";
    private final ActivityResultLauncher<android.content.Intent> pickPlace = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> placePicked(PlacePickActivity.placeId(result.getResultCode(), result.getData())));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_automation_edit);
        isNew = getIntent().getBooleanExtra(EXTRA_NEW, true);
        String json = getIntent().getStringExtra(EXTRA_RULE);
        if (savedInstanceState != null) {
            draft = AutomationRule.fromDraft(savedInstanceState.getString(KEPT_DRAFT));
            startedAs = savedInstanceState.getString(KEPT_START);
            pickFor = savedInstanceState.getString(KEPT_PICK_FOR, "");
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
        out.putString(KEPT_PICK_FOR, pickFor);
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
        TextView hint = AutomationActivity.text(this, getString(R.string.automation_when_hint), false);
        hint.setPadding(0, 0, 0, dp(4));
        whenPane.addView(hint);
        List<AutomationRule.Condition> conditions = draft.conditions();
        for (AutomationTrigger.Group group : AutomationTrigger.Group.values()) {
            ChipGroup chips = addSection(whenPane, group.iconRes, getString(group.labelRes));
            AutomationRule.Condition picked = inGroup(conditions, group);
            if (group == AutomationTrigger.Group.DAYS) {
                drawDays(chips, picked);
                continue;
            }
            for (AutomationTrigger trigger : AutomationTrigger.values()) {
                if (trigger.group == group) {
                    boolean on = picked != null && picked.trigger() == trigger;
                    chips.addView(chip(getString(trigger.chipRes), on, v -> pickTrigger(trigger)));
                }
            }
            if (picked != null && picked.trigger().measure != null) {
                whenPane.addView(number(picked));
            }
            if (group == AutomationTrigger.Group.TIME && picked != null) {
                drawTimes(chips, picked);
            }
            if (group == AutomationTrigger.Group.PLACE) {
                drawPlaces(picked);
            }
        }
    }

    private void drawDays(ChipGroup chips, AutomationRule.Condition picked) {
        int days = picked == null ? 0 : picked.value;
        chips.addView(chip(getString(R.string.automation_days_weekdays), days == AutomationClock.WEEKDAYS,
                v -> setDays(days == AutomationClock.WEEKDAYS ? 0 : AutomationClock.WEEKDAYS)));
        chips.addView(chip(getString(R.string.automation_days_weekend), days == AutomationClock.WEEKEND,
                v -> setDays(days == AutomationClock.WEEKEND ? 0 : AutomationClock.WEEKEND)));
        String[] names = DateFormatSymbols.getInstance(locale()).getShortWeekdays();
        for (int day : AutomationClock.WEEK) {
            int bit = AutomationClock.dayBit(day);
            chips.addView(chip(names[day], (days & bit) != 0, v -> setDays(days ^ bit)));
        }
    }

    private Locale locale() {
        return getResources().getConfiguration().getLocales().get(0);
    }

    /** No days left takes the condition away. */
    private void setDays(int days) {
        List<AutomationRule.Condition> conditions = draft.conditions();
        AutomationRule.Condition old = inGroup(conditions, AutomationTrigger.Group.DAYS);
        if (days == 0) {
            conditions.remove(old);
        } else if (old != null) {
            old.value = days;
        } else {
            conditions.add(new AutomationRule.Condition(AutomationTrigger.DAYS.key, days));
        }
        draft.setConditions(conditions);
        draw();
    }

    /** The times picked, as chips of their own: a tap changes one. */
    private void drawTimes(ChipGroup chips, AutomationRule.Condition picked) {
        if (picked.trigger() == AutomationTrigger.TIME_AT) {
            chips.addView(chip(AutomationClock.time(picked.value), true,
                    v -> askTime(picked.value, time -> setValue(picked, time))));
            return;
        }
        int from = AutomationClock.from(picked.value);
        int to = AutomationClock.to(picked.value);
        chips.addView(chip(AutomationClock.time(from), true,
                v -> askTime(from, time -> setValue(picked, AutomationClock.span(time, to)))));
        chips.addView(chip(AutomationClock.time(to), true,
                v -> askTime(to, time -> setValue(picked, AutomationClock.span(from, time)))));
    }

    private void askTime(int minutes, IntConsumer done) {
        NumberPicker hour = wheel(23, minutes / 60);
        NumberPicker minute = wheel(59, minutes % 60);
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER);
        box.addView(hour);
        box.addView(minute);
        CamDialogs.show(new MaterialAlertDialogBuilder(this, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.automation_pick_time)
                .setView(box)
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> done.accept(hour.getValue() * 60 + minute.getValue()))
                .setNegativeButton(android.R.string.cancel, null));
    }

    private NumberPicker wheel(int max, int value) {
        NumberPicker wheel = new NumberPicker(this);
        wheel.setMinValue(0);
        wheel.setMaxValue(max);
        wheel.setFormatter(number -> String.format(Locale.US, "%02d", number));
        wheel.setValue(value);
        return wheel;
    }

    /** The saved places under Arrive and Leave; hold one to delete it. */
    private void drawPlaces(AutomationRule.Condition picked) {
        ChipGroup chips = chipRow(whenPane);
        chips.setPaddingRelative(dp(34), dp(8), 0, 0);
        for (SavedPlaces.Place place : SavedPlaces.load(this)) {
            TextView row = chip(place.name, picked != null && picked.value == place.id, v -> {
                if (picked == null) {
                    addCondition(AutomationTrigger.PLACE_ARRIVE, place.id);
                } else {
                    setValue(picked, place.id);
                }
            });
            row.setOnLongClickListener(v -> {
                askDeletePlace(place);
                return true;
            });
            chips.addView(row);
        }
        chips.addView(saveSpotChip(place -> { }));
        if (PlacePickActivity.available()) {
            chips.addView(pickOnMapChip(picked == null ? "" : "set"));
        }
    }

    private TextView pickOnMapChip(String forWhat) {
        TextView pick = chip(getString(R.string.place_pick_on_map), false, v -> openPicker(forWhat));
        pick.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_automation_place, 0, 0, 0);
        pick.setCompoundDrawablePadding(dp(8));
        return pick;
    }

    private void openPicker(String forWhat) {
        pickFor = forWhat;
        pickPlace.launch(PlacePickActivity.intent(this));
    }

    /** Back from the map with a saved place (or -1): use it for what the map was opened for. */
    private void placePicked(int id) {
        String forWhat = pickFor;
        pickFor = "";
        if (id < 0) {
            return;
        }
        if (forWhat.startsWith("add:")) {
            AutomationTrigger trigger = AutomationTrigger.fromKey(forWhat.substring(4));
            if (trigger != null) {
                addCondition(trigger, id);
                return;
            }
        } else if (forWhat.equals("set")) {
            AutomationRule.Condition place = inGroup(draft.conditions(), AutomationTrigger.Group.PLACE);
            if (place != null) {
                setValue(place, id);
                return;
            }
        } else if (forWhat.equals("nav")) {
            setChoice(AutomationAction.NAVIGATE, Integer.toString(id));
        }
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

    private void addCondition(AutomationTrigger trigger, int value) {
        List<AutomationRule.Condition> conditions = draft.conditions();
        conditions.add(new AutomationRule.Condition(trigger.key, value));
        draft.setConditions(conditions);
        draw();
    }

    private void setValue(AutomationRule.Condition changed, int value) {
        setNumber(changed, value);
        draw();
    }

    private static AutomationRule.Condition inGroup(List<AutomationRule.Condition> conditions,
                                                    AutomationTrigger.Group group) {
        for (AutomationRule.Condition condition : conditions) {
            if (condition.trigger().group == group) {
                return condition;
            }
        }
        return null;
    }

    /** One choice per group: a tap picks it, another in the group replaces it, the same one again takes it back. */
    private void pickTrigger(AutomationTrigger trigger) {
        List<AutomationRule.Condition> conditions = draft.conditions();
        AutomationRule.Condition old = inGroup(conditions, trigger.group);
        if (old == null && trigger.group == AutomationTrigger.Group.PLACE) {
            List<SavedPlaces.Place> places = SavedPlaces.load(this);
            if (places.isEmpty() && PlacePickActivity.available()) {
                openPicker("add:" + trigger.key);
            } else if (places.isEmpty()) {
                askSaveSpot(place -> addCondition(trigger, place.id));
            } else {
                addCondition(trigger, places.get(0).id);
            }
            return;
        }
        int at = old == null ? conditions.size() : conditions.indexOf(old);
        conditions.remove(old);
        if (old == null || old.trigger() != trigger) {
            conditions.add(at, new AutomationRule.Condition(trigger.key, startingValue(trigger, old)));
        }
        draft.setConditions(conditions);
        draw();
    }

    private static int startingValue(AutomationTrigger trigger, AutomationRule.Condition old) {
        switch (trigger) {
            case TIME_AT: return DEFAULT_TIME;
            case TIME_BETWEEN: return DEFAULT_SPAN;
            default:
                if (old != null) {
                    return old.value;
                }
                return trigger.measure == null ? 0 : trigger.measure.fallback;
        }
    }

    private void setNumber(AutomationRule.Condition changed, int value) {
        List<AutomationRule.Condition> conditions = draft.conditions();
        AutomationRule.Condition now = inGroup(conditions, changed.trigger().group);
        if (now != null) {
            now.value = value;
            draft.setConditions(conditions);
        }
    }

    /** A slider for the number, with the value in large type over it. */
    private View number(AutomationRule.Condition condition) {
        AutomationTrigger trigger = condition.trigger();
        AutomationTrigger.Measure m = trigger.measure;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(44), dp(6), 0, dp(4));
        TextView value = new TextView(this);
        value.setTextAppearance(R.style.TextAppearance_Cam_Title);
        value.setTextColor(ContextCompat.getColor(this, R.color.energy));
        value.setText(getString(trigger.group.unitRes, condition.value));
        box.addView(value);
        SeekBar bar = new SeekBar(this);
        bar.setMax((m.to - m.from) / m.step);
        bar.setProgress((condition.value - m.from) / m.step);
        bar.setContentDescription(getString(trigger.group.labelRes));
        ColorStateList energy = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.energy));
        bar.setProgressTintList(energy);
        bar.setThumbTintList(energy);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    int number = m.from + progress * m.step;
                    setNumber(condition, number);
                    value.setText(getString(trigger.group.unitRes, number));
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
        return action != AutomationAction.NAVIGATE || Navigator.hasKey();
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
            chips.addView(pickOnMapChip("nav"));
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
