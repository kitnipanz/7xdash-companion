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

    @Test
    public void withAPlaceThereAreExamplesAboutIt() {
        java.util.List<com.kooo.evcam.nav.SavedPlaces.Place> places = new java.util.ArrayList<>();
        com.kooo.evcam.nav.SavedPlaces.add(places, "Home", 13.7, 100.5);
        List<AutomationRule> examples = AutomationExamples.all(places);
        assertEquals(AutomationExamples.all().size() + 2, examples.size());
        assertEquals(examples.size(), AutomationRule.parseAll(AutomationRule.toJson(examples)).size());
    }
}
