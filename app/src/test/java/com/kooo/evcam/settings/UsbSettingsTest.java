package com.kooo.evcam.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

public class UsbSettingsTest {

    @Test
    public void everyTypeRoundTripsThroughTheCopy() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("beta_features", true);
        config.put("speak_volume", 80);
        config.put("saved_at", 1_766_000_000_000L);
        config.put("dim_opacity", 0.35f);
        config.put("button_shortcuts", "135,0,quit_app,tap,\n136,0,announce_play:sound-siren,long,");
        config.put("tricky", "tab\there back\\slash");
        config.put("lights", new HashSet<>(Arrays.asList("AA:BB", "CC:DD")));
        config.put("empty_set", new HashSet<String>());
        Map<String, Map<String, ?>> all = new LinkedHashMap<>();
        all.put("app_config", config);
        all.put("cabin_eq", new LinkedHashMap<>(Map.of("enabled", false)));

        Map<String, Map<String, Object>> back = UsbSettings.decode(UsbSettings.encode(all));

        assertEquals(config, back.get("app_config"));
        assertEquals(false, back.get("cabin_eq").get("enabled"));
    }

    @Test
    public void anythingElseReadsAsNoSettings() {
        assertTrue(UsbSettings.decode("not a settings copy\napp_config\tx\tb\ttrue\n").isEmpty());
        // a damaged line is skipped, the rest is kept
        Map<String, Map<String, Object>> back = UsbSettings.decode(
                "7xdash-settings 1\napp_config\tvol\ti\tloud\napp_config\tok\tb\ttrue\n");
        assertEquals(1, back.get("app_config").size());
    }
}
