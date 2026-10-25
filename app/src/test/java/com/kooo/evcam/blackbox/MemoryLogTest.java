package com.kooo.evcam.blackbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** What a leak, a steady run and a restart look like in the memory log. */
public class MemoryLogTest {

    private static final long MIN = 60_000L;
    private static final long HOUR = 60 * MIN;

    private static MemoryLog.Sample sample(long pid, long up, long javaKb, long threads) {
        MemoryLog.Sample s = new MemoryLog.Sample();
        s.time = 1_700_000_000_000L + up;
        s.pid = pid;
        s.up = up;
        s.java = javaKb;
        s.javaMax = 512 * 1024;
        s.nativeKb = 50 * 1024;
        s.pss = 300 * 1024;
        s.gfx = 40 * 1024;
        s.rss = 400 * 1024;
        s.threads = threads;
        s.fds = 200;
        return s;
    }

    /** A run of {@code hours} with one line every five minutes; java grows {@code javaPerHourKb}. */
    private static List<String> run(long pid, double hours, long javaPerHourKb) {
        List<String> lines = new ArrayList<>();
        for (long up = MIN; up <= hours * HOUR; up += 5 * MIN) {
            long java = 80 * 1024 + javaPerHourKb * up / HOUR;
            lines.add(MemoryLog.format(sample(pid, up, java, 90)));
        }
        return lines;
    }

    @Test
    public void aLineReadsBackTheSame() {
        MemoryLog.Sample s = sample(42, 5 * MIN, 1234, 77);
        s.event = "trim:15";
        MemoryLog.Sample back = MemoryLog.parse(MemoryLog.format(s));
        assertEquals(42, back.pid);
        assertEquals(5 * MIN, back.up);
        assertEquals(1234, back.java);
        assertEquals(77, back.threads);
        assertEquals("trim:15", back.event);
        assertNull(MemoryLog.parse("garbage"));
        assertNull(MemoryLog.parse(null));
    }

    @Test
    public void rateIsPerHourPastWarmUp() {
        List<MemoryLog.Sample> process = new ArrayList<>();
        for (String line : run(1, 6, 10 * 1024)) {
            process.add(MemoryLog.parse(line));
        }
        assertEquals(10 * 1024, MemoryLog.perHour(process, "java"), 1.0);
        assertEquals(0, MemoryLog.perHour(process, "threads"), 0.001);
    }

    @Test
    public void aShortRunHasNoRate() {
        List<MemoryLog.Sample> process = new ArrayList<>();
        for (String line : run(1, 2, 10 * 1024)) {
            process.add(MemoryLog.parse(line));
        }
        assertTrue(Double.isNaN(MemoryLog.perHour(process, "java")));
    }

    @Test
    public void aLeakSaysGrowing() {
        String text = MemoryLog.summarize(run(7, 8, 20 * 1024), 7);
        assertTrue(text, text.contains("pid 7 (this run)"));
        assertTrue(text, text.contains("verdict: GROWING java"));
    }

    @Test
    public void aFlatRunSaysSteady() {
        String text = MemoryLog.summarize(run(7, 8, 0), 7);
        assertTrue(text, text.contains("verdict: steady"));
        assertFalse(text, text.contains("GROWING"));
    }

    /** Warm-up growth (the first half hour) is not a leak. */
    @Test
    public void warmUpIsLeftOut() {
        List<String> lines = new ArrayList<>();
        for (long up = MIN; up <= 8 * HOUR; up += 5 * MIN) {
            long java = up < MemoryLog.WARMUP_MS ? 40 * 1024 + up / MIN * 1024 : 100 * 1024;
            lines.add(MemoryLog.format(sample(3, up, java, 90)));
        }
        assertTrue(MemoryLog.summarize(lines, 3).contains("verdict: steady"));
    }

    @Test
    public void eachProcessIsItsOwnBlock() {
        List<String> lines = new ArrayList<>(run(1, 3, 0));
        lines.addAll(run(2, 1, 0));
        Map<Long, List<MemoryLog.Sample>> byProcess = MemoryLog.byProcess(lines);
        assertEquals(Arrays.asList(1L, 2L), new ArrayList<>(byProcess.keySet()));
        String text = MemoryLog.summarize(lines, 2);
        assertTrue(text.contains("pid 1 "));
        assertTrue(text.contains("pid 2 (this run)"));
        assertTrue(text.contains("too short to tell"));
    }

    @Test
    public void noLinesYet() {
        assertTrue(MemoryLog.summarize(new ArrayList<>(), 1).startsWith("(no samples yet"));
    }
}
