package com.kooo.evcam.settings.speaker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.media.AudioTrack;

import org.junit.Test;

public class SpeakerBenchTest {

    @Test
    public void sameIdWhileSoundingStopsAndAStaleCompletionIsIgnored() {
        SpeakerBench.BindingId id = SpeakerBench.BindingId.device(3);
        SpeakerBench.PlayState idle = SpeakerBench.PlayState.Idle.instance();
        SpeakerBench.PlayState sounding = SpeakerBench.reduce(idle, SpeakerBench.PlayEvent.play(id));

        assertTrue(sounding instanceof SpeakerBench.PlayState.Sounding);
        assertEquals(id, ((SpeakerBench.PlayState.Sounding) sounding).id);
        assertTrue(sounding.generation() > idle.generation());

        SpeakerBench.PlayState cut = SpeakerBench.reduce(sounding, SpeakerBench.PlayEvent.play(id));
        assertTrue(cut instanceof SpeakerBench.PlayState.Idle);
        assertTrue(cut.generation() > sounding.generation());

        SpeakerBench.PlayState late = SpeakerBench.reduce(
                cut, SpeakerBench.PlayEvent.finished(sounding.generation()));
        assertSame(cut, late);
    }

    @Test
    public void otherIdWhileSoundingReplacesAndTheOldCompletionIsIgnored() {
        SpeakerBench.BindingId id = SpeakerBench.BindingId.device(3);
        SpeakerBench.BindingId other = SpeakerBench.BindingId.zone(2, 1);
        SpeakerBench.PlayState sounding = SpeakerBench.reduce(
                SpeakerBench.PlayState.Idle.instance(), SpeakerBench.PlayEvent.play(id));
        SpeakerBench.PlayState replaced = SpeakerBench.reduce(
                sounding, SpeakerBench.PlayEvent.play(other));

        assertTrue(replaced instanceof SpeakerBench.PlayState.Sounding);
        assertEquals(other, ((SpeakerBench.PlayState.Sounding) replaced).id);
        assertTrue(replaced.generation() > sounding.generation());

        SpeakerBench.PlayState late = SpeakerBench.reduce(
                replaced, SpeakerBench.PlayEvent.finished(sounding.generation()));
        assertSame(replaced, late);

        SpeakerBench.PlayState done = SpeakerBench.reduce(
                replaced, SpeakerBench.PlayEvent.finished(replaced.generation()));
        assertTrue(done instanceof SpeakerBench.PlayState.Completed);
        assertEquals(other, ((SpeakerBench.PlayState.Completed) done).id);

        SpeakerBench.PlayState again = SpeakerBench.reduce(done, SpeakerBench.PlayEvent.play(other));
        assertTrue(again instanceof SpeakerBench.PlayState.Sounding);
        assertEquals(other, ((SpeakerBench.PlayState.Sounding) again).id);
    }

    @Test
    public void matchingFaultBecomesFailedAndAStaleFaultDoesNot() {
        SpeakerBench.BindingId id = SpeakerBench.BindingId.device(8);
        SpeakerBench.PlayState sounding = SpeakerBench.reduce(
                SpeakerBench.PlayState.Idle.instance(), SpeakerBench.PlayEvent.play(id));
        SpeakerBench.PlayState failed = SpeakerBench.reduce(sounding,
                SpeakerBench.PlayEvent.fault(sounding.generation(), SpeakerBench.Fail.WRITE, "7"));

        assertTrue(failed instanceof SpeakerBench.PlayState.Failed);
        assertEquals(id, ((SpeakerBench.PlayState.Failed) failed).id);
        assertEquals(SpeakerBench.Fail.WRITE, ((SpeakerBench.PlayState.Failed) failed).reason);
        assertEquals("7", ((SpeakerBench.PlayState.Failed) failed).detail);

        SpeakerBench.PlayState replay = SpeakerBench.reduce(failed, SpeakerBench.PlayEvent.play(id));
        SpeakerBench.PlayState stale = SpeakerBench.reduce(
                replay, SpeakerBench.PlayEvent.fault(sounding.generation(),
                        SpeakerBench.Fail.ROUTE_MISMATCH, "8 1"));
        assertSame(replay, stale);
    }

    @Test
    public void staticTrackWaitingForItsBufferCountsAsOpen() {
        assertTrue(SpeakerBench.opened(AudioTrack.STATE_INITIALIZED));
        assertTrue(SpeakerBench.opened(AudioTrack.STATE_NO_STATIC_DATA));
        assertFalse(SpeakerBench.opened(AudioTrack.STATE_UNINITIALIZED));
    }
}
