package com.kooo.evcam.analytics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class CrashFramesTest {

    private static StackTraceElement at(String cls, String method, int line) {
        return new StackTraceElement(cls, method, "X.java", line);
    }

    @Test
    public void onlyAppFramesOfTheDeepestCauseAndNeverTheMessage() {
        RuntimeException cause = new IllegalStateException("plate ABC-1234 at /sdcard/secret.mp4");
        cause.setStackTrace(new StackTraceElement[]{
                at("android.os.Handler", "dispatch", 10),
                at("com.kooo.evcam.input.ShortcutPerformer", "perform", 118),
                at("java.lang.Thread", "run", 1),
                at("com.kooo.evcam.input.ShortcutKeys", "dispatch", 45),
        });
        RuntimeException top = new RuntimeException("wrapper", cause);
        top.setStackTrace(new StackTraceElement[]{at("com.kooo.evcam.Other", "x", 1)});

        List<String> frames = AnalyticsPayload.crashFrames(top);

        assertEquals(1, frames.size());
        assertEquals("ShortcutPerformer.perform:118 < ShortcutKeys.dispatch:45", frames.get(0));
        assertFalse(String.join("", frames).contains("ABC"));
    }

    @Test
    public void withNoAppFramesTheTopThreeOfAnyCode() {
        RuntimeException t = new RuntimeException();
        t.setStackTrace(new StackTraceElement[]{
                at("a.A", "one", 1), at("b.B", "two", 2), at("c.C", "three", 3), at("d.D", "four", 4),
        });
        assertEquals("A.one:1 < B.two:2 < C.three:3", AnalyticsPayload.crashFrames(t).get(0));
    }

    @Test
    public void longStacksSplitIntoAtMostFiveValuesOfAHundred() {
        StackTraceElement[] stack = new StackTraceElement[60];
        for (int i = 0; i < stack.length; i++) {
            stack[i] = at("com.kooo.evcam.recording.RecordingCoordinator", "method" + i, 1000 + i);
        }
        RuntimeException t = new RuntimeException();
        t.setStackTrace(stack);
        List<String> frames = AnalyticsPayload.crashFrames(t);
        assertEquals(AnalyticsPayload.MAX_FRAME_PARAMS, frames.size());
        for (String f : frames) {
            assertTrue(f, f.length() <= AnalyticsPayload.MAX_VALUE);
        }
        assertTrue(frames.get(0).startsWith("RecordingCoordinator.method0:1000 < "));
    }

    @Test
    public void nothingForNoThrowable() {
        assertTrue(AnalyticsPayload.crashFrames(null).isEmpty());
    }
}
