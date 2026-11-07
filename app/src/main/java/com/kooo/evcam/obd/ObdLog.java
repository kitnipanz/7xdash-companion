package com.kooo.evcam.obd;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * One capture as a text file: time, tab, line. Times use the same clock and format as the car
 * signal recorder's files, so the two can be laid side by side. Written from the capture thread.
 */
final class ObdLog {

    private static final long FLUSH_MS = 1_000L;

    final File file;
    private final BufferedWriter out;
    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private long bytes;
    private long flushedAt;

    ObdLog(File file) throws IOException {
        this.file = file;
        out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
    }

    static String fileName(Date start) {
        return "obd-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(start) + ".txt";
    }

    void line(String text) throws IOException {
        String stamped = time.format(new Date()) + "\t" + text + "\n";
        out.write(stamped);
        bytes += stamped.length();
        // A power cut loses at most the last second
        long now = System.currentTimeMillis();
        if (now - flushedAt >= FLUSH_MS) {
            flushedAt = now;
            out.flush();
        }
    }

    long bytes() {
        return bytes;
    }

    void close() {
        try {
            out.close();
        } catch (IOException e) {
            // the drive went away; what was flushed stays
        }
    }
}
