package com.kooo.evcam.share;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.SystemClock;
import android.view.Surface;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.camera.CameraSlots;
import com.kooo.evcam.camera.EncodeSize;
import com.kooo.evcam.camera.TargetBitrate;
import com.kooo.evcam.camera.WatermarkText;
import com.kooo.evcam.playback.PlaybackViewport;
import com.kooo.evcam.zeekr.CompositeStreamGeometry;
import com.kooo.evcam.zeekr.FisheyeGlPipe;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把环视录像拉直后再交给 U 盘。
 *
 * <p>盘上那份是鱼眼原片，屏幕上的校正只是看着。导出要的是拔下来就能播的直画面，
 * 所以这里解码 → {@link FisheyeGlPipe} 逐像素校正 → 再编码。座舱不是鱼眼，不走这里。</p>
 *
 * <p>校正参数用设置里那一套（投影、视野、强度）。屏幕上的拉直开关关着时不走这里，
 * 导出的就是录像原片。</p>
 */
public final class SurroundDefish {

    private static final String TAG = "SurroundDefish";
    private static final String AVC = "video/avc";
    /** 最后一帧迟迟没画上就照样封口，免得进度停在 99% 直到整段超时。 */
    static final long FRAME_STALL_MS = 8_000L;

    /**
     * 拉直后再压成 H.264 的码率。
     *
     * <p>原片多半是 H.265。把那个码率原样套到 H.264 上，拉直后边缘的细节会被压成一块一块，
     * 看起来比鱼眼原片顿。按输出尺寸走高档，H.265 原片再抬一档，但不超过编码器开始丢帧的上限。</p>
     */
    static int exportBitrate(int sourceBitrate, boolean sourceHevc, int outW, int outH, int fps) {
        int fitted = TargetBitrate.compute(3, outW, outH, Math.max(1, fps), false);
        if (sourceBitrate <= 0) {
            return fitted;
        }
        long lifted = sourceHevc ? sourceBitrate * 9L / 5L : sourceBitrate;
        long chosen = Math.max(fitted, lifted);
        if (chosen > TargetBitrate.MAX) {
            chosen = TargetBitrate.MAX;
        }
        return (int) chosen;
    }

    /**
     * 这一帧该用的时间戳。原片时间在走就用原片的；卡住（全是 0，或编码器按编码耗时盖章）
     * 就按标称帧间隔补，免得成片比鱼眼顿。
     */
    static long nextSourcePts(long sampleUs, long lastQueued, long stepUs) {
        long pts = Math.max(0L, sampleUs);
        if (lastQueued < 0) {
            return pts;
        }
        if (pts > lastQueued) {
            return pts;
        }
        return lastQueued + Math.max(1L, stepUs);
    }

    /** 编码输出按放出去的顺序对上原片时间。编码器自己的时间戳是编码花了多久，不是这一帧在片子里的位置。 */
    static long assignPts(ArrayDeque<Long> pending, long lastWritten) {
        long pts = pending.isEmpty()
                ? (lastWritten < 0 ? 0L : lastWritten + 1L)
                : pending.removeFirst();
        if (lastWritten >= 0 && pts <= lastWritten) {
            return lastWritten + 1L;
        }
        return pts;
    }

    private static MediaFormat videoFormat(int width, int height, int bitrate, int fps, boolean tuned) {
        MediaFormat format = MediaFormat.createVideoFormat(AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        if (!tuned) {
            return format;
        }
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
        // 0 是实时：跟不上就丢帧。拉直比实时慢，丢了就比鱼眼顿。1 是尽力而为，排队不丢。
        format.setInteger(MediaFormat.KEY_PRIORITY, 1);
        format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE);
        format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        return format;
    }

    private SurroundDefish() {
    }

    /** 文件名是环视的 mp4（含旧名 {@code _front}）。 */
    public static boolean isSurroundVideo(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase(Locale.US);
        if (!lower.endsWith(".mp4")) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        int underscore = base.lastIndexOf('_');
        if (underscore < 0 || underscore >= base.length() - 1) {
            return false;
        }
        String suffix = base.substring(underscore + 1);
        return CameraSlots.SURROUND.equals(CameraSlots.canonical(suffix));
    }

    /** 拉直进度，0 到 100。在拉直线程上回调。 */
    public interface Percent {
        void onPercent(int percent);
    }

    /** 环视视频，而且屏幕上的拉直开关开着。关着就按原文件导出。 */
    public static boolean wanted(Context context, File file) {
        return file != null
                && isSurroundVideo(file.getName())
                && new AppConfig(context).isFisheyeCorrection();
    }

    /**
     * 把 {@code source} 拉直写到 {@code dest}。失败时删掉半成品。
     */
    public static void write(Context context, File source, File dest) throws IOException {
        write(context, source, dest, null);
    }

    /**
     * 同 {@link #write(Context, File, File)}。{@code cancel} 变成 true 时停在下一帧，删掉半成品。
     */
    public static void write(Context context, File source, File dest, AtomicBoolean cancel)
            throws IOException {
        write(context, source, dest, cancel, null);
    }

    /** 同上一档。{@code percent} 按画面时间报 0–100，调用方自己切回主线程。 */
    public static void write(Context context, File source, File dest, AtomicBoolean cancel,
                             Percent percent) throws IOException {
        if (dest.exists() && !dest.delete()) {
            throw new IOException("replace " + dest.getAbsolutePath());
        }
        AppConfig config = new AppConfig(context);
        try {
            transcode(source, dest,
                    config.getFisheyeFov(),
                    config.getFisheyeProjection(),
                    1f,
                    cancel,
                    percent,
                    brandLine(context),
                    clipStartMillis(source.getName()));
        } catch (IOException e) {
            if (dest.exists() && !dest.delete()) {
                AppLog.w(TAG, "删不掉半成品: " + dest.getAbsolutePath());
            }
            throw e;
        }
    }

    private static void transcode(File source, File dest,
                                  float fov, String projection, float strength,
                                  AtomicBoolean cancel, Percent percent,
                                  String stampLeft, long clipStartMs) throws IOException {
        MediaExtractor videoEx = new MediaExtractor();
        MediaExtractor audioEx = new MediaExtractor();
        MediaCodec decoder = null;
        MediaCodec encoder = null;
        MediaMuxer muxer = null;
        FisheyeGlPipe pipe = null;
        Surface decoderSurface = null;
        Surface encoderSurface = null;
        try {
            videoEx.setDataSource(source.getAbsolutePath());
            audioEx.setDataSource(source.getAbsolutePath());
            int videoIndex = track(videoEx, "video/");
            if (videoIndex < 0) {
                throw new IOException("no video");
            }
            videoEx.selectTrack(videoIndex);
            MediaFormat inFormat = videoEx.getTrackFormat(videoIndex);
            int width = inFormat.getInteger(MediaFormat.KEY_WIDTH);
            int height = inFormat.getInteger(MediaFormat.KEY_HEIGHT);
            if ((width & 1) != 0) {
                width--;
            }
            if ((height & 1) != 0) {
                height--;
            }
            int[] frame = exportFrame(width, height);
            int outW = frame[0];
            int outH = frame[1];
            int contentH = frame[2];
            int inset = frame[3];
            int fps = inFormat.containsKey(MediaFormat.KEY_FRAME_RATE)
                    ? inFormat.getInteger(MediaFormat.KEY_FRAME_RATE) : 30;
            if (fps <= 0) {
                fps = 30;
            }
            int sourceRate = inFormat.containsKey(MediaFormat.KEY_BIT_RATE)
                    ? inFormat.getInteger(MediaFormat.KEY_BIT_RATE) : 0;
            String inMime = inFormat.getString(MediaFormat.KEY_MIME);
            boolean sourceHevc = inMime != null && inMime.toLowerCase(Locale.US).contains("hevc");
            int bitrate = exportBitrate(sourceRate, sourceHevc, outW, outH, fps);
            long durationUs = inFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? inFormat.getLong(MediaFormat.KEY_DURATION) : 0L;

            int audioIndex = track(audioEx, "audio/");
            MediaFormat audioFormat = audioIndex >= 0 ? audioEx.getTrackFormat(audioIndex) : null;
            if (audioIndex >= 0) {
                audioEx.selectTrack(audioIndex);
            }

            encoder = MediaCodec.createEncoderByType(AVC);
            MediaFormat outFormat = videoFormat(outW, outH, bitrate, fps, true);
            try {
                encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (MediaCodec.CodecException | IllegalArgumentException e) {
                AppLog.w(TAG, "编码器不吃画质参数，退回基本设置: " + e.getMessage());
                encoder.release();
                encoder = MediaCodec.createEncoderByType(AVC);
                encoder.configure(videoFormat(outW, outH, bitrate, fps, false),
                        null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            }
            encoderSurface = encoder.createInputSurface();
            encoder.start();

            float[] lanes = lanesFor(width, height);
            boolean strip = CompositeStreamGeometry.looksLikeCompositeByRatio(width, height);
            if (!strip && contentH < outH) {
                lanes = FisheyeGlPipe.gridLanes(contentH / (float) outH);
            }
            pipe = FisheyeGlPipe.start("usb", encoderSurface, width, height, lanes);
            if (pipe == null) {
                throw new IOException("defish pipe");
            }
            pipe.setCorrection(true, fov, projection, strength);
            if (!strip) {
                float srcFrac = inset <= 0 ? 1f : (height - inset) / (float) height;
                pipe.setExport(outW / (float) Math.max(1, contentH), lanes,
                        FisheyeGlPipe.gridLanes(srcFrac),
                        outH - contentH, srcFrac, contentH / (float) outH,
                        stampLeft, clipStartMs);
            }
            decoderSurface = pipe.newInputSurface();
            if (decoderSurface == null) {
                throw new IOException("defish input");
            }

            String mime = inFormat.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime != null ? mime : AVC);
            decoder.configure(inFormat, decoderSurface, null, 0);
            decoder.start();

            muxer = new MediaMuxer(dest.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            run(videoEx, audioEx, decoder, encoder, muxer, pipe, audioFormat,
                    durationUs, fps, cancel, percent);
            AppLog.i(TAG, "环视已拉直: " + dest.getName() + " " + outW + "x" + outH);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage() == null ? "defish" : e.getMessage(), e);
        } finally {
            release(decoder);
            if (decoderSurface != null) {
                decoderSurface.release();
            }
            if (pipe != null) {
                pipe.release();
            }
            release(encoder);
            if (encoderSurface != null) {
                encoderSurface.release();
            }
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Exception ignored) {
                    // stop 失败时 release 还会再抛一次
                }
            }
            videoEx.release();
            audioEx.release();
        }
    }

    private static void run(MediaExtractor videoEx, MediaExtractor audioEx,
                            MediaCodec decoder, MediaCodec encoder, MediaMuxer muxer,
                            FisheyeGlPipe pipe, MediaFormat audioFormat, long durationUs,
                            int fps, AtomicBoolean cancel, Percent percent)
            throws IOException {
        MediaCodec.BufferInfo decInfo = new MediaCodec.BufferInfo();
        MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();
        boolean inEos = false;
        boolean decEos = false;
        boolean signaled = false;
        boolean encEos = false;
        boolean muxerStarted = false;
        int rendered = 0;
        int lastPercent = -1;
        int videoTrack = -1;
        int audioTrack = -1;
        long budgetMs = Math.max(120_000L, durationUs / 1000L * 4L + 30_000L);
        long deadline = SystemClock.elapsedRealtime() + budgetMs;
        long lastPresented = -1L;
        long presentedMovedAt = SystemClock.elapsedRealtime();
        long stepUs = 1_000_000L / Math.max(1, fps);
        long lastQueued = -1L;
        long lastWritten = -1L;
        ArrayDeque<Long> framePts = new ArrayDeque<>();

        while (!encEos) {
            if (cancel != null && cancel.get()) {
                throw new IOException("cancelled");
            }
            if (SystemClock.elapsedRealtime() > deadline) {
                throw new IOException("timeout");
            }
            // 先腾编码器的缓冲。画线程的 swap 会卡在这上面，不腾的话两边互相等。
            int enc = encoder.dequeueOutputBuffer(encInfo, 10_000);
            if (enc == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) {
                    throw new IOException("format twice");
                }
                videoTrack = muxer.addTrack(encoder.getOutputFormat());
                if (audioFormat != null) {
                    audioTrack = muxer.addTrack(audioFormat);
                }
                muxer.start();
                muxerStarted = true;
            } else if (enc >= 0) {
                if (!muxerStarted) {
                    throw new IOException("sample before format");
                }
                ByteBuffer buf = encoder.getOutputBuffer(enc);
                if (buf != null && encInfo.size > 0
                        && (encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    encInfo.presentationTimeUs = assignPts(framePts, lastWritten);
                    lastWritten = encInfo.presentationTimeUs;
                    buf.position(encInfo.offset);
                    buf.limit(encInfo.offset + encInfo.size);
                    muxer.writeSampleData(videoTrack, buf, encInfo);
                }
                boolean eos = (encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                if (encInfo.size > 0 && (encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    lastPercent = report(percent, durationUs, encInfo.presentationTimeUs, lastPercent);
                }
                encoder.releaseOutputBuffer(enc, false);
                if (eos) {
                    encEos = true;
                    break;
                }
            }
            if (!inEos) {
                int in = decoder.dequeueInputBuffer(10_000);
                if (in >= 0) {
                    ByteBuffer buf = decoder.getInputBuffer(in);
                    int n = buf == null ? -1 : videoEx.readSampleData(buf, 0);
                    if (n < 0) {
                        decoder.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inEos = true;
                    } else {
                        long sampleUs = Math.max(0L, videoEx.getSampleTime());
                        decoder.queueInputBuffer(in, 0, n, sampleUs, 0);
                        videoEx.advance();
                    }
                }
            }
            long presented = pipe.drawn();
            if (presented != lastPresented) {
                lastPresented = presented;
                presentedMovedAt = SystemClock.elapsedRealtime();
            }
            // 一次只放一帧。一次放多帧时 SurfaceTexture 只留最后一帧，画过的帧数永远少于放出去的，
            // 结束符就发不出去，进度停在最后一帧的 97–99%。
            if (!decEos && canReleaseFrame(presented, rendered)) {
                int out = decoder.dequeueOutputBuffer(decInfo, 10_000);
                if (out >= 0) {
                    boolean eos = (decInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    boolean render = decInfo.size > 0;
                    decoder.releaseOutputBuffer(out, render);
                    if (render) {
                        rendered++;
                        long pts = nextSourcePts(decInfo.presentationTimeUs, lastQueued, stepUs);
                        framePts.addLast(pts);
                        lastQueued = pts;
                    }
                    if (eos) {
                        decEos = true;
                    }
                }
            }
            if (canSignalEncoder(decEos, signaled, presented, rendered)) {
                encoder.signalEndOfInputStream();
                signaled = true;
            } else if (forceSignalEncoder(decEos, signaled, presented, rendered,
                    SystemClock.elapsedRealtime() - presentedMovedAt)) {
                AppLog.w(TAG, "最后一帧没画上，照样封口 rendered=" + rendered + " drawn=" + presented);
                encoder.signalEndOfInputStream();
                signaled = true;
            }
        }
        if (rendered == 0) {
            throw new IOException("no frames");
        }
        if (audioTrack >= 0) {
            copyAudio(audioEx, muxer, audioTrack);
        }
        muxer.stop();
        if (percent != null) {
            percent.onPercent(100);
        }
    }

    /** 上一帧已经画完，才能再放一帧。多放会被 SurfaceTexture 丢掉。 */
    static boolean canReleaseFrame(long presented, int released) {
        return presented >= released;
    }

    /** 解码结束，而且放出去的帧都画完了，才能通知编码器收尾。 */
    static boolean canSignalEncoder(boolean decoderDone, boolean signaled,
                                    long presented, int released) {
        return decoderDone && !signaled && presented >= released;
    }

    /** 画线程卡住时不要一直等到整段超时。 */
    static boolean forceSignalEncoder(boolean decoderDone, boolean signaled,
                                      long presented, int released, long stalledMs) {
        return decoderDone && !signaled && presented < released && stalledMs >= FRAME_STALL_MS;
    }

    private static int report(Percent percent, long durationUs, long timeUs, int last) {
        if (percent == null || durationUs <= 0 || timeUs < 0) {
            return last;
        }
        int pct = (int) Math.min(99L, timeUs * 100L / durationUs);
        if (pct > last) {
            percent.onPercent(pct);
            return pct;
        }
        return last;
    }

    private static void copyAudio(MediaExtractor audioEx, MediaMuxer muxer, int audioTrack) {
        ByteBuffer buf = ByteBuffer.allocate(256 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int n = audioEx.readSampleData(buf, 0);
            if (n < 0) {
                return;
            }
            info.offset = 0;
            info.size = n;
            info.presentationTimeUs = Math.max(0L, audioEx.getSampleTime());
            info.flags = audioEx.getSampleFlags();
            muxer.writeSampleData(audioTrack, buf, info);
            audioEx.advance();
        }
    }

    /**
     * 2×2 的导出尺寸。每一格是 16:9。原片底下若有行驶信息条，原样留在导出画面底下。
     * 合成流长条保持原尺寸。
     *
     * @return {@code {宽, 总高, 画面高, 原片信息条高}}
     */
    static int[] exportFrame(int srcW, int srcH) {
        int w = srcW & ~1;
        int h = srcH & ~1;
        if (w < 2 || h < 2) {
            return new int[]{Math.max(2, w), Math.max(2, h), Math.max(2, h), 0};
        }
        if (CompositeStreamGeometry.looksLikeCompositeByRatio(w, h)) {
            return new int[]{w, h, h, 0};
        }
        int inset = PlaybackViewport.infoBarInset(w, h, true);
        int content = (w * 9 / 16) & ~1;
        if (content < 2) {
            content = 2;
        }
        int total = content + inset;
        if (w > EncodeSize.MAX_SIDE || total > EncodeSize.MAX_SIDE) {
            float scale = Math.min((float) EncodeSize.MAX_SIDE / w,
                    (float) EncodeSize.MAX_SIDE / Math.max(1, total));
            w = Math.max(2, ((int) (w * scale)) & ~1);
            content = Math.max(2, ((int) (content * scale)) & ~1);
            total = content + inset;
            if (total > EncodeSize.MAX_SIDE) {
                content = Math.max(2, (EncodeSize.MAX_SIDE - inset) & ~1);
                total = content + inset;
            }
        }
        return new int[]{w, total, content, inset};
    }

    /** 文件名里的 {@code yyyyMMdd_HHmmss}。没有就返回 -1，信息条不写时间。 */
    static long clipStartMillis(String fileName) {
        if (fileName == null) {
            return -1L;
        }
        Matcher matcher = Pattern.compile("(\\d{8})_(\\d{6})").matcher(fileName);
        if (!matcher.find()) {
            return -1L;
        }
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
            format.setLenient(false);
            Date date = format.parse(matcher.group(1) + matcher.group(2));
            return date == null ? -1L : date.getTime();
        } catch (ParseException e) {
            return -1L;
        }
    }

    /** 和录像左上角同一行：应用名、版本、车牌。 */
    private static String brandLine(Context context) {
        String version = "";
        try {
            version = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            AppLog.w(TAG, "读版本失败: " + e);
        }
        return WatermarkText.brandLine(
                context.getString(com.kooo.evcam.R.string.app_name),
                version,
                new AppConfig(context).getLicensePlate());
    }

    /** 录像落盘是 2×2。万一还是合成流长条，按长条的四格来。 */
    static float[] lanesFor(int width, int height) {
        if (!CompositeStreamGeometry.looksLikeCompositeByRatio(width, height)) {
            return FisheyeGlPipe.GRID_2X2;
        }
        CompositeStreamGeometry.Plan plan = CompositeStreamGeometry.analyse(null, width, height);
        float[] lanes = new float[plan.lanes.length * 4];
        for (int i = 0; i < plan.lanes.length; i++) {
            CompositeStreamGeometry.Lane lane = plan.lanes[i];
            lanes[i * 4] = lane.u0;
            lanes[i * 4 + 1] = lane.v0;
            lanes[i * 4 + 2] = lane.u1 - lane.u0;
            lanes[i * 4 + 3] = lane.v1 - lane.v0;
        }
        return lanes;
    }

    private static int track(MediaExtractor extractor, String prefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private static void release(MediaCodec codec) {
        if (codec == null) {
            return;
        }
        try {
            codec.stop();
        } catch (Exception ignored) {
            // 没 start 成功时 stop 会抛
        }
        try {
            codec.release();
        } catch (Exception ignored) {
            // 已经放掉了
        }
    }
}
