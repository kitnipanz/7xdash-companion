package com.kooo.evcam.settings;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 「常用」档藏的 key 必须真有；每个分区在「常用」里至少还剩一项。 */
public class SettingsLevelTest {

    private static final Pattern KEY = Pattern.compile("android:key=\"([^\"]+)\"");

    @Test
    public void everyHiddenKeyExists() throws Exception {
        Set<String> keys = new HashSet<>();
        Matcher m = KEY.matcher(preferences());
        while (m.find()) {
            keys.add(m.group(1));
        }
        List<String> missing = new ArrayList<>();
        for (String key : SettingsLevel.ADVANCED) {
            if (!keys.contains(key)) {
                missing.add(key);
            }
        }
        assertTrue("SettingsLevel 登记了不存在的 key: " + missing, missing.isEmpty());
    }

    @Test
    public void everySectionKeepsSomethingInBasic() throws Exception {
        String xml = preferences();
        Matcher section = Pattern.compile(
                "<PreferenceScreen\\s+android:key=\"(screen_[a-z_]+)\"(.*?)</PreferenceScreen>",
                Pattern.DOTALL).matcher(xml);
        List<String> empty = new ArrayList<>();
        while (section.find()) {
            boolean kept = false;
            Matcher key = KEY.matcher(section.group(2));
            while (key.find()) {
                kept |= !SettingsLevel.isAdvanced(key.group(1)) && !key.group(1).startsWith("cat_");
            }
            if (!kept) {
                empty.add(section.group(1));
            }
        }
        assertTrue("这些分区在「常用」里一项都不剩: " + empty, empty.isEmpty());
    }

    private static String preferences() throws Exception {
        File dir = new File(System.getProperty("user.dir", "."));
        for (int i = 0; i < 4 && dir != null; i++) {
            for (File base : new File[]{dir, new File(dir, "app")}) {
                File file = new File(base, "src/main/res/xml/preferences.xml");
                if (file.isFile()) {
                    return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                }
            }
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("preferences.xml not found");
    }
}
