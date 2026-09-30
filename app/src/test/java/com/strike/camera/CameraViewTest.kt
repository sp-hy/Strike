package com.strike.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraViewTest {

    @Test
    fun fastCamViewsUseAFullFrameTile() {
        for (view in CameraView.entries) {
            val tiles = tilesOf(view)
            assertEquals(1, tiles.size)
            assertEquals(0f, tiles[0].sourceX, 0f)
            assertEquals(1f, tiles[0].sourceWidth, 0f)
            assertEquals(1f, tiles[0].destWidth, 0f)
            assertEquals(1f, tiles[0].destHeight, 0f)
        }
    }

    @Test
    fun frameSizeMatchesFastCamCanvas() {
        val whole = frameOf(CameraView.ALL)
        val one = frameOf(CameraView.FRONT)
        assertEquals(1920, whole.width)
        assertEquals(1300, whole.height)
        assertEquals(1920, one.width)
        assertEquals(1300, one.height)
    }

    @Test
    fun anUnknownAngleFallsBackToTheRoadAhead() {
        assertEquals(CameraView.FRONT, CameraView.of(null))
        assertEquals(CameraView.FRONT, CameraView.of("sideways"))
        assertEquals(CameraView.ALL, CameraView.of("all"))
    }

    @Test
    fun liveScalesTheCanvasIntoMacroblocks() {
        val live = liveFrameOf(CameraView.ALL)
        assertEquals(0, live.width % 16)
        assertEquals(0, live.height % 16)
        assertTrue(live.width <= 1280)
        assertTrue(live.height <= 960)
    }

    @Test
    fun scalingKeepsWholeMacroblocks() {
        val fit = fitted(Frame(1000, 700), 640, 480)
        assertEquals(0, fit.width % 16)
        assertEquals(0, fit.height % 16)
    }

    private fun assertTrue(condition: Boolean) {
        org.junit.Assert.assertTrue(condition)
    }
}
