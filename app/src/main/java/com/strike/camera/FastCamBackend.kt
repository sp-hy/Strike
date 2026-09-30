package com.strike.camera

import com.strike.core.ScratchPaths
import com.strike.core.systemProperty
import com.strike.daemon.DaemonLog
import java.io.File

private const val TAG = "FastCam"
private const val SOCKET = "@fast_cam.sock"

/**
 * Spawns libfast_cam_capture.so from the APK native lib dir and connects the JNI client.
 * Never exec from emulated Android/data (media_rw).
 */
class FastCamBackend {

    @Volatile private var capture: Process? = null
    @Volatile private var nativeLibDir: String? = null
    @Volatile private var running = false

    fun setNativeLibDir(dir: String?) {
        nativeLibDir = dir
    }

    fun open(frameRateFps: Int = 30): Boolean {
        close()
        ScratchPaths.syncFromEnv()
        ScratchPaths.ensureDir()
        if (!FastCamNative.tryLoadFrom(nativeLibDir) && !FastCamNative.ensureLoaded()) {
            return fail("FastCam JNI is not available")
        }
        val binary = resolveCaptureBinary() ?: return fail("libfast_cam_capture.so not found in an executable location")
        killStaleCapture()
        val cams = sharkCams()
        val scratch = ScratchPaths.getDir()
        return try {
            val pb = ProcessBuilder(
                binary.absolutePath,
                "--cams", cams,
                "--socket", SOCKET,
                "--time", "0"
            )
            pb.redirectErrorStream(true)
            val env = pb.environment()
            env["STRIKE_SCRATCH"] = scratch
            env["OVERDRIVE_SCRATCH"] = scratch
            env["TMPDIR"] = scratch
            env["LD_LIBRARY_PATH"] =
                "/vendor/lib64:/system/lib64:$scratch:${binary.parentFile?.absolutePath.orEmpty()}"
            capture = pb.start()
            // Drain stdout so the child cannot block on a full pipe.
            Thread({
                try {
                    capture?.inputStream?.bufferedReader()?.use { reader ->
                        while (reader.readLine() != null) { /* discard */ }
                    }
                } catch (_: Exception) {
                }
            }, "fast-cam-out").also { it.isDaemon = true; it.start() }

            var connected = false
            repeat(40) {
                if (FastCamNative.openSocket(SOCKET)) {
                    connected = true
                    return@repeat
                }
                Thread.sleep(100)
            }
            if (!connected) {
                close()
                return fail("could not connect to $SOCKET")
            }
            running = true
            DaemonLog.d(TAG, "capture ${binary.absolutePath} --cams $cams @ $SOCKET (${frameRateFps}fps target)")
            true
        } catch (t: Throwable) {
            close()
            fail("capture failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun close() {
        running = false
        FastCamNative.closeSocket()
        capture?.let { child ->
            try {
                child.destroy()
                child.waitFor()
            } catch (_: Exception) {
            }
        }
        capture = null
        killStaleCapture()
    }

    val isOpen: Boolean get() = running && FastCamNative.connected()

    private fun resolveCaptureBinary(): File? {
        val binary = nativeLibDir?.let { File(it, "libfast_cam_capture.so") } ?: return null
        if (!binary.isFile) return null
        if (!ScratchPaths.isExecCapableLocation(binary.absolutePath)) {
            DaemonLog.w(TAG, "refusing non-executable location ${binary.absolutePath}")
            return null
        }
        return binary
    }

    private fun killStaleCapture() {
        try {
            // The bracket keeps pkill from matching this shell's own command line. AIS needs a
            // moment to release a killed child's cameras before a new child can open them.
            ProcessBuilder("sh", "-c", "pkill -9 -f '[f]ast_cam_capture' 2>/dev/null && sleep 1; true")
                .start().waitFor()
        } catch (_: Exception) {
        }
    }

    private fun fail(reason: String): Boolean {
        DaemonLog.e(TAG, reason)
        return false
    }
}

/** Logical FastCam ids: 0–3 surround, 4 = 2×2 mosaic (prefer for live ALL). */
fun aisByteForViewMode(view: CameraView): Int = when (view) {
    CameraView.ALL -> 4
    CameraView.FRONT -> 0
    CameraView.RIGHT -> 1
    CameraView.REAR -> 2
    CameraView.LEFT -> 3
}

/** Shark FastCam canvas (single camera or 2×2 mosaic). */
val SHARK_FRAME = Frame(1920, 1300)

/** Wait this long for the first FastCam frame before reopening. */
const val CAMERA_FIRST_FRAME_MS = 25_000L

/** AIS camera ids for front, right, rear, left. */
const val SHARK_CAMS = "8,9,5,4"

/** [SHARK_CAMS] unless a debug property remaps them. */
fun sharkCams(): String {
    val override = systemProperty("persist.overdrive.cams") ?: systemProperty("persist.strike.cams")
    return if (override.isNullOrBlank()) SHARK_CAMS else override.trim()
}

fun cameraStack(): String {
    val vehicle = systemProperty("ro.vehicle.type") ?: "empty"
    return "FastCam vehicle.type=$vehicle cams=${sharkCams()}"
}
