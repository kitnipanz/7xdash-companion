package com.kooo.evcam.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

public class OwnFilesTest {

    @Test
    public void listsAChildAndRejectsAPathOutside() throws Exception {
        File root = Files.createTempDirectory("own-files").toFile();
        File folder = new File(root, "files");
        assertTrue(folder.mkdir());
        File note = new File(folder, "note.txt");
        assertTrue(note.createNewFile());
        List<File> roots = Collections.singletonList(root);

        OwnFiles.Listing listing = OwnFiles.list(folder, roots);
        assertTrue(listing.allowed);
        assertTrue(listing.readable);
        assertEquals(1, listing.entries.size());
        assertEquals("note.txt", listing.entries.get(0).name);
        assertTrue(listing.entries.get(0).inside);

        File outside = Files.createTempDirectory("own-files-out").toFile();
        OwnFiles.Listing blocked = OwnFiles.list(outside, roots);
        assertFalse(blocked.allowed);
        assertTrue(blocked.entries.isEmpty());
    }

    @Test
    public void aSymlinkOutsideTheRootIsNotInside() throws Exception {
        File root = Files.createTempDirectory("own-files-link").toFile();
        File outside = Files.createTempDirectory("own-files-target").toFile();
        File secret = new File(outside, "secret.txt");
        assertTrue(secret.createNewFile());
        File link = new File(root, "escape");
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath());
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return;
        }
        assertFalse(OwnFiles.inside(link, Collections.singletonList(root)));
        OwnFiles.Listing listing = OwnFiles.list(root, Collections.singletonList(root));
        assertEquals(1, listing.entries.size());
        assertFalse(listing.entries.get(0).inside);
    }

    @Test
    public void modeShowsTypeAndNineBits() {
        assertEquals("-rw-r--r--", OwnFiles.mode(0100644));
        assertEquals("drwxr-xr-x", OwnFiles.mode(0040755));
        assertEquals("lrwxrwxrwx", OwnFiles.mode(0120777));
    }
}
