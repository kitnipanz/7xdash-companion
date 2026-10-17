package com.kooo.evcam.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class StickerFrameTest {

    @Test
    public void shortPressIsATap() {
        StickerFrame frame = StickerFrame.parse(new byte[]{0x02, 0x01, 0, 0, 0, 0, 0});
        assertEquals(StickerFrame.CONTROL_BUTTON, frame.control);
        assertEquals(PressKind.TAP, frame.press);
    }

    @Test
    public void longAndDoubleStayOnTheButton() {
        assertEquals(PressKind.LONG, StickerFrame.parse(new byte[]{0x02, 0x41}).press);
        assertEquals(PressKind.DOUBLE, StickerFrame.parse(new byte[]{0x02, (byte) 0x81}).press);
    }

    @Test
    public void knobDirectionsAreSeparateControls() {
        StickerFrame right = StickerFrame.parse(new byte[]{0x02, 0x02});
        StickerFrame left = StickerFrame.parse(new byte[]{0x02, 0x03});
        assertEquals(StickerFrame.CONTROL_KNOB_RIGHT, right.control);
        assertEquals(StickerFrame.CONTROL_KNOB_LEFT, left.control);
        assertEquals(PressKind.TAP, right.press);
    }

    @Test
    public void zeekrButtonOneClickAndDoubleClick() {
        StickerFrame tap = StickerFrame.parse(new byte[]{0x31});
        StickerFrame twice = StickerFrame.parse(new byte[]{0x32});
        assertEquals(StickerFrame.CONTROL_BUTTON, tap.control);
        assertEquals(PressKind.TAP, tap.press);
        assertEquals(StickerFrame.CONTROL_BUTTON, twice.control);
        assertEquals(PressKind.DOUBLE, twice.press);
    }

    @Test
    public void otherPacketsAreNotPresses() {
        assertNull(StickerFrame.parse(null));
        assertNull(StickerFrame.parse(new byte[]{0x02}));
        assertNull(StickerFrame.parse(new byte[]{0x01, 0x01}));
        assertNull(StickerFrame.parse(new byte[]{0x02, 0x00}));
        assertNull(StickerFrame.parse(new byte[]{0x02, (byte) 0xF1}));
    }

    @Test
    public void deviceNameMatchesTheShortcutBook() {
        String name = StickerFrame.deviceName("aa:bb:cc:dd:ee:ff");
        assertEquals("sticker:AA:BB:CC:DD:EE:FF", name);
        assertEquals("AA:BB:CC:DD:EE:FF", StickerFrame.address(name));
        assertEquals("EE:FF", StickerFrame.tail(name));
        StickerFrame frame = StickerFrame.parse(new byte[]{0x02, 0x41});
        Shortcut saved = new Shortcut(frame.control, 0, name, "toggle_dim", frame.press.key);
        Shortcut hit = ShortcutBook.match(Collections.singletonList(saved),
                frame.control, 0, StickerFrame.deviceName("AA:BB:CC:DD:EE:FF"), PressKind.LONG);
        assertEquals("toggle_dim", hit.action);
        assertNull(ShortcutBook.match(Collections.singletonList(saved),
                frame.control, 0, name, PressKind.TAP));
    }

    @Test
    public void hexAndNameHints() {
        assertEquals("02 81", StickerFrame.hex(new byte[]{0x02, (byte) 0x81}));
        assertTrue(StickerMatch.nameHint("LingDong-2"));
        assertTrue(StickerMatch.nameHint("CSB10"));
        assertTrue(StickerMatch.nameHint("灵动贴"));
        assertTrue(StickerMatch.nameHint("ZEEKR"));
        assertFalse(StickerMatch.nameHint("Pixel"));
        assertEquals(StickerMatch.RANK_SERVICE, StickerMatch.rank("Pixel", true));
        assertEquals(StickerMatch.RANK_NAME, StickerMatch.rank("smart button", false));
        assertTrue(StickerMatch.isService(StickerMatch.SERVICE));
        assertTrue(StickerMatch.isNotify(StickerMatch.NOTIFY));
    }

    @Test
    public void savedAddressesStayUnique() {
        assertEquals("CC:DD\nAA:BB", StickerDevices.write(StickerDevices.put(
                Arrays.asList("aa:bb", "CC:DD"), "AA:BB")));
        assertEquals(Collections.singletonList("CC:DD"),
                StickerDevices.remove(StickerDevices.parse("AA:BB\nCC:DD"), "aa:bb"));
    }
}
