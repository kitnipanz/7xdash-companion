package com.kooo.evcam.home;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which home cards show and in what order, from what the person saved. Pure, so it is unit tested.
 *
 * <p>Saves the order and what is hidden, not what is shown: a card added in a later version is
 * in neither, so it appears at the end by itself. Ids that are not offered now (a beta card while
 * Beta features is off) stay in the saved lists, so the card comes back to its place.</p>
 */
final class HomeCardOrder {

    private static final String SEPARATOR = ",";

    private HomeCardOrder() {
    }

    /** The cards to show: saved order first, then any the order does not know, as {@code all} has them. */
    static List<String> visible(List<String> all, List<String> order, Set<String> hidden) {
        Set<String> out = new LinkedHashSet<>();
        for (String id : order) {
            if (all.contains(id) && !hidden.contains(id)) {
                out.add(id);
            }
        }
        for (String id : all) {
            if (!hidden.contains(id)) {
                out.add(id);
            }
        }
        return new ArrayList<>(out);
    }

    /** Hidden cards that could be added back, as {@code all} has them. */
    static List<String> hidden(List<String> all, Set<String> hidden) {
        List<String> out = new ArrayList<>();
        for (String id : all) {
            if (hidden.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /** The order to save: what shows now, then the rest of the old order so nothing forgets its place. */
    static List<String> merged(List<String> shown, List<String> oldOrder) {
        Set<String> out = new LinkedHashSet<>(shown);
        out.addAll(oldOrder);
        return new ArrayList<>(out);
    }

    /**
     * The whole page after a tab's cards were put in {@code tabOrder}: those cards take each other's
     * places, and a card of the other tab stays exactly where it is.
     */
    static List<String> withTabOrder(List<String> shown, List<String> tabOrder) {
        List<String> out = new ArrayList<>(shown);
        int next = 0;
        for (int i = 0; i < out.size() && next < tabOrder.size(); i++) {
            if (tabOrder.contains(out.get(i))) {
                out.set(i, tabOrder.get(next++));
            }
        }
        return out;
    }

    static String write(Iterable<String> ids) {
        return String.join(SEPARATOR, ids);
    }

    static List<String> parse(String stored) {
        List<String> out = new ArrayList<>();
        if (stored != null) {
            for (String id : Arrays.asList(stored.split(SEPARATOR))) {
                if (!id.isEmpty()) {
                    out.add(id);
                }
            }
        }
        return out;
    }
}
