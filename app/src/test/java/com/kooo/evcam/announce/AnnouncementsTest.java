package com.kooo.evcam.announce;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AnnouncementsTest {

    @Test
    public void savedListRoundTripsTabsNewlinesAndBackslashes() {
        List<Announcement> list = Arrays.asList(
                Announcement.phrase("a", "Step\tback\\now\nplease"),
                Announcement.sound("b", Tones.SIREN, "Siren"),
                Announcement.phrase("c", "กรุณาถอยห่างจากรถ"));
        List<Announcement> back = AnnouncementStore.decode(AnnouncementStore.encode(list));
        assertEquals(3, back.size());
        assertEquals("Step\tback\\now\nplease", back.get(0).text);
        assertEquals(Tones.SIREN, back.get(1).sound);
        assertTrue(back.get(1).isSound());
        assertEquals("กรุณาถอยห่างจากรถ", back.get(2).text);
    }

    @Test
    public void brokenLinesAreSkipped() {
        assertEquals(1, AnnouncementStore.decode("only-two\tparts\n\tno\tid\nok\t\tHello\n").size());
    }

    @Test
    public void phrasesAreTrimmedAndCapped() {
        assertEquals("Hello there", AnnouncementStore.clean("  Hello \n  there "));
        assertEquals("", AnnouncementStore.clean(null));
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            longText.append('x');
        }
        assertEquals(AnnouncementStore.MAX_TEXT, AnnouncementStore.clean(longText.toString()).length());
    }

    @Test
    public void defaultsHaveSoundsEnglishThaiAndUniqueIds() {
        List<Announcement> list = AnnouncementStore.defaults();
        Set<String> ids = new HashSet<>();
        boolean thai = false;
        boolean english = false;
        int sounds = 0;
        for (Announcement a : list) {
            assertTrue(ids.add(a.id));
            if (a.isSound()) {
                sounds++;
                assertNotNull(Tones.render(a.sound));
            } else if (a.isThai()) {
                thai = true;
            } else {
                english = true;
            }
        }
        assertEquals(3, sounds);
        assertTrue(thai);
        assertTrue(english);
    }

    @Test
    public void tonesStayBelowFullScaleAndUnknownIsNull() {
        for (String key : new String[] {Tones.CHIME, Tones.BEEP, Tones.SIREN}) {
            short[] pcm = Tones.render(key);
            assertTrue(pcm.length > Tones.RATE / 4);
            int peak = 0;
            for (short s : pcm) {
                peak = Math.max(peak, Math.abs(s));
            }
            assertTrue(key, peak > 10_000 && peak < 32_767);
            // ramps in: no click on the first sample
            assertTrue(Math.abs(pcm[0]) < 100);
        }
        assertNull(Tones.render("horn"));
    }

    @Test
    public void wavReadsMonoAndDownmixesStereo() {
        byte[] stereo = wav(2, 22_050, new short[] {1000, 3000, -2000, -4000});
        Wav w = Wav.parse(stereo);
        assertNotNull(w);
        assertEquals(22_050, w.rate);
        assertEquals(2, w.mono.length);
        assertEquals(2000, w.mono[0]);
        assertEquals(-3000, w.mono[1]);
        assertNull(Wav.parse(new byte[] {1, 2, 3}));
        assertNull(Wav.parse(wav(1, 16_000, new short[0])));
    }

    @Test
    public void sentryPlaysOnArmingOnlyOutsideTheCooldown() {
        long cool = SentryAnnouncer.COOLDOWN_MS;
        assertTrue(SentryAnnouncer.shouldPlay(1, 2, cool));
        assertTrue(SentryAnnouncer.shouldPlay(0, 2, cool));
        assertTrue(SentryAnnouncer.shouldPlay(null, 2, cool));
        assertFalse(SentryAnnouncer.shouldPlay(2, 2, cool));
        assertFalse(SentryAnnouncer.shouldPlay(0, 1, cool));
        assertFalse(SentryAnnouncer.shouldPlay(1, null, cool));
        assertFalse(SentryAnnouncer.shouldPlay(1, 2, cool - 1));
    }

    @Test
    public void thaiIsSpottedAnywhereInThePhrase() {
        assertTrue(Announcement.phrase("x", "Hello ขอบคุณ").isThai());
        assertFalse(Announcement.phrase("x", "Thank you").isThai());
    }

    /** 16-bit PCM WAV with an extra chunk before data, as some engines write. */
    private static byte[] wav(int channels, int rate, short[] samples) {
        int data = samples.length * 2;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(12 + 24 + 10 + 8 + data)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(b.capacity() - 8).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) channels)
                .putInt(rate).putInt(rate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16);
        b.put("LIST".getBytes()).putInt(2).put(new byte[] {0, 0});
        b.put("data".getBytes()).putInt(data);
        for (short s : samples) {
            b.putShort(s);
        }
        return b.array();
    }
}
