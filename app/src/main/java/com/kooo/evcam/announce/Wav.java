package com.kooo.evcam.announce;

/** 16-bit PCM WAV: reads what text-to-speech writes (down to mono), writes recordings. */
final class Wav {

    final int rate;
    final short[] mono;

    private Wav(int rate, short[] mono) {
        this.rate = rate;
        this.mono = mono;
    }

    static Wav of(int rate, short[] mono) {
        return new Wav(rate, mono);
    }

    /** Null when the bytes are not a 16-bit PCM WAV with some audio in it. */
    static Wav parse(byte[] b) {
        if (b == null || b.length < 12 || !tag(b, 0, "RIFF") || !tag(b, 8, "WAVE")) {
            return null;
        }
        int channels = 0;
        int rate = 0;
        int bits = 0;
        int at = 12;
        while (at + 8 <= b.length) {
            int size = le32(b, at + 4);
            int body = at + 8;
            if (size < 0) {
                return null;
            }
            if (tag(b, at, "fmt ") && body + 16 <= b.length) {
                int format = le16(b, body);
                channels = le16(b, body + 2);
                rate = le32(b, body + 4);
                bits = le16(b, body + 14);
                if (format != 1 || bits != 16 || channels < 1 || rate <= 0) {
                    return null;
                }
            } else if (tag(b, at, "data") && channels > 0) {
                int end = (int) Math.min((long) body + size, b.length);
                int frames = (end - body) / (2 * channels);
                if (frames <= 0) {
                    return null;
                }
                short[] mono = new short[frames];
                for (int f = 0; f < frames; f++) {
                    int sum = 0;
                    for (int c = 0; c < channels; c++) {
                        sum += (short) le16(b, body + (f * channels + c) * 2);
                    }
                    mono[f] = (short) (sum / channels);
                }
                return new Wav(rate, mono);
            }
            // chunks are padded to an even length
            at = body + size + (size & 1);
        }
        return null;
    }

    /** Mono 16-bit PCM WAV bytes. */
    static byte[] write(int rate, short[] mono, int frames) {
        int data = frames * 2;
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[] {'R', 'I', 'F', 'F'}).putInt(36 + data).put(new byte[] {'W', 'A', 'V', 'E'});
        b.put(new byte[] {'f', 'm', 't', ' '}).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16);
        b.put(new byte[] {'d', 'a', 't', 'a'}).putInt(data);
        for (int i = 0; i < frames; i++) {
            b.putShort(mono[i]);
        }
        return b.array();
    }

    /**
     * Raises a quiet recording so its loudest sample reaches 70% of full scale, by at most
     * {@code maxGain} (the cabin mic peaks around 1–2%). Returns the gain used.
     */
    static float normalize(short[] pcm, int frames, float maxGain) {
        int peak = 0;
        for (int i = 0; i < frames; i++) {
            peak = Math.max(peak, Math.abs((int) pcm[i]));
        }
        if (peak == 0) {
            return 1f;
        }
        float gain = Math.min(maxGain, 0.7f * 32767 / peak);
        for (int i = 0; i < frames; i++) {
            int v = Math.round(pcm[i] * gain);
            pcm[i] = (short) Math.max(-32768, Math.min(32767, v));
        }
        return gain;
    }

    private static boolean tag(byte[] b, int at, String s) {
        for (int i = 0; i < 4; i++) {
            if (b[at + i] != s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int le16(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8;
    }

    private static int le32(byte[] b, int at) {
        return le16(b, at) | le16(b, at + 2) << 16;
    }
}
