package com.kooo.evcam.zeekr;

import static org.junit.Assume.assumeTrue;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.kooo.evcam.AppConfig;

import org.junit.Test;
import org.junit.runner.RunWith;


/**
 * Not a pass/fail test: shows the turn-signal side view on the centre screen with a drawn stand-in
 * for the surround camera (the emulator has none), in both styles and both sides, and saves a
 * log line for each so a script can take a screenshot with adb.
 */
@RunWith(AndroidJUnit4.class)
public class SideViewPopupCaptureTest {

    private static final int LANE_W = 640;
    private static final int LANE_H = 720;

    @Test
    public void captureStyles() throws Exception {
        Instrumentation inst = InstrumentationRegistry.getInstrumentation();
        Context ctx = inst.getTargetContext();
        assumeTrue(Settings.canDrawOverlays(ctx));
        AppConfig config = new AppConfig(ctx);
        String style = config.getSidePopupStyle();
        boolean straighten = config.isSidePopupStraighten();
        int fov = config.getSidePopupFov();
        int yaw = config.getSidePopupYaw();
        int pitch = config.getSidePopupPitch();
        int rollLeft = config.getSidePopupRollLeft();
        int rollRight = config.getSidePopupRollRight();
        // widest, straight back: most of the drawn stand-in scene is in the picture
        config.setSidePopupStraighten(false);
        config.setSidePopupFov(130);
        config.setSidePopupYaw(0);
        config.setSidePopupPitch(0);
        config.setSidePopupRollLeft(0);
        config.setSidePopupRollRight(0);

        Intent home = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(home);
        SystemClock.sleep(3000);

        try {
            for (String s : new String[] {"square", "wide"}) {
                config.setSidePopupStyle(s);
                for (int lane : new int[] {LaneCycle.LEFT, LaneCycle.RIGHT}) {
                    SideViewPopupView[] popup = new SideViewPopupView[1];
                    inst.runOnMainSync(() -> {
                        popup[0] = new SideViewPopupView(ctx, config);
                        popup[0].getTextureView().setSurfaceTextureListener(new Feeder());
                        popup[0].setSourceSize(new Size(LANE_W, LANE_H * 4));
                        popup[0].show(lane);
                    });
                    // UiAutomation's screenshot leaves overlay windows out: hold each state for
                    // `adb exec-out screencap`, announced in logcat
                    String name = "sideview-" + s + "-" + (lane == LaneCycle.LEFT ? "left" : "right");
                    SystemClock.sleep(1000);
                    android.util.Log.i("SideViewCapture", "ready " + name);
                    SystemClock.sleep(3000);
                    inst.runOnMainSync(() -> popup[0].detach());
                    SystemClock.sleep(300);
                }
            }
        } finally {
            config.setSidePopupStyle(style);
            config.setSidePopupStraighten(straighten);
            config.setSidePopupFov(fov);
            config.setSidePopupYaw(yaw);
            config.setSidePopupPitch(pitch);
            config.setSidePopupRollLeft(rollLeft);
            config.setSidePopupRollRight(rollRight);
        }
    }

    /** Fills the texture with four stacked lanes (front, rear, left, right) of a drawn road scene. */
    private static final class Feeder implements TextureView.SurfaceTextureListener {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
            st.setDefaultBufferSize(LANE_W, LANE_H * 4);
            Surface surface = new Surface(st);
            Canvas c = surface.lockCanvas(null);
            for (int i = 0; i < 4; i++) {
                c.save();
                c.translate(0, i * LANE_H);
                drawScene(c, i);
                c.restore();
            }
            surface.unlockCanvasAndPost(c);
            surface.release();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture st) {
        }
    }

    /** A side camera looking back: sky, a road going away, lane lines, a car in the next lane. */
    private static void drawScene(Canvas c, int lane) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, LANE_H * 0.45f,
                Color.rgb(120, 160, 205), Color.rgb(200, 215, 230), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, LANE_W, LANE_H * 0.45f, p);
        p.setShader(null);
        p.setColor(Color.rgb(70, 90, 70));
        c.drawRect(0, LANE_H * 0.38f, LANE_W, LANE_H * 0.45f, p);
        p.setColor(Color.rgb(60, 62, 66));
        c.drawRect(0, LANE_H * 0.45f, LANE_W, LANE_H, p);
        // lane lines converging to a vanishing point
        float vx = LANE_W * 0.5f;
        float vy = LANE_H * 0.45f;
        p.setColor(Color.rgb(235, 235, 235));
        for (float x : new float[] {-0.4f, 0.35f, 1.1f}) {
            Path path = new Path();
            path.moveTo(vx - 2, vy);
            path.lineTo(vx + 2, vy);
            path.lineTo(LANE_W * x + 14, LANE_H);
            path.lineTo(LANE_W * x - 14, LANE_H);
            path.close();
            c.drawPath(path, p);
        }
        // a car in the next lane
        p.setColor(lane == LaneCycle.LEFT ? Color.rgb(200, 40, 40) : Color.rgb(40, 90, 200));
        c.drawRoundRect(LANE_W * 0.12f, LANE_H * 0.55f, LANE_W * 0.42f, LANE_H * 0.75f, 18, 18, p);
        p.setColor(Color.rgb(30, 30, 35));
        c.drawRoundRect(LANE_W * 0.16f, LANE_H * 0.58f, LANE_W * 0.38f, LANE_H * 0.64f, 8, 8, p);
        c.drawCircle(LANE_W * 0.17f, LANE_H * 0.76f, 20, p);
        c.drawCircle(LANE_W * 0.37f, LANE_H * 0.76f, 20, p);
    }
}
