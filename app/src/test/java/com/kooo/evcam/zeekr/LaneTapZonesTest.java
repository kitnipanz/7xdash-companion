package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 按键模式切出来的几块。
 *
 * <p>位置本身就是答案（上前、下后、左左、右右）：位置错了会让人凭肌肉记忆点错。</p>
 */
public class LaneTapZonesTest {

    private static final int WIDTH = 800;
    private static final int HEIGHT = 400;

    private static int at(float x, float y) {
        return LaneTapZones.laneAt(x, y, WIDTH, HEIGHT, false);
    }

    @Test
    public void eachEdgeMidpointBelongsToItsDirection() {
        assertEquals(LaneCycle.FRONT, at(400, 20));
        assertEquals(LaneCycle.REAR, at(400, 380));
        assertEquals(LaneCycle.LEFT, at(20, 200));
        assertEquals(LaneCycle.RIGHT, at(780, 200));
    }

    /** 分界是窗口自己的对角线，不是一个正方形的：扁窗口里靠左一点、偏上的点仍然算前。 */
    @Test
    public void diagonalsFollowTheWindowShape() {
        // 800×400 里 (200, 90)：左上—右下那条线在 x=200 处 y=100，点在它上方；
        // 按 45° 的线算会落进「左」
        assertEquals(LaneCycle.FRONT, at(200, 90));
        assertEquals(LaneCycle.LEFT, at(200, 110));
        assertEquals(LaneCycle.RIGHT, at(600, 110));
        assertEquals(LaneCycle.REAR, at(600, 310));
    }

    /** 只显示前后视：只分上下两半，左右两边的点也落在前或后，没有死区。 */
    @Test
    public void frontRearOnlySplitsTopAndBottom() {
        assertEquals(LaneCycle.FRONT, LaneTapZones.laneAt(20, 150, WIDTH, HEIGHT, true));
        assertEquals(LaneCycle.FRONT, LaneTapZones.laneAt(780, 199, WIDTH, HEIGHT, true));
        assertEquals(LaneCycle.REAR, LaneTapZones.laneAt(20, 250, WIDTH, HEIGHT, true));
        assertEquals(LaneCycle.REAR, LaneTapZones.laneAt(780, 201, WIDTH, HEIGHT, true));
    }

    @Test
    public void noSizeMeansNoZone() {
        assertEquals(-1, LaneTapZones.laneAt(1, 1, 0, 400, false));
        assertEquals(-1, LaneTapZones.laneAt(1, 1, 800, 0, true));
    }

    /** 画出来的轮廓和点中的判定是同一块：轮廓的中心要落回同一路。 */
    @Test
    public void outlineCentreHitsItsOwnLane() {
        int[] lanes = {LaneCycle.FRONT, LaneCycle.REAR, LaneCycle.LEFT, LaneCycle.RIGHT};
        for (int lane : lanes) {
            assertEquals(lane, laneAtCentreOf(LaneTapZones.outline(lane, WIDTH, HEIGHT, false),
                    false));
        }
        for (int lane : new int[]{LaneCycle.FRONT, LaneCycle.REAR}) {
            assertEquals(lane, laneAtCentreOf(LaneTapZones.outline(lane, WIDTH, HEIGHT, true),
                    true));
        }
    }

    private static int laneAtCentreOf(float[] points, boolean frontRearOnly) {
        float x = 0f;
        float y = 0f;
        int n = points.length / 2;
        for (int i = 0; i < n; i++) {
            x += points[2 * i];
            y += points[2 * i + 1];
        }
        return LaneTapZones.laneAt(x / n, y / n, WIDTH, HEIGHT, frontRearOnly);
    }
}
