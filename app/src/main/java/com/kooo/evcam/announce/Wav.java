package com.kooo.evcam.announce;

/** 16-bit PCM WAV: reads what text-to-speech writes (down to mono), writes recordings. */
public final class Wav {

    public final int rate;
    public final short[] mono;

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

    /**
     * Plays the sound {@code factor} times faster, like a tape: shorter and higher (or longer and
     * lower below 1). Linear interpolation; 1 returns the input as it is.
     */
    static short[] resample(short[] in, float factor) {
        if (in == null || factor <= 0 || Math.abs(factor - 1f) < 0.001f) {
            return in;
        }
        int n = Math.max(1, (int) (in.length / factor));
        short[] out = new short[n];
        for (int i = 0; i < n; i++) {
            float at = i * factor;
            int j = (int) at;
            if (j >= in.length - 1) {
                out[i] = in[in.length - 1];
                continue;
            }
            float f = at - j;
            out[i] = (short) Math.round(in[j] + (in[j + 1] - in[j]) * f);
        }
        return out;
    }

    /**
     * Cleans a cabin recording before it is raised: cuts the rumble under 100 Hz, then pulls down the
     * room's echo and hiss between and after words (a soft gate that opens on the voice and closes
     * over the reverb tail), then trims the quiet at both ends. Returns the frames kept, moved to the start.
     */
    static int clean(short[] pcm, int frames, int rate) {
        if (frames <= 0) {
            return frames;
        }
        // one-pole high-pass at ~100 Hz
        float a = (float) Math.exp(-2 * Math.PI * 100 / rate);
        float prevIn = 0;
        float prevOut = 0;
        float[] x = new float[frames];
        for (int i = 0; i < frames; i++) {
            float in = pcm[i];
            float out = a * (prevOut + in - prevIn);
            prevIn = in;
            prevOut = out;
            x[i] = out;
        }
        // loudness of 20 ms blocks: the quiet ones are the room, the loud ones the voice
        int block = Math.max(1, rate / 50);
        int blocks = (frames + block - 1) / block;
        float[] rms = new float[blocks];
        float loudest = 0;
        for (int b = 0; b < blocks; b++) {
            int from = b * block;
            int to = Math.min(frames, from + block);
            double sum = 0;
            for (int i = from; i < to; i++) {
                sum += x[i] * x[i];
            }
            rms[b] = (float) Math.sqrt(sum / Math.max(1, to - from));
            loudest = Math.max(loudest, rms[b]);
        }
        if (loudest <= 0) {
            return frames;
        }
        float[] sorted = rms.clone();
        java.util.Arrays.sort(sorted);
        float floor = sorted[blocks / 5];
        // opens on speech; the echo that follows a word sits well under it
        float threshold = Math.max(floor * 3f, loudest * 0.08f);
        float attack = 1f - (float) Math.exp(-1.0 / (rate * 0.002));
        float release = 1f - (float) Math.exp(-1.0 / (rate * 0.060));
        // loudness the same way the threshold was measured (RMS over ~10 ms), not peaks:
        // noise peaks sit ~10 dB over its RMS and would hold the gate open
        float meanAttack = 1f - (float) Math.exp(-1.0 / (rate * 0.005));
        float meanRelease = 1f - (float) Math.exp(-1.0 / (rate * 0.030));
        float meanSquare = 0;
        float gain = 0;
        for (int i = 0; i < frames; i++) {
            float sq = x[i] * x[i];
            meanSquare += (sq > meanSquare ? meanAttack : meanRelease) * (sq - meanSquare);
            float env = (float) Math.sqrt(meanSquare);
            float want;
            if (env >= threshold) {
                want = 1f;
            } else {
                float r = env / threshold;
                want = r * r * r; // 1:4 downward expansion: the tail fades instead of clicking off
            }
            gain += (want > gain ? attack : release) * (want - gain);
            x[i] *= gain;
        }
        // trim quiet ends, keeping 150 ms around the voice
        int first = -1;
        int last = -1;
        for (int b = 0; b < blocks; b++) {
            if (rms[b] >= threshold) {
                if (first < 0) {
                    first = b;
                }
                last = b;
            }
        }
        int start = 0;
        int end = frames;
        if (first >= 0) {
            int pad = rate * 150 / 1000;
            start = Math.max(0, first * block - pad);
            end = Math.min(frames, (last + 1) * block + pad);
        }
        int n = end - start;
        for (int i = 0; i < n; i++) {
            float v = x[start + i];
            pcm[i] = (short) Math.max(-32768, Math.min(32767, Math.round(v)));
        }
        return n;
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
