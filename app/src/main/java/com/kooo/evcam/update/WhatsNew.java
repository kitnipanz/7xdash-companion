package com.kooo.evcam.update;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "What's new" after an update, like Tesla's release notes: the CHANGELOG sections of every
 * version newer than the one the driver last saw, up to the one now installed, as plain text.
 *
 * <p>The CHANGELOG ships inside the APK ({@code assets/CHANGELOG.md}, copied at build time), so it
 * works without a connection. Pure Java, see {@code WhatsNewTest}; {@link WhatsNewDialog} shows it.</p>
 */
public final class WhatsNew {

    private static final Pattern VERSION_HEADING = Pattern.compile("^## \\[([^\\]]+)]");

    private WhatsNew() {
    }

    /** CHANGELOG split into versions, in file order (newest first). */
    static List<ReleaseNotes.Entry> versions(String changelog) {
        List<ReleaseNotes.Entry> out = new ArrayList<>();
        if (changelog == null) {
            return out;
        }
        String version = null;
        StringBuilder body = new StringBuilder();
        for (String line : changelog.replace("\r\n", "\n").split("\n", -1)) {
            Matcher m = VERSION_HEADING.matcher(line);
            if (m.find()) {
                if (version != null) {
                    out.add(entry(version, body));
                }
                version = m.group(1).trim();
                body.setLength(0);
                continue;
            }
            if (version != null) {
                body.append(line).append('\n');
            }
        }
        if (version != null) {
            out.add(entry(version, body));
        }
        return out;
    }

    /** {@link ReleaseNotes#summarise} drops the first heading as the title: give it the version as one. */
    private static ReleaseNotes.Entry entry(String version, StringBuilder body) {
        return new ReleaseNotes.Entry(version, "# " + version + "\n" + body);
    }

    /**
     * The notes to show now that {@code current} is installed and {@code lastSeen} was the version
     * last opened. Empty when there is nothing to show: a first install (no last version), the same
     * version, or a downgrade.
     */
    public static String since(String changelog, String lastSeen, String current) {
        if (lastSeen == null || lastSeen.isEmpty() || current == null
                || !VersionName.isNewer(current, lastSeen)) {
            return "";
        }
        List<ReleaseNotes.Entry> newer = new ArrayList<>();
        for (ReleaseNotes.Entry e : versions(changelog)) {
            if (VersionName.isNewer(e.tagName, lastSeen) && !VersionName.isNewer(e.tagName, current)) {
                newer.add(e);
            }
        }
        return ReleaseNotes.combine(newer);
    }

    /** The latest {@code count} versions up to {@code current}, for Settings → What's new. */
    public static String latest(String changelog, String current, int count) {
        List<ReleaseNotes.Entry> shown = new ArrayList<>();
        for (ReleaseNotes.Entry e : versions(changelog)) {
            if (current != null && VersionName.isNewer(e.tagName, current)) {
                continue;
            }
            shown.add(e);
            if (shown.size() >= count) {
                break;
            }
        }
        if (shown.size() == 1) {
            // combine() leaves the version out for a single one: the dialog title has it
            return ReleaseNotes.summarise(shown.get(0).body);
        }
        return ReleaseNotes.combine(shown);
    }
}
