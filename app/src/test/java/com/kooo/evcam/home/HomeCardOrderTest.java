package com.kooo.evcam.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class HomeCardOrderTest {

    private static final List<String> ALL = Arrays.asList("a", "b", "c", "d");
    private static final Set<String> NONE = Collections.emptySet();

    private static Set<String> set(String... ids) {
        return new HashSet<>(Arrays.asList(ids));
    }

    @Test
    public void nothingSaved_showsEveryCardInItsUsualOrder() {
        assertEquals(ALL, HomeCardOrder.visible(ALL, Collections.emptyList(), NONE));
    }

    @Test
    public void savedOrderWins_andHiddenCardsStayOut() {
        assertEquals(Arrays.asList("c", "a", "d"),
                HomeCardOrder.visible(ALL, Arrays.asList("c", "b", "a", "d"), set("b")));
    }

    @Test
    public void cardFromALaterVersion_appearsAtTheEnd() {
        assertEquals(Arrays.asList("b", "a", "c", "d"),
                HomeCardOrder.visible(ALL, Arrays.asList("b", "a"), NONE));
    }

    @Test
    public void cardNotOfferedNow_isSkippedButKeepsItsPlace() {
        List<String> order = Arrays.asList("x", "a", "b");
        List<String> shown = HomeCardOrder.visible(Arrays.asList("a", "b"), order, NONE);
        assertEquals(Arrays.asList("a", "b"), shown);
        List<String> moved = Arrays.asList("b", "a");
        assertEquals(Arrays.asList("b", "a", "x"), HomeCardOrder.merged(moved, order));
    }

    @Test
    public void hiddenListsOnlyCardsOfferedNow() {
        assertEquals(Arrays.asList("b", "d"), HomeCardOrder.hidden(ALL, set("d", "b", "x")));
        assertTrue(HomeCardOrder.hidden(ALL, NONE).isEmpty());
    }

    @Test
    public void everyCardRemoved_showsNone() {
        assertTrue(HomeCardOrder.visible(ALL, ALL, new HashSet<>(ALL)).isEmpty());
    }

    @Test
    public void movingInATab_leavesTheOtherTabInPlace() {
        // b and d are beta; swap them while a and c stay put
        assertEquals(Arrays.asList("a", "d", "c", "b"),
                HomeCardOrder.withTabOrder(ALL, Arrays.asList("d", "b")));
    }

    @Test
    public void savedText_comesBack() {
        assertEquals(ALL, HomeCardOrder.parse(HomeCardOrder.write(ALL)));
        assertTrue(HomeCardOrder.parse(null).isEmpty());
        assertTrue(HomeCardOrder.parse("").isEmpty());
    }
}
