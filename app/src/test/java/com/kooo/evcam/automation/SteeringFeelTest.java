package com.kooo.evcam.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SteeringFeelTest {

    @Test
    public void choicesMapOntoTheCarsValues() {
        assertEquals(Integer.valueOf(570624257), SteeringFeel.value("light"));
        assertEquals(Integer.valueOf(570624258), SteeringFeel.value("balanced"));
        assertEquals(Integer.valueOf(570624259), SteeringFeel.value("heavy"));
        assertNull(SteeringFeel.value("sport"));
        assertNull(SteeringFeel.value(null));
    }

    @Test
    public void oneButtonGoesRoundTheThree() {
        assertEquals(SteeringFeel.BALANCED, SteeringFeel.next(SteeringFeel.LIGHT));
        assertEquals(SteeringFeel.HEAVY, SteeringFeel.next(SteeringFeel.BALANCED));
        assertEquals(SteeringFeel.LIGHT, SteeringFeel.next(SteeringFeel.HEAVY));
        assertEquals(SteeringFeel.LIGHT, SteeringFeel.next(null));
    }

    @Test
    public void storedKeyStaysTheSame() {
        assertEquals(AutomationAction.STEERING_FEEL, AutomationAction.fromKey("steering_feel"));
    }
}
