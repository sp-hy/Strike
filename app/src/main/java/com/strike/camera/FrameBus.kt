package com.strike.camera

import android.os.SystemClock
import android.view.Surface
import com.strike.core.Diagnostics
import com.strike.core.systemProperty
import com.strike.daemon.DaemonLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "FrameBus"
private const val FRAME_WAIT_MS = 100

/** Every FastCam camera senses at this rate. */
const val CAPTURE_FPS = 30

/** Announced rate when no consumer needs the full sensor rate; halves IPC and pump work. */
private const val HALF_RATE_FPS = CAPTURE_FPS / 2

/** An anchor camera that has been silent this long hands the tick to the next one. */
private const val ANCHOR_STALE_MS = 200L
private const val STATS_EVERY_MS = 10_000L
private const val LANE_JOIN_MS = 1_000L

/** "0" forces every consumer onto the CPU painter, for A/B measurement. */
private const val GPU_PROPERTY = "debug.strike.gpu"

class Consumer(
    val name: String,
    val surface: Surface,
    val view: CameraView,
    val frame: Frame,
    fps: Int = CAPTURE_FPS,
    /** Encoder input surfaces take GPU frames with exact timestamps; ImageReaders stay on the CPU. */
    val gpu: Boolean = false
) {
    val fps: Int = fps.coerceIn(1, CAPTURE_FPS)

    // Touched only on the bus thread; negative until the first tick seeds it.
    internal var phase = -1

    internal val painted = AtomicInteger()
    internal val paintNs = AtomicLong()
    internal val superseded = AtomicInteger()
}

/**
 * FastCam frame fan-out: stage IPC frames, then have each consumer's own paint lane
 * draw it at the consumer's rate, on the anchor camera's cadence.
 */
class FrameBus(val frameWidth: Int, val frameHeight: Int) {

    private val consumers = CopyOnWriteArrayList<Consumer>()
    private val lanes = ConcurrentHashMap<String, Lane>()

    private var thread: Thread? = null

    @Volatile private var running = false
    @Volatile private var ready = false
    @Volatile private var frames = 0L
    @Volatile private var frameAtMs = 0L
    @Volatile private var openedAtMs = 0L
    @Volatile private var changed = true

    private var needs = -1
    private var rate = CAPTURE_FPS
    private var copying = true
    private val seenAtMs = LongArray(4)
    private val received = IntArray(4)
    private var tickAtMs = 0L
    private var ticks = 0
    private var statsAtMs = 0L

    val frameCount: Long get() = frames

    val quietForMs: Long
        get() {
            val since = if (frameAtMs == 0L) openedAtMs else frameAtMs
            return if (since == 0L) 0L else System.currentTimeMillis() - since
        }

    /** Starts the pump thread; false when FastCam's JNI is unavailable. */
    fun start(): Boolean {
        val gate = Object()
        var failed = false
        thread = Thread({
            val opened = try {
                open()
            } catch (t: Throwable) {
                DaemonLog.e(TAG, "FastCam bus refused: ${t.javaClass.simpleName}: ${t.message}")
                false
            }
            synchronized(gate) {
                failed = !opened
                ready = opened
                gate.notifyAll()
            }
            if (opened) spin()
        }, "framebus").also { it.start() }

        synchronized(gate) {
            while (!failed && !ready) gate.wait(5_000)
        }
        return !failed
    }

    fun stop() {
        running = false
        thread?.join(2_000)
        thread = null
        ready = false
        for (name in lanes.keys.toList()) lanes.remove(name)?.close()
    }

    fun add(consumer: Consumer) {
        remove(consumer.name)
        val gpu = consumer.gpu && systemProperty(GPU_PROPERTY) != "0"
        lanes[consumer.name] = Lane(consumer, gpu)
        consumers.add(consumer)
        changed = true
    }

    // Returns once the lane has let go of the surface, so the owner can release it.
    fun remove(name: String) {
        consumers.removeAll { it.name == name }
        lanes.remove(name)?.close()
        changed = true
    }

    private fun open(): Boolean {
        if (!FastCamNative.ensureLoaded()) {
            DaemonLog.e(TAG, "FastCam JNI is not loaded")
            return false
        }
        openedAtMs = System.currentTimeMillis()
        statsAtMs = SystemClock.elapsedRealtime()
        FastCamNative.takeCopyNanos()
        rate = CAPTURE_FPS
        FastCamNative.setRate(CAPTURE_FPS)
        running = true
        return true
    }

    private fun spin() {
        while (running) {
            if (changed) {
                changed = false
                syncNeeds()
                syncRate()
                copy(consumers.isNotEmpty())
            }
            val cam = FastCamNative.pumpFrame(FRAME_WAIT_MS)
            val now = SystemClock.elapsedRealtime()
            if (cam < 0) {
                if (!FastCamNative.connected()) Thread.sleep(50)
                report(now)
                continue
            }
            val arrivedNs = System.nanoTime()
            frameAtMs = System.currentTimeMillis()
            frames++
            seenAtMs[cam] = now
            received[cam]++
            if (isTick(cam, now)) {
                tickAtMs = now
                ticks++
                paint(arrivedNs)
                copy(consumers.any { it.phase < 0 || it.phase + it.fps >= rate })
            }
            report(now)
        }
    }

    private fun copy(wanted: Boolean) {
        if (wanted == copying) return
        copying = wanted
        FastCamNative.setCopying(wanted)
    }

    // Stage only the views someone is painting; an idle bus still stages the mosaic.
    private fun syncNeeds() {
        var mask = 0
        for (consumer in consumers) {
            mask = mask or (1 shl aisByteForViewMode(consumer.view))
        }
        if (mask == 0) mask = NEED_MOSAIC
        if (mask == needs) return
        needs = mask
        FastCamNative.setNeeds(mask)
    }

    // Encoders need evenly spaced frames, so they keep 30 unless their rate divides 15.
    private fun syncRate() {
        val half = consumers.all {
            it.fps <= HALF_RATE_FPS && (!it.gpu || HALF_RATE_FPS % it.fps == 0)
        }
        val wanted = if (half) HALF_RATE_FPS else CAPTURE_FPS
        if (wanted == rate) return
        rate = wanted
        for (consumer in consumers) consumer.phase = -1
        FastCamNative.setRate(wanted)
        DaemonLog.d(TAG, "cameras announce ${wanted}fps")
    }

    // Paint once per anchor frame so every consumer follows one camera's cadence.
    private fun isTick(cam: Int, now: Long): Boolean {
        val anchor = anchor(now)
        if (anchor >= 0) return cam == anchor
        return now - tickAtMs >= 1000L / rate
    }

    private fun anchor(now: Long): Int {
        for (cam in 0 until 4) {
            if (!needsCamera(cam)) continue
            val seen = seenAtMs[cam]
            if (seen != 0L && now - seen <= ANCHOR_STALE_MS) return cam
        }
        return -1
    }

    private fun needsCamera(cam: Int): Boolean =
        needs and NEED_MOSAIC != 0 || needs and (1 shl cam) != 0

    private fun paint(arrivedNs: Long) {
        for (consumer in consumers) {
            if (consumer.phase < 0) consumer.phase = rate - consumer.fps
            consumer.phase += consumer.fps
            if (consumer.phase < rate) continue
            consumer.phase -= rate
            lanes[consumer.name]?.post(arrivedNs)
        }
    }

    private fun report(now: Long) {
        val elapsed = now - statsAtMs
        if (elapsed < STATS_EVERY_MS) return
        statsAtMs = now
        val seconds = elapsed / 1000.0
        val copyMs = FastCamNative.takeCopyNanos() / 1_000_000.0
        val line = StringBuilder("[perf] in=")
        line.append(received.joinToString("/") { rate(it, seconds) })
        line.append(" ticks=").append(rate(ticks, seconds))
        line.append(" copy=").append(ms(copyMs / seconds)).append("ms/s")
        for (consumer in consumers) {
            val painted = consumer.painted.getAndSet(0)
            val paintMs = consumer.paintNs.getAndSet(0L) / 1_000_000.0
            val superseded = consumer.superseded.getAndSet(0)
            val average = if (painted > 0) paintMs / painted else 0.0
            val kind = if (lanes[consumer.name]?.onGpu == true) "gpu" else "cpu"
            line.append(' ').append(consumer.name).append('=')
                .append(rate(painted, seconds)).append("fps@")
                .append(ms(average)).append("ms/").append(kind)
            if (superseded > 0) line.append(" dropped=").append(superseded)
        }
        if (Diagnostics.perfLogs()) DaemonLog.d(TAG, line.toString())
        received.fill(0)
        ticks = 0
    }

    private fun rate(count: Int, seconds: Double): String = "%.0f".format(count / seconds)

    private fun ms(value: Double): String = "%.1f".format(value)

    // One thread per consumer, so a slow encoder never delays the bus or another consumer.
    private class Lane(private val consumer: Consumer, private val gpu: Boolean) {

        private val gate = Object()
        private var pendingNs = NONE
        private var open = true
        private var faulted = false

        @Volatile var onGpu = false
            private set

        private val thread = Thread({ run() }, "paint-${consumer.name}").also {
            it.isDaemon = true
            it.start()
        }

        // Latest wins: a frame the lane has not started yet is replaced by the newer one.
        fun post(arrivedNs: Long) = synchronized(gate) {
            if (pendingNs != NONE) consumer.superseded.incrementAndGet()
            pendingNs = arrivedNs
            gate.notify()
        }

        fun close() {
            synchronized(gate) {
                open = false
                gate.notify()
            }
            if (Thread.currentThread() !== thread) thread.join(LANE_JOIN_MS)
        }

        private fun take(): Long? = synchronized(gate) {
            while (open && pendingNs == NONE) gate.wait()
            if (!open) return null
            val arrived = pendingNs
            pendingNs = NONE
            arrived
        }

        private fun run() {
            val gl = if (gpu) FastCamNative.glCreate(consumer.surface) else 0L
            onGpu = gl != 0L
            if (gpu) {
                if (onGpu) DaemonLog.d(TAG, "${consumer.name} paints on the GPU")
                else DaemonLog.w(TAG, "${consumer.name} could not start the GPU painter; using the CPU")
            }
            var first = true
            try {
                while (true) {
                    val arrived = take() ?: break
                    val started = System.nanoTime()
                    val ok = try {
                        if (gl != 0L) FastCamNative.glDraw(gl, consumer.view, arrived)
                        else consumer.surface.isValid && FastCamNative.draw(consumer.surface, consumer.view)
                    } catch (t: Throwable) {
                        fault(t.message ?: "draw failed")
                        false
                    }
                    consumer.paintNs.addAndGet(System.nanoTime() - started)
                    if (ok) {
                        consumer.painted.incrementAndGet()
                        if (first) DaemonLog.d(TAG, "${consumer.name} took its first frame")
                        first = false
                    } else {
                        fault("would not take the frame")
                    }
                }
            } finally {
                if (gl != 0L) FastCamNative.glDestroy(gl)
            }
        }

        private fun fault(what: String) {
            if (faulted) return
            faulted = true
            DaemonLog.e(TAG, "${consumer.name} $what")
        }

        private companion object {
            const val NONE = Long.MIN_VALUE
        }
    }
}
