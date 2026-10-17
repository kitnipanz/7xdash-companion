package com.kooo.evcam.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;

/** 拷进 exports：文件名不变，半成品不留成正式文件。 */
public class UsbExportTest {

    @Test
    public void folderSitsAtUsbRoot() {
        assertEquals(new File("/storage/ABCD-1234/exports"),
                UsbExport.folder(new File("/storage/ABCD-1234")));
        assertEquals("exports", UsbExport.DIR_NAME);
        assertEquals(new File("/storage/ABCD-1234/moments"),
                UsbExport.moments(new File("/storage/ABCD-1234")));
        assertEquals("moments", UsbExport.MOMENTS_DIR);
    }

    @Test
    public void surroundVideoIsTheOnlyOneDefished() throws IOException {
        assertTrue(SurroundDefish.isSurroundVideo("20250101_120000_surround.mp4"));
        assertTrue(SurroundDefish.isSurroundVideo("20250101_120000_front.mp4"));
        assertFalse(SurroundDefish.isSurroundVideo("20250101_120000_cabinfront.mp4"));
        assertFalse(SurroundDefish.isSurroundVideo("20250101_120000_cabinrear.mp4"));
        assertFalse(SurroundDefish.isSurroundVideo("20250101_120000_surround.jpg"));
    }

    @Test
    public void unfinishedMp4IsNotPlayable() throws IOException {
        File tmp = Files.createTempDirectory("usb-export").toFile();
        try {
            File broken = write(new File(tmp, "20250101_120000_surround.mp4"),
                    "this is not a finished mp4");
            assertFalse(UsbExport.playable(broken));
            File photo = write(new File(tmp, "20250101_120000_cabinfront.jpg"), "jpeg-bytes");
            assertTrue(UsbExport.playable(photo));
        } finally {
            deleteTree(tmp);
        }
    }

    @Test
    public void copyKeepsTheTimestampedName() throws IOException {
        File tmp = Files.createTempDirectory("usb-export").toFile();
        try {
            File source = write(new File(tmp, "20250101_120000_surround.mp4"), "frame-data");
            File destDir = new File(tmp, "exports");

            File out = UsbExport.copy(source, destDir);

            assertEquals(new File(destDir, "20250101_120000_surround.mp4"), out);
            assertEquals("frame-data", read(out));
            assertFalse(new File(destDir, "20250101_120000_surround.mp4.part").exists());
        } finally {
            deleteTree(tmp);
        }
    }

    @Test
    public void copyKeepsNameAndBytes() throws IOException {
        File tmp = Files.createTempDirectory("usb-export").toFile();
        try {
            File source = write(new File(tmp, "clip.mp4"), "frame-data");
            File destDir = new File(tmp, "exports");

            File out = UsbExport.copy(source, destDir);

            assertEquals(new File(destDir, "clip.mp4"), out);
            assertEquals("frame-data", read(out));
            assertFalse(new File(destDir, "clip.mp4.part").exists());
        } finally {
            deleteTree(tmp);
        }
    }

    @Test
    public void copyReplacesAnOlderFileOfTheSameName() throws IOException {
        File tmp = Files.createTempDirectory("usb-export").toFile();
        try {
            File destDir = new File(tmp, "exports");
            assertTrue(destDir.mkdirs());
            write(new File(destDir, "clip.mp4"), "old");
            File source = write(new File(tmp, "src.mp4"), "new-bytes");
            source.renameTo(new File(tmp, "clip.mp4"));
            source = new File(tmp, "clip.mp4");

            File out = UsbExport.copy(source, destDir);

            assertEquals("new-bytes", read(out));
            assertFalse(new File(destDir, "clip.mp4.part").exists());
        } finally {
            deleteTree(tmp);
        }
    }

    @Test
    public void alreadyInExportsIsLeftAlone() throws IOException {
        File tmp = Files.createTempDirectory("usb-export").toFile();
        try {
            File destDir = new File(tmp, "exports");
            assertTrue(destDir.mkdirs());
            File source = write(new File(destDir, "clip.mp4"), "same");

            File out = UsbExport.copy(source, destDir);

            assertEquals(source.getCanonicalPath(), out.getCanonicalPath());
            assertEquals("same", read(out));
            assertFalse(new File(destDir, "clip.mp4.part").exists());
        } finally {
            deleteTree(tmp);
        }
    }

    @Test
    public void failedReplaceRemovesThePartial() throws IOException {
        File tmp = Files.createTempDirectory("usb-export").toFile();
        try {
            File destDir = new File(tmp, "exports");
            assertTrue(destDir.mkdirs());
            File blocker = new File(destDir, "clip.mp4");
            assertTrue(blocker.mkdirs());
            assertTrue(new File(blocker, "kept").createNewFile());
            File source = write(new File(tmp, "clip.mp4"), "incoming");

            try {
                UsbExport.copy(source, destDir);
                fail("replacing a non-empty directory should fail");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("replace"));
            }
            assertFalse(new File(destDir, "clip.mp4.part").exists());
            assertTrue(new File(blocker, "kept").exists());
        } finally {
            deleteTree(tmp);
        }
    }

    @Test
    public void emptyOrMissingSourceIsRefused() throws IOException {
        File tmp = Files.createTempDirectory("usb-export").toFile();
        try {
            File destDir = new File(tmp, "exports");
            try {
                UsbExport.copy(new File(tmp, "missing.mp4"), destDir);
                fail("missing source");
            } catch (IOException expected) {
                assertEquals("empty", expected.getMessage());
            }
            File empty = new File(tmp, "empty.mp4");
            assertTrue(empty.createNewFile());
            try {
                UsbExport.copy(empty, destDir);
                fail("empty source");
            } catch (IOException expected) {
                assertEquals("empty", expected.getMessage());
            }
            assertFalse(destDir.exists());
        } finally {
            deleteTree(tmp);
        }
    }

    private static File write(File file, String text) throws IOException {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(text.getBytes("UTF-8"));
        } finally {
            out.close();
        }
        return file;
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), "UTF-8");
    }

    private static void deleteTree(File file) {
        File[] kids = file.listFiles();
        if (kids != null) {
            for (File kid : kids) {
                deleteTree(kid);
            }
        }
        file.delete();
    }
}
