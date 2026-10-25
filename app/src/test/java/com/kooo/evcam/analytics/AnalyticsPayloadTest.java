package com.kooo.evcam.analytics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AnalyticsPayloadTest {

    @Test
    public void namesAreCleanedForGa4() {
        assertEquals("page_open", AnalyticsPayload.name("page_open"));
        assertEquals("settings_screen_drive", AnalyticsPayload.name("settings:screen_drive"));
        assertEquals("home_card", AnalyticsPayload.name("Home Card"));
        assertEquals("abc", AnalyticsPayload.name("12_abc"));
        assertEquals("unknown", AnalyticsPayload.name("123"));
        assertEquals("unknown", AnalyticsPayload.name(null));
        String longName = AnalyticsPayload.name("a123456789012345678901234567890123456789012345");
        assertEquals(AnalyticsPayload.MAX_NAME, longName.length());
    }

    @Test
    public void valuesAreCut() {
        assertEquals("", AnalyticsPayload.value(null, 10));
        assertEquals("abc", AnalyticsPayload.value("abc", 10));
        assertEquals("abcde", AnalyticsPayload.value("abcdefgh", 5));
    }

    @Test
    public void minutesAreBucketed() {
        assertEquals("0", AnalyticsPayload.minutesBucket(-5));
        assertEquals("0", AnalyticsPayload.minutesBucket(59_000));
        assertEquals("1", AnalyticsPayload.minutesBucket(60_000));
        assertEquals("1", AnalyticsPayload.minutesBucket(4 * 60_000));
        assertEquals("5", AnalyticsPayload.minutesBucket(5 * 60_000));
        assertEquals("30", AnalyticsPayload.minutesBucket(59 * 60_000));
        assertEquals("120", AnalyticsPayload.minutesBucket(10 * 3_600_000L));
    }

    @Test
    public void bodyHasClientSessionAndEngagement() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("page", "settings:screen_drive");
        Map<String, String> user = new LinkedHashMap<>();
        user.put("app_version", "1.3.12-beta");
        String json = AnalyticsPayload.build("install-1", 1700000000L, user,
                Collections.singletonList(new AnalyticsPayload.Event("page_open", params, 1_000L)), false);

        JsonObject body = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("install-1", body.get("client_id").getAsString());
        assertEquals("1.3.12-beta", body.getAsJsonObject("user_properties")
                .getAsJsonObject("app_version").get("value").getAsString());
        JsonObject event = body.getAsJsonArray("events").get(0).getAsJsonObject();
        assertEquals("page_open", event.get("name").getAsString());
        assertEquals(1_000_000L, event.get("timestamp_micros").getAsLong());
        JsonObject p = event.getAsJsonObject("params");
        assertEquals("settings:screen_drive", p.get("page").getAsString());
        assertEquals("1700000000", p.get("session_id").getAsString());
        assertEquals(AnalyticsPayload.ENGAGEMENT_MS, p.get("engagement_time_msec").getAsInt());
        assertFalse(p.has("debug_mode"));
    }

    @Test
    public void debugBuildsMarkEvents() {
        String json = AnalyticsPayload.build("id", 1L, null,
                Collections.singletonList(new AnalyticsPayload.Event("app_open", null, 0L)), true);
        JsonObject body = JsonParser.parseString(json).getAsJsonObject();
        assertFalse(body.has("user_properties"));
        JsonObject p = body.getAsJsonArray("events").get(0).getAsJsonObject().getAsJsonObject("params");
        assertTrue(p.has("debug_mode"));
    }

    @Test
    public void atMost25EventsPerRequest() {
        List<AnalyticsPayload.Event> events = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            events.add(new AnalyticsPayload.Event("shortcut", null, i));
        }
        String json = AnalyticsPayload.build("id", 1L, null, events, false);
        assertEquals(AnalyticsPayload.MAX_EVENTS,
                JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("events").size());
    }
}
