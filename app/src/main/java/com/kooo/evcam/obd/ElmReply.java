package com.kooo.evcam.obd;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What an ELM327 answered to an OBD request, as frames of bytes.
 *
 * <p>Expects headers off (ATH0) and the adapter's CAN formatting on (the default). A long CAN
 * answer then comes as a length line and numbered lines ("00A", "0:43…", "1:…"), joined here.
 * With headers off, two ECUs sending long answers at once cannot be told apart.</p>
 */
final class ElmReply {

    enum Kind {
        /** At least one frame. */
        DATA,
        /** The car is there but has nothing for this request. */
        NO_DATA,
        /** No car on the bus: ignition off, or the search failed. */
        NO_BUS,
        /** The adapter refused the request or lost the answer. */
        ERROR
    }

    private static final Pattern PART = Pattern.compile("^([0-9A-F]):([0-9A-F]*)$");
    private static final Pattern LENGTH = Pattern.compile("^[0-9A-F]{3}$");
    private static final Pattern HEX = Pattern.compile("^([0-9A-F]{2})+$");

    final Kind kind;
    final List<byte[]> frames;

    private ElmReply(Kind kind, List<byte[]> frames) {
        this.kind = kind;
        this.frames = Collections.unmodifiableList(frames);
    }

    static ElmReply parse(String raw) {
        List<byte[]> frames = new ArrayList<>();
        boolean noData = false;
        boolean noBus = false;
        ByteArrayOutputStream joined = null;
        int declared = -1;
        for (String part : raw.split("[\\r\\n]+")) {
            String line = part.replace(" ", "").replace(">", "").toUpperCase(Locale.ROOT)
                    .replace("SEARCHING...", "");
            if (line.isEmpty()) {
                continue;
            }
            if (line.contains("NODATA")) {
                noData = true;
                continue;
            }
            if (line.contains("UNABLETOCONNECT") || line.contains("BUSINIT") || line.contains("BUSERROR")
                    || line.contains("CANERROR")) {
                noBus = true;
                continue;
            }
            Matcher numbered = PART.matcher(line);
            if (numbered.matches()) {
                if (numbered.group(1).equals("0")) {
                    flush(joined, declared, frames);
                    joined = new ByteArrayOutputStream();
                }
                if (joined != null) {
                    write(joined, numbered.group(2));
                }
            } else if (LENGTH.matcher(line).matches()) {
                flush(joined, declared, frames);
                joined = null;
                declared = Integer.parseInt(line, 16);
            } else if (HEX.matcher(line).matches()) {
                ByteArrayOutputStream frame = new ByteArrayOutputStream();
                write(frame, line);
                frames.add(frame.toByteArray());
            }
            // Anything else ("?", "BUFFER FULL", "<RX ERROR", "STOPPED") is not an answer.
        }
        flush(joined, declared, frames);
        Kind kind = !frames.isEmpty() ? Kind.DATA : noBus ? Kind.NO_BUS : noData ? Kind.NO_DATA : Kind.ERROR;
        return new ElmReply(kind, frames);
    }

    private static void flush(ByteArrayOutputStream joined, int declared, List<byte[]> frames) {
        if (joined == null || joined.size() == 0) {
            return;
        }
        byte[] all = joined.toByteArray();
        if (declared < 0 || declared >= all.length) {
            frames.add(all);
            return;
        }
        // The last CAN frame is padded to eight bytes; the length line says where the answer ends.
        byte[] cut = new byte[declared];
        System.arraycopy(all, 0, cut, 0, declared);
        frames.add(cut);
    }

    private static void write(ByteArrayOutputStream out, String hex) {
        for (int i = 0; i + 1 < hex.length(); i += 2) {
            out.write(Integer.parseInt(hex.substring(i, i + 2), 16));
        }
    }
}
