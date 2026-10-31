package com.kooo.evcam.airplay;

import static org.junit.Assume.assumeTrue;

import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Feeds a clip with long gaps (a still Mac screen) and times each picture reaching the surface. */
@RunWith(AndroidJUnit4.class)
public class MirrorDecoderLatencyTest {

    @Test
    public void eachPictureShowsWithoutTheNextPacket() throws Exception {
        File clip = new File("/data/local/tmp/mirror.h264");
        assumeTrue(clip.exists());
        List<byte[]> frames = split(Files.readAllBytes(clip.toPath()));

        HandlerThread t = new HandlerThread("st");
        t.start();
        ImageReader st = ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 4);
        Semaphore shown = new Semaphore(0);
        st.setOnImageAvailableListener(r -> {
            Image img = r.acquireLatestImage();
            if (img != null) {
                img.close();
            }
            shown.release();
        }, new Handler(t.getLooper()));
        Surface surface = st.getSurface();

        MirrorDecoder d = new MirrorDecoder(60);
        d.setSize(1280, 720);
        d.setSurface(surface);
        int late = 0;
        long worst = 0;
        // first 30 frames back to back, then 15 frames 300 ms apart like a still screen
        for (int i = 0; i < 45; i++) {
            long at = SystemClock.elapsedRealtime();
            // stamped 50 ms in the past, like a sender clock synced over a network
            d.feed(frames.get(i), System.currentTimeMillis() * 1_000_000L - 50_000_000L);
            if (i >= 30) {
                boolean ok = shown.tryAcquire(250, TimeUnit.MILLISECONDS);
                long took = SystemClock.elapsedRealtime() - at;
                worst = Math.max(worst, took);
                if (!ok) {
                    late++;
                }
                shown.drainPermits();
                SystemClock.sleep(300 - Math.min(took, 300));
            } else {
                SystemClock.sleep(33);
                shown.drainPermits();
            }
        }
        Log.i("MirrorLatency", "late=" + late + " worstMs=" + worst);
        d.release();
        st.close();
        t.quitSafely();
        Assert.assertEquals("pictures that waited for the next packet", 0, late);
    }

    /** Split Annex-B at access unit delimiters (NAL 9). */
    private static List<byte[]> split(byte[] b) {
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i + 4 < b.length; i++) {
            if (b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 0 && b[i + 3] == 1 && (b[i + 4] & 0x1F) == 9) {
                starts.add(i);
            }
        }
        starts.add(b.length);
        List<byte[]> out = new ArrayList<>();
        for (int k = 0; k + 1 < starts.size(); k++) {
            byte[] f = new byte[starts.get(k + 1) - starts.get(k)];
            System.arraycopy(b, starts.get(k), f, 0, f.length);
            out.add(f);
        }
        return out;
    }
}
