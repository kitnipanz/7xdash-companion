package com.kooo.evcam.recording;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class InstantClipsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void listIsNewestFirstAndSkipsOtherFiles() throws IOException {
        File dir = tmp.newFolder("instant captures");
        File older = new File(dir, "20261010_120000_surround.mp4");
        File newer = new File(dir, "20261010_120020_surround.mp4");
        assertTrue(older.createNewFile());
        assertTrue(newer.createNewFile());
        assertTrue(new File(dir, "notes.txt").createNewFile());
        assertTrue(new File(dir, "nested").mkdir());

        List<File> clips = InstantClips.list(dir);

        assertEquals(2, clips.size());
        assertEquals(newer.getName(), clips.get(0).getName());
        assertEquals(older.getName(), clips.get(1).getName());
    }

    @Test
    public void deleteRefusesAFileOutsideTheFolder() throws IOException {
        File dir = tmp.newFolder("instant captures");
        File outside = tmp.newFile("20261010_120000_surround.mp4");

        assertFalse(InstantClips.delete(dir, outside));
        assertTrue(outside.isFile());
    }

    @Test
    public void deleteAllRemovesOnlyClips() throws IOException {
        File dir = tmp.newFolder("instant captures");
        assertTrue(new File(dir, "20261010_120000_surround.mp4").createNewFile());
        assertTrue(new File(dir, "20261010_120020_surround_2.mp4").createNewFile());
        File note = new File(dir, "keep.txt");
        assertTrue(note.createNewFile());

        assertEquals(2, InstantClips.deleteAll(dir));
        assertFalse(new File(dir, "20261010_120000_surround.mp4").exists());
        assertTrue(note.isFile());
    }
}
