package com.kooo.evcam.recording;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Copies one time range out of a finished mp4. Samples start at the previous
 * keyframe so the clip can play, and stop at {@code endUs}.
 */
public final class InstantClip {

    private InstantClip() {
    }

    public static void write(File source, File dest, long startUs, long endUs) throws IOException {
        if (source == null || !source.isFile()) {
            throw new IOException("no source");
        }
        if (dest == null) {
            throw new IOException("no dest");
        }
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("mkdir " + parent.getAbsolutePath());
        }
        if (dest.exists() && !dest.delete()) {
            throw new IOException("replace " + dest.getAbsolutePath());
        }
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean started = false;
        int wrote = 0;
        try {
            extractor.setDataSource(source.getAbsolutePath());
            muxer = new MediaMuxer(dest.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int tracks = extractor.getTrackCount();
            int[] outIndex = new int[tracks];
            boolean[] use = new boolean[tracks];
            boolean[] video = new boolean[tracks];
            int[] budget = new int[tracks];
            int[] seen = new int[tracks];
            int selected = 0;
            for (int i = 0; i < tracks; i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null) {
                    continue;
                }
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) {
                    continue;
                }
                extractor.selectTrack(i);
                outIndex[i] = muxer.addTrack(format);
                use[i] = true;
                if (mime.startsWith("video/")) {
                    video[i] = true;
                    int fps = 20;
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        fps = format.getInteger(MediaFormat.KEY_FRAME_RATE);
                    }
                    budget[i] = InstantSpan.videoSampleBudget(fps);
                }
                selected++;
            }
            if (selected == 0) {
                throw new IOException("no tracks " + source.getName());
            }
            muxer.start();
            started = true;
            extractor.seekTo(Math.max(0L, startUs), MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            ByteBuffer buffer = ByteBuffer.allocate(256 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long base = -1L;
            long stopAfter = endUs + 500_000L;
            while (true) {
                int track = extractor.getSampleTrackIndex();
                if (track < 0) {
                    break;
                }
                long pts = extractor.getSampleTime();
                if (pts < 0 || !use[track] || pts > endUs) {
                    if (pts > stopAfter) {
                        break;
                    }
                    extractor.advance();
                    continue;
                }
                int size = (int) extractor.getSampleSize();
                if (size < 0) {
                    extractor.advance();
                    continue;
                }
                if (buffer.capacity() < size) {
                    buffer = ByteBuffer.allocate(size);
                }
                buffer.clear();
                int read = extractor.readSampleData(buffer, 0);
                if (read < 0) {
                    break;
                }
                if (base < 0) {
                    base = pts;
                }
                info.offset = 0;
                info.size = read;
                info.presentationTimeUs = Math.max(0L, pts - base);
                info.flags = extractor.getSampleFlags();
                muxer.writeSampleData(outIndex[track], buffer, info);
                wrote++;
                if (video[track]) {
                    seen[track]++;
                    if (seen[track] > budget[track]) {
                        break;
                    }
                }
                extractor.advance();
            }
            if (wrote == 0) {
                throw new IOException("empty clip " + source.getName());
            }
            muxer.stop();
            started = false;
        } catch (IOException e) {
            if (dest.exists() && !dest.delete()) {
                // the caller deletes the cache dir later
            }
            throw e;
        } finally {
            if (muxer != null) {
                try {
                    if (started) {
                        muxer.stop();
                    }
                } catch (Exception ignored) {
                }
                muxer.release();
            }
            extractor.release();
            if (wrote == 0 && dest.exists() && !dest.delete()) {
                // leave it; the cache dir is wiped after the job
            }
        }
    }
}
