package com.kooo.evcam.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class AutomationExamplesTest {

    @Test
    public void everyExampleWouldSaveAndRun() {
        List<AutomationRule> examples = AutomationExamples.all();
        assertFalse(examples.isEmpty());
        for (AutomationRule example : examples) {
            assertNotNull(example.trigger());
            assertFalse(example.steps.isEmpty());
            for (AutomationRule.Step step : example.steps) {
                assertNotNull(AutomationAction.fromKey(step.action));
            }
        }
        assertEquals(examples.size(), AutomationRule.parseAll(AutomationRule.toJson(examples)).size());
    }

    @Test
    public void homeExamples_onlyWithASavedPlace() {
        List<AutomationRule> noHome = AutomationExamples.all();
        assertEquals(4, noHome.size());
        // with no home saved, P remembers where the car is every time (no condition)
        AutomationRule anyP = noHome.get(1);
        assertEquals(AutomationTrigger.GEAR_P, anyP.trigger());
        assertTrue(anyP.conditions.isEmpty());
        assertEquals(com.kooo.evcam.input.ShortcutAction.WHERE_PARKED.key, anyP.steps.get(0).choice);
        assertEquals(AutomationTrigger.RAIN_START, noHome.get(2).trigger());
        assertEquals(AutomationCondition.DARK,
                noHome.get(2).condition(AutomationCondition.Group.DAYLIGHT).kind());
        assertEquals(AutomationAction.REAR_FOG.key, noHome.get(2).steps.get(0).action);

        java.util.List<com.kooo.evcam.nav.SavedPlaces.Place> places = new java.util.ArrayList<>();
        com.kooo.evcam.nav.SavedPlaces.add(places, "Office", 13.8, 100.6);
        com.kooo.evcam.nav.SavedPlaces.Place home = com.kooo.evcam.nav.SavedPlaces.add(places, "Home", 13.7, 100.5);
        List<AutomationRule> examples = AutomationExamples.all(places);
        assertEquals(6, examples.size());

        AutomationRule evening = examples.get(0);
        assertEquals(AutomationTrigger.GEAR_D, evening.trigger());
        AutomationRule.Condition after = evening.condition(AutomationCondition.Group.TIME);
        assertEquals(AutomationCondition.AFTER_TIME, after.kind());
        assertEquals(18 * 60, after.value);
        assertEquals(AutomationAction.SUNROOF_SHADE.key, evening.steps.get(0).action);
        assertEquals("100", evening.steps.get(0).choice);
        assertEquals(AutomationAction.DIM.key, evening.steps.get(1).action);
        assertEquals("30", evening.steps.get(1).choice);

        // "Home" is found by name, not just the first place
        assertEquals(AutomationTrigger.PLACE_ARRIVE, examples.get(1).trigger());
        assertEquals(home.id, examples.get(1).value);
        assertEquals("100", examples.get(1).steps.get(0).choice);
        assertEquals(AutomationTrigger.PLACE_LEAVE, examples.get(2).trigger());
        assertEquals(home.id, examples.get(2).value);
        assertEquals("60", examples.get(2).steps.get(0).choice);

        AutomationRule parked = examples.get(3);
        assertEquals(AutomationTrigger.GEAR_P, parked.trigger());
        assertEquals(AutomationCondition.AWAY_FROM_PLACE, parked.condition(AutomationCondition.Group.PLACE).kind());
        assertEquals(home.id, parked.condition(AutomationCondition.Group.PLACE).value);
        assertEquals(com.kooo.evcam.input.ShortcutAction.WHERE_PARKED.key, parked.steps.get(0).choice);

        assertEquals(AutomationTrigger.RAIN_START, examples.get(4).trigger());
        assertEquals(AutomationTrigger.DARK, examples.get(5).trigger());
    }

    @Test
    public void anExampleAlreadyAddedIsNotOfferedAgain() {
        List<AutomationRule> examples = AutomationExamples.all();
        List<AutomationRule> mine = new java.util.ArrayList<>();
        mine.add(AutomationRule.parseAll(AutomationRule.toJson(java.util.Collections.singletonList(examples.get(0)))).get(0));
        assertTrue(AutomationExamples.alreadyHave(mine, examples.get(0)));
        assertFalse(AutomationExamples.alreadyHave(mine, examples.get(1)));
        mine.get(0).steps.get(1).choice = "50";
        assertFalse(AutomationExamples.alreadyHave(mine, examples.get(0)));
    }

    @Test
    public void draftWithNoTriggerYet_survivesTheScreenBeingRebuilt() {
        AutomationRule draft = new AutomationRule();
        draft.steps.add(new AutomationRule.Step(AutomationAction.DRIVE_MODE.key, "normal"));
        AutomationRule back = AutomationRule.fromDraft(draft.toDraft());
        assertEquals(draft.id, back.id);
        assertEquals("normal", back.steps.get(0).choice);
    }

    @Test
    public void numbersOfExamplesAreOnTheSlider() {
        for (AutomationRule example : AutomationExamples.all()) {
            AutomationTrigger.Measure m = example.trigger().measure;
            if (m != null) {
                assertEquals(0, (example.value - m.from) % m.step);
            }
        }
    }

    @Test
    public void everyTriggerBelongsToAGroupWithAChip() {
        for (AutomationTrigger trigger : AutomationTrigger.values()) {
            assertNotNull(trigger.group);
        }
    }
}
