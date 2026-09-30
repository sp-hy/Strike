package com.strike.camera

import android.view.Surface
import com.strike.core.Logs
import com.strike.daemon.DaemonLog

private const val TAG = "FastCam"

/** Mosaic bit for [FastCamNative.setNeeds]; bits 0-3 are the single camera views. */
const val NEED_MOSAIC = 1 shl 4

/**
 * JNI bridge to libfast_cam_client.so. Load order: c++_shared → fast_cam_client → strike.
 */
object FastCamNative {

    @Volatile
    private var loaded = false

    fun warm() {
        ensureLoaded()
    }

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            try {
                System.loadLibrary("c++_shared")
            } catch (_: UnsatisfiedLinkError) {
                // Already loaded or packaged under another name.
            }
            System.loadLibrary("fast_cam_client")
            System.loadLibrary("strike")
            loaded = true
            Logs.d(TAG, "FastCam JNI loaded via System.loadLibrary")
            true
        } catch (t: UnsatisfiedLinkError) {
            DaemonLog.w(TAG, "FastCam JNI load failed: ${t.message}")
            loaded = false
            false
        }
    }

    fun tryLoadFrom(nativeLibDir: String?): Boolean {
        if (ensureLoaded()) return true
        if (nativeLibDir.isNullOrBlank()) return false
        return try {
            System.load("$nativeLibDir/libc++_shared.so")
            System.load("$nativeLibDir/libfast_cam_client.so")
            System.load("$nativeLibDir/libstrike.so")
            loaded = true
            DaemonLog.d(TAG, "FastCam JNI loaded from $nativeLibDir")
            true
        } catch (t: UnsatisfiedLinkError) {
            DaemonLog.w(TAG, "FastCam absolute load failed: ${t.message}")
            loaded = false
            false
        }
    }

    fun openSocket(socket: String = "@fast_cam.sock"): Boolean {
        if (!ensureLoaded()) return false
        return try {
            nativeConnect(socket)
        } catch (t: UnsatisfiedLinkError) {
            loaded = false
            if (!ensureLoaded()) return false
            nativeConnect(socket)
        }
    }

    fun closeSocket() {
        if (!loaded) return
        try {
            nativeDisconnect()
        } catch (_: UnsatisfiedLinkError) {
            loaded = false
        }
    }

    fun connected(): Boolean = loaded && try {
        nativeIsConnected()
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    /** Which views to stage on each frame; see [NEED_MOSAIC]. */
    fun setNeeds(mask: Int) {
        if (!ensureLoaded()) return
        nativeSetNeeds(mask)
    }

    /** Frames per second each camera announces (1..30); survives capture restarts. */
    fun setRate(fps: Int) {
        if (!ensureLoaded()) return
        nativeSetRate(fps)
    }

    /** Off between ticks that paint nothing; staged frames stay valid. */
    fun setCopying(copying: Boolean) {
        if (!ensureLoaded()) return
        nativeSetCopying(copying)
    }

    /** Waits for one camera frame and stages it; returns its logical camera (0-3) or -1. */
    fun pumpFrame(timeoutMs: Int): Int {
        if (!ensureLoaded()) return -1
        return nativePump(timeoutMs)
    }

    fun draw(surface: Surface, view: CameraView): Boolean {
        if (!ensureLoaded()) return false
        return nativeDrawToWindow(surface, aisByteForViewMode(view))
    }

    /** GPU renderer bound to [surface]; create, draw and destroy on one thread. Returns 0 on failure. */
    fun glCreate(surface: Surface): Long {
        if (!ensureLoaded()) return 0L
        return nativeGlCreate(surface)
    }

    fun glDraw(handle: Long, view: CameraView, ptsNs: Long): Boolean =
        nativeGlDraw(handle, aisByteForViewMode(view), ptsNs)

    fun glDestroy(handle: Long) {
        nativeGlDestroy(handle)
    }

    /** Time spent copying camera frames since the last call. */
    fun takeCopyNanos(): Long = if (loaded) nativeTakeCopyNanos() else 0L

    fun width(): Int = if (loaded) nativeFrameWidth() else 1920

    fun height(): Int = if (loaded) nativeFrameHeight() else 1300

    @JvmStatic private external fun nativeConnect(path: String?): Boolean
    @JvmStatic private external fun nativeDisconnect()
    @JvmStatic private external fun nativeIsConnected(): Boolean
    @JvmStatic private external fun nativeSetNeeds(mask: Int)
    @JvmStatic private external fun nativeSetRate(fps: Int)
    @JvmStatic private external fun nativeSetCopying(copying: Boolean)
    @JvmStatic private external fun nativePump(timeoutMs: Int): Int
    @JvmStatic private external fun nativeGlCreate(surface: Surface): Long
    @JvmStatic private external fun nativeGlDraw(handle: Long, view: Int, ptsNs: Long): Boolean
    @JvmStatic private external fun nativeGlDestroy(handle: Long)
    @JvmStatic private external fun nativeTakeCopyNanos(): Long
    @JvmStatic private external fun nativeDrawToWindow(surface: Surface, view: Int): Boolean
    @JvmStatic private external fun nativeFrameWidth(): Int
    @JvmStatic private external fun nativeFrameHeight(): Int
}
