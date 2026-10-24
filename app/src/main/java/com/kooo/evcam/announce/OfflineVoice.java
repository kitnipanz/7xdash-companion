package com.kooo.evcam.announce;

import android.content.Context;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;
import com.kooo.evcam.blackbox.BlackBox;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * 7xDash's own English voice, for head units with no speech engine that speaks English. Piper
 * "libritts_r" (CC BY 4.0) run by sherpa-onnx (Apache-2.0), fully offline once downloaded. The
 * download (22 MB) is checked against a pinned hash before it is unpacked. There is no Thai voice
 * in this form; Thai phrases are recorded instead.
 */
public final class OfflineVoice {

    static final String NAME = "vits-piper-en_US-libritts_r-medium-int8";
    private static final String URL_ =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" + NAME + ".tar.bz2";
    private static final String SHA256 = "7e4552e239988f4896872822b56e99e0e9e00958164e3f6bdf5ee14391fbe829";
    static final int DOWNLOAD_MB = 22;

    interface Progress {
        /** Worker thread: 0–100 while downloading, then unpacking. */
        void onProgress(int percent, boolean unpacking);
    }

    private static OfflineTts engine;

    private OfflineVoice() {
    }

    private static File dir(Context context) {
        return new File(new File(context.getFilesDir(), "voices"), NAME);
    }

    public static boolean isReady(Context context) {
        File d = dir(context);
        return new File(d, "en_US-libritts_r-medium.onnx").isFile() && new File(d, "tokens.txt").isFile()
                && new File(d, "espeak-ng-data").isDirectory();
    }

    /** Worker thread. Downloads, checks and unpacks the voice; false on any failure (nothing half-done is kept). */
    static boolean download(Context context, Progress progress) {
        File voices = dir(context).getParentFile();
        File archive = new File(context.getCacheDir(), NAME + ".tar.bz2");
        File unpacking = new File(voices, NAME + ".part");
        try {
            if (!voices.isDirectory() && !voices.mkdirs()) {
                return false;
            }
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            HttpURLConnection http = (HttpURLConnection) new URL(URL_).openConnection();
            http.setConnectTimeout(15_000);
            http.setReadTimeout(30_000);
            http.setInstanceFollowRedirects(true);
            long total = http.getContentLengthLong();
            try (InputStream in = http.getInputStream(); OutputStream out = new FileOutputStream(archive)) {
                byte[] buf = new byte[64 * 1024];
                long got = 0;
                int n;
                int shown = -1;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    sha.update(buf, 0, n);
                    got += n;
                    int percent = total > 0 ? (int) (got * 100 / total) : 0;
                    if (percent != shown) {
                        shown = percent;
                        progress.onProgress(percent, false);
                    }
                }
            } finally {
                http.disconnect();
            }
            String hash = hex(sha.digest());
            if (!SHA256.equals(hash)) {
                BlackBox.note("Announcement: English voice download hash " + hash + ", expected " + SHA256);
                return false;
            }
            progress.onProgress(0, true);
            delete(unpacking);
            unpack(archive, unpacking);
            File unpacked = new File(unpacking, NAME);
            delete(dir(context));
            boolean ok = unpacked.renameTo(dir(context)) && isReady(context);
            BlackBox.note("Announcement: English voice " + (ok ? "installed" : "failed to install"));
            return ok;
        } catch (Exception e) {
            BlackBox.note("Announcement: English voice download failed: " + e);
            return false;
        } finally {
            archive.delete();
            delete(unpacking);
        }
    }

    /** Worker thread. The phrase in the English voice, or null when the voice is missing or fails. */
    static synchronized Wav speak(Context context, String text) {
        if (!isReady(context)) {
            return null;
        }
        File cached = new File(new File(context.getCacheDir(), "announce"),
                "en-" + Integer.toHexString(text.hashCode()) + ".wav");
        if (cached.length() > 44) {
            try {
                Wav wav = Wav.parse(java.nio.file.Files.readAllBytes(cached.toPath()));
                if (wav != null) {
                    return wav;
                }
            } catch (Exception ignored) {
                // generate it again
            }
        }
        try {
            if (engine == null) {
                File d = dir(context);
                OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
                vits.setModel(new File(d, "en_US-libritts_r-medium.onnx").getPath());
                vits.setTokens(new File(d, "tokens.txt").getPath());
                vits.setDataDir(new File(d, "espeak-ng-data").getPath());
                OfflineTtsModelConfig model = new OfflineTtsModelConfig();
                model.setVits(vits);
                model.setNumThreads(2);
                OfflineTtsConfig config = new OfflineTtsConfig();
                config.setModel(model);
                engine = new OfflineTts(null, config);
            }
            GeneratedAudio audio = engine.generate(text, 0, 1.0f);
            float[] samples = audio.getSamples();
            if (samples == null || samples.length == 0) {
                return null;
            }
            short[] pcm = toPcm(samples);
            try {
                cached.getParentFile().mkdirs();
                java.nio.file.Files.write(cached.toPath(), Wav.write(audio.getSampleRate(), pcm, pcm.length));
            } catch (Exception ignored) {
                // plays anyway, generated again next time
            }
            return Wav.of(audio.getSampleRate(), pcm);
        } catch (Throwable t) {
            BlackBox.note("Announcement: English voice failed: " + t);
            return null;
        }
    }

    static short[] toPcm(float[] samples) {
        short[] out = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            float v = Math.max(-1f, Math.min(1f, samples[i]));
            out[i] = (short) Math.round(v * 32767);
        }
        return out;
    }

    private static void unpack(File archive, File into) throws Exception {
        String root = into.getCanonicalPath() + File.separator;
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new BZip2CompressorInputStream(
                new BufferedInputStream(new FileInputStream(archive))))) {
            TarArchiveEntry entry;
            byte[] buf = new byte[64 * 1024];
            while ((entry = tar.getNextEntry()) != null) {
                File out = new File(into, entry.getName());
                // never write outside the folder, whatever the archive says
                if (!out.getCanonicalPath().startsWith(root)) {
                    throw new SecurityException("archive entry outside the voice folder: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                out.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(out)) {
                    int n;
                    while ((n = tar.read(buf)) > 0) {
                        o.write(buf, 0, n);
                    }
                }
            }
        }
    }

    private static void delete(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                delete(c);
            }
        }
        f.delete();
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
