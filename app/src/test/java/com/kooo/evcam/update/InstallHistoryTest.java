package com.kooo.evcam.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Lines in, lines out: what a removal or an OTA looks like in the history. */
public class InstallHistoryTest {

    private static InstallHistory.Entry entry(long seen, long installed, long updated, String build) {
        InstallHistory.Entry e = new InstallHistory.Entry();
        e.seen = seen;
        e.installed = installed;
        e.updated = updated;
        e.version = "1.3.9 (42)";
        e.build = build;
        e.incremental = "inc-" + build;
        e.buildTime = 1000L;
        e.fingerprint = "zeekr/" + build;
        return e;
    }

    @Test
    public void aLineReadsBackTheSame() {
        InstallHistory.Entry e = entry(5L, 3L, 4L, "OS 6.1\tbeta");
        InstallHistory.Entry back = InstallHistory.parse(InstallHistory.format(e));
        assertEquals(5L, back.seen);
        assertEquals(3L, back.installed);
        assertEquals(4L, back.updated);
        assertEquals("tabs in a field do not split the line", "OS 6.1 beta", back.build);
        assertEquals("1.3.9 (42)", back.version);
        assertEquals("zeekr/OS 6.1 beta", back.fingerprint);
    }

    @Test
    public void foreignLinesAreIgnored() {
        assertNull(InstallHistory.parse("hello"));
        assertNull(InstallHistory.parse(""));
        assertNull(InstallHistory.parse(null));
    }

    @Test
    public void sameInstallAndSystemIsNotNew() {
        InstallHistory.Entry a = entry(1L, 10L, 20L, "A");
        assertTrue(a.sameAs(entry(99L, 10L, 20L, "A")));
        assertFalse("reinstalled", a.sameAs(entry(99L, 11L, 20L, "A")));
        assertFalse("updated", a.sameAs(entry(99L, 10L, 21L, "A")));
        assertFalse("OTA", a.sameAs(entry(99L, 10L, 20L, "B")));
        assertFalse(a.sameAs(null));
    }

    /** The stick's copy (from before the removal) and the fresh app storage come out as one timeline. */
    @Test
    public void copiesMergeOldestFirstWithoutDuplicates() {
        String old = InstallHistory.format(entry(100L, 10L, 10L, "A"));
        String ota = InstallHistory.format(entry(200L, 10L, 10L, "B"));
        String fresh = InstallHistory.format(entry(300L, 250L, 250L, "B"));
        List<String> usb = Arrays.asList(old, ota, "junk");
        List<String> local = Collections.singletonList(fresh);
        assertEquals(Arrays.asList(old, ota, fresh), InstallHistory.merge(local, usb, 100));
        assertEquals(Arrays.asList(old, ota, fresh), InstallHistory.merge(usb, Arrays.asList(fresh, old), 100));
    }

    @Test
    public void onlyTheNewestLinesAreKept() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            lines.add(InstallHistory.format(entry(i, i, i, "A")));
        }
        assertEquals(lines.subList(2, 5), InstallHistory.merge(lines, Collections.emptyList(), 3));
    }

    @Test
    public void changesAreNamed() {
        InstallHistory.Entry a = entry(1L, 10L, 10L, "A");
        assertEquals("first line", InstallHistory.change(null, a));
        assertEquals("no change", InstallHistory.change(a, entry(2L, 10L, 10L, "A")));
        assertEquals("app updated", InstallHistory.change(a, entry(2L, 10L, 11L, "A")));
        assertEquals("SYSTEM CHANGED (A -> B)", InstallHistory.change(a, entry(2L, 10L, 10L, "B")));
        assertEquals("REINSTALLED (removed since the line before), SYSTEM CHANGED (A -> B)",
                InstallHistory.change(a, entry(2L, 12L, 12L, "B")));
    }

    @Test
    public void describeShowsEveryLine() {
        String text = InstallHistory.describe(Arrays.asList(
                InstallHistory.format(entry(1L, 10L, 10L, "A")),
                InstallHistory.format(entry(2L, 12L, 12L, "A"))));
        assertTrue(text.contains("first line"));
        assertTrue(text.contains("REINSTALLED"));
        assertEquals("(none yet)\n", InstallHistory.describe(Collections.emptyList()));
    }
}
