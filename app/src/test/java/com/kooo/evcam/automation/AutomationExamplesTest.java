package com.kooo.evcam.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

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
    public void leaveHomeExample_onlyWithASavedPlace() {
        assertEquals(2, AutomationExamples.all().size());
        java.util.List<com.kooo.evcam.nav.SavedPlaces.Place> places = new java.util.ArrayList<>();
        com.kooo.evcam.nav.SavedPlaces.Place home = com.kooo.evcam.nav.SavedPlaces.add(places, "Home", 13.7, 100.5);
        List<AutomationRule> examples = AutomationExamples.all(places);
        assertEquals(3, examples.size());
        assertEquals(AutomationTrigger.PLACE_LEAVE, examples.get(0).trigger());
        assertEquals(home.id, examples.get(0).value);
        assertEquals(AutomationAction.TAILGATE_HEIGHT.key, examples.get(0).steps.get(0).action);
        AutomationRule fog = examples.get(1);
        assertEquals(AutomationTrigger.RAIN_START, fog.trigger());
        assertEquals(AutomationCondition.DARK, fog.condition(AutomationCondition.Group.DAYLIGHT).kind());
        assertEquals(AutomationAction.REAR_FOG.key, fog.steps.get(0).action);
        assertEquals(AutomationTrigger.DARK, examples.get(2).trigger());
        assertEquals(AutomationAction.MOOD_LIGHT.key, examples.get(2).steps.get(0).action);
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
