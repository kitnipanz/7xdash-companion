package com.kooo.evcam.announce;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NoticeVoiceTest {

    @Test
    public void headrestNamesGoFirst() {
        assertTrue(NoticeVoice.soundsLikeHeadrest("BUS7_driver_headrest"));
        assertTrue(NoticeVoice.soundsLikeHeadrest("BUS5_DRIVER_NOTIFY"));
        assertFalse(NoticeVoice.soundsLikeHeadrest("BUS0_MEDIA"));
        assertFalse(NoticeVoice.soundsLikeHeadrest("BUS12_OUTER_NOTIFY"));
    }
}
