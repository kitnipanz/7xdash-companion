package com.kooo.evcam.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public class WhatsNewTest {

    private static final String LOG = "# Changelog\n\n"
            + "## [1.7.0-beta] - 2026-11-02\n\n### Look\n- **Bigger** clock\n\n"
            + "## [1.6.9-beta] - 2026-10-30\n\n### Removed\n- Sentry-lite\n\n"
            + "## [1.6.8-beta] - 2026-10-30\n\n### Look and feel\n- Home cards line up,\n  and Off is grey\n\n"
            + "## [1.6.7-beta] - 2026-10-30\n\n- Finder\n";

    @Test
    public void splitsIntoVersionsNewestFirst() {
        List<ReleaseNotes.Entry> v = WhatsNew.versions(LOG);
        assertEquals(4, v.size());
        assertEquals("1.7.0-beta", v.get(0).tagName);
        assertEquals("1.6.7-beta", v.get(3).tagName);
    }

    @Test
    public void showsEveryVersionSinceTheLastSeenUpToTheInstalledOne() {
        String text = WhatsNew.since(LOG, "1.6.7-beta", "1.6.9-beta");
        assertTrue(text, text.contains("[1.6.9-beta]") && text.contains("[1.6.8-beta]"));
        assertFalse("not past the installed one", text.contains("1.7.0") || text.contains("Bigger"));
        assertFalse("not the one already seen", text.contains("Finder"));
        // a wrapped bullet is joined back into one line, and the subheading stays
        assertTrue(text, text.contains("• Home cards line up, and Off is grey"));
        assertTrue(text, text.contains("Look and feel"));
    }

    @Test
    public void oneVersionHasNoVersionLabel() {
        String text = WhatsNew.since(LOG, "1.6.9-beta", "1.7.0-beta");
        assertEquals("Look\n• Bigger clock", text);
    }

    @Test
    public void nothingOnFirstInstallSameVersionOrDowngrade() {
        assertEquals("", WhatsNew.since(LOG, null, "1.6.9-beta"));
        assertEquals("", WhatsNew.since(LOG, "1.6.9-beta", "1.6.9-beta"));
        assertEquals("", WhatsNew.since(LOG, "1.7.0-beta", "1.6.9-beta"));
    }

    @Test
    public void latestStopsAtTheInstalledVersion() {
        String text = WhatsNew.latest(LOG, "1.6.9-beta", 2);
        assertTrue(text, text.startsWith("[1.6.9-beta]") && text.contains("[1.6.8-beta]"));
        assertFalse(text.contains("Bigger") || text.contains("Finder"));
        assertEquals("Removed\n• Sentry-lite", WhatsNew.latest(LOG, "1.6.9-beta", 1));
    }

    @Test
    public void theRealChangelogParses() throws Exception {
        File file = new File("../CHANGELOG.md");
        if (!file.isFile()) {
            file = new File("CHANGELOG.md");
        }
        String log = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        List<ReleaseNotes.Entry> v = WhatsNew.versions(log);
        assertTrue(v.size() > 10);
        assertFalse(WhatsNew.since(log, "1.6.7-beta", "1.6.9-beta").isEmpty());
    }
}
