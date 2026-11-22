package com.kooo.evcam.telemetry;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class EcarxLoaderTest {

    @Test
    public void classpathKeepsOnlyEcarxJars() {
        String boot = "/system/framework/framework.jar:/system/framework/ecarx.adaptapi.impl.jar";
        String system = "/system/framework/services.jar:/system/framework/zeekrlife.adaptapi.jar";
        assertEquals(Arrays.asList(
                        "/system/framework/ecarx.adaptapi.impl.jar",
                        "/system/framework/zeekrlife.adaptapi.jar"),
                EcarxLoader.candidatePaths(boot, system, Collections.emptyList()));
        assertFalse(EcarxLoader.isEcarxJar("/system/framework/framework.jar"));
        assertTrue(EcarxLoader.isEcarxJar("ecarx.adaptapi.impl.jar"));
    }

    @Test
    public void frameworkDirectoryAddsAMatchingJar() throws Exception {
        File dir = Files.createTempDirectory("ecarx-fw").toFile();
        assertTrue(new File(dir, "framework.jar").createNewFile());
        assertTrue(new File(dir, "ecarx.adaptapi.impl.jar").createNewFile());
        assertEquals(Collections.singletonList(new File(dir, "ecarx.adaptapi.impl.jar").getAbsolutePath()),
                EcarxLoader.jarsIn(dir));
    }

    @Test
    public void dexIndexOrdersClassesThenNumbered() {
        assertTrue(EcarxLoader.dexIndex("classes.dex") < EcarxLoader.dexIndex("classes2.dex"));
        assertTrue(EcarxLoader.dexIndex("classes2.dex") < EcarxLoader.dexIndex("classes10.dex"));
    }

    @Test
    public void jarYieldsDexEntriesInOrder() throws Exception {
        File jar = File.createTempFile("ecarx", ".jar");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(jar))) {
            put(zip, "classes2.dex", "second");
            put(zip, "classes.dex", "first");
            put(zip, "not-a-dex.txt", "nope");
        }
        byte[][] dex = EcarxLoader.dexBytes(jar);
        assertEquals(2, dex.length);
        assertArrayEquals("first".getBytes(StandardCharsets.US_ASCII), dex[0]);
        assertArrayEquals("second".getBytes(StandardCharsets.US_ASCII), dex[1]);
    }

    @Test
    public void rawDexFileIsTakenWhole() throws Exception {
        File dex = File.createTempFile("ecarx", ".dex");
        byte[] body = "dex\n035-body".getBytes(StandardCharsets.US_ASCII);
        try (FileOutputStream out = new FileOutputStream(dex)) {
            out.write(body);
        }
        assertArrayEquals(body, EcarxLoader.dexBytes(dex)[0]);
    }

    private static void put(ZipOutputStream zip, String name, String text) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.US_ASCII));
        zip.closeEntry();
    }
}
