package com.kooo.evcam.obd;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ElmReplyTest {

    @Test
    public void readsAFrameWithOrWithoutSpaces() {
        assertArrayEquals(new byte[]{0x41, 0x0D, 0x2A}, ElmReply.parse("41 0D 2A\r\r").frames.get(0));
        assertArrayEquals(new byte[]{0x41, 0x0D, 0x2A}, ElmReply.parse("410D2A\r\r").frames.get(0));
    }

    @Test
    public void skipsTheSearchingNotice() {
        ElmReply reply = ElmReply.parse("SEARCHING...\r41 0D 2A\r");
        assertEquals(ElmReply.Kind.DATA, reply.kind);
        assertEquals(1, reply.frames.size());
    }

    @Test
    public void eachEcuIsItsOwnFrame() {
        assertEquals(2, ElmReply.parse("41 00 80 00 00 00\r41 00 40 00 00 00\r").frames.size());
    }

    @Test
    public void tellsTheFailuresApart() {
        assertEquals(ElmReply.Kind.NO_DATA, ElmReply.parse("NO DATA\r").kind);
        assertEquals(ElmReply.Kind.NO_BUS, ElmReply.parse("SEARCHING...\rUNABLE TO CONNECT\r").kind);
        assertEquals(ElmReply.Kind.NO_BUS, ElmReply.parse("BUS INIT: ...ERROR\r").kind);
        assertEquals(ElmReply.Kind.NO_BUS, ElmReply.parse("CAN ERROR\r").kind);
        assertEquals(ElmReply.Kind.ERROR, ElmReply.parse("?\r").kind);
        assertEquals(ElmReply.Kind.ERROR, ElmReply.parse("BUFFER FULL\r").kind);
        assertEquals(ElmReply.Kind.ERROR, ElmReply.parse("").kind);
    }

    @Test
    public void joinsALongCanAnswerAndCutsThePadding() {
        ElmReply reply = ElmReply.parse("00A\r0: 43 04 01 23 04 56\r1: 07 89 00 00 AA AA AA\r");
        assertEquals(1, reply.frames.size());
        assertArrayEquals(new byte[]{0x43, 4, 1, 0x23, 4, 0x56, 7, (byte) 0x89, 0, 0},
                reply.frames.get(0));
    }

    @Test
    public void aBadLineDoesNotLoseTheGoodOne() {
        ElmReply reply = ElmReply.parse("41 0D 2A\r41 0D <RX ERROR\r");
        assertEquals(ElmReply.Kind.DATA, reply.kind);
        assertEquals(1, reply.frames.size());
    }
}
