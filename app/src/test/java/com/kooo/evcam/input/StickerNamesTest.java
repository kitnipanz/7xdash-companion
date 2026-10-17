package com.kooo.evcam.input;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StickerNamesTest {

    @Test
    public void nameIsTrimmedToOneShortLine() {
        assertEquals("Steering left", StickerNames.clean("  Steering left \n"));
        assertEquals("a b", StickerNames.clean("a\nb"));
        assertEquals("", StickerNames.clean(null));
        assertEquals("", StickerNames.clean("   "));
        String longName = "123456789012345678901234567890";
        assertEquals(StickerNames.MAX_LENGTH, StickerNames.clean(longName).length());
    }
}
