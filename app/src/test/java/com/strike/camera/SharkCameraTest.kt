package com.strike.camera

import com.strike.core.ScratchPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharkCameraTest {

    @Test
    fun sharkFrameMatchesFastCamCanvas() {
        assertEquals(1920, SHARK_FRAME.width)
        assertEquals(1300, SHARK_FRAME.height)
    }

    @Test
    fun sharkCamsAreFrontRightRearLeft() {
        assertEquals("8,9,5,4", SHARK_CAMS)
    }

    @Test
    fun aisBytesMatchLogicalFastCamIds() {
        assertEquals(4, aisByteForViewMode(CameraView.ALL))
        assertEquals(0, aisByteForViewMode(CameraView.FRONT))
        assertEquals(1, aisByteForViewMode(CameraView.RIGHT))
        assertEquals(2, aisByteForViewMode(CameraView.REAR))
        assertEquals(3, aisByteForViewMode(CameraView.LEFT))
    }

    @Test
    fun captureOnlyRunsFromTheApkLibDir() {
        assertFalse(ScratchPaths.isExecCapableLocation("/storage/emulated/0/Android/data/com.strike/files/daemon/bin"))
        assertFalse(ScratchPaths.isExecCapableLocation("/data/local/tmp/fast_cam_capture"))
        assertTrue(ScratchPaths.isExecCapableLocation("/data/app/~~x/com.strike-y/lib/arm64/libfast_cam_capture.so"))
    }
}
