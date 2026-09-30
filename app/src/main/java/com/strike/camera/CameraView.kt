package com.strike.camera

// FastCam delivers a full 1920×1300 canvas per view (single or composed mosaic).
enum class CameraView(val id: String) {
    ALL("all"),
    FRONT("front"),
    RIGHT("right"),
    REAR("rear"),
    LEFT("left");

    companion object {
        fun of(id: String?): CameraView = values().firstOrNull { it.id == id } ?: FRONT
    }
}

/** Full-frame tile — FastCam already crops/composes; no strip slicing. */
class Tile(
    val sourceX: Float,
    val sourceWidth: Float,
    val destX: Float,
    val destY: Float,
    val destWidth: Float,
    val destHeight: Float
)

fun tilesOf(view: CameraView): List<Tile> {
    // Identity mapping; view selection is done in FastCamNative.setActiveCamera.
    return listOf(Tile(0f, 1f, 0f, 0f, 1f, 1f))
}

class Frame(val width: Int, val height: Int)

fun frameOf(view: CameraView, frameWidth: Int = 1920, frameHeight: Int = 1300): Frame =
    Frame(frameWidth, frameHeight)

private const val LIVE_MAX_WIDTH = 1280
private const val LIVE_MAX_HEIGHT = 960
private const val MACROBLOCK = 16

fun liveFrameOf(view: CameraView, frameWidth: Int = 1920, frameHeight: Int = 1300): Frame =
    fitted(frameOf(view, frameWidth, frameHeight), LIVE_MAX_WIDTH, LIVE_MAX_HEIGHT)

internal fun fitted(frame: Frame, maxWidth: Int, maxHeight: Int): Frame {
    val scale = minOf(
        maxWidth.toFloat() / frame.width,
        maxHeight.toFloat() / frame.height,
        1f
    )
    if (scale == 1f) return frame
    return Frame(blocks(frame.width * scale), blocks(frame.height * scale))
}

/** An encoder codes in whole macroblocks, so a stray pixel becomes a green edge. */
private fun blocks(value: Float): Int =
    maxOf(MACROBLOCK, (Math.round(value / MACROBLOCK) * MACROBLOCK))
