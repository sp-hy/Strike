package com.strike.daemon

import android.os.Looper
import android.os.Handler
import android.os.Process
import android.os.SystemClock
import com.strike.camera.CAMERA_FIRST_FRAME_MS
import com.strike.camera.FastCamBackend
import com.strike.camera.FastCamNative
import com.strike.camera.CameraView
import com.strike.camera.FrameBus
import com.strike.camera.LIVE_BITRATE_BPS
import com.strike.camera.LIVE_FRAME_RATE_FPS
import com.strike.camera.LiveStreamer
import com.strike.camera.LiveQuality
import com.strike.camera.SHARK_FRAME
import com.strike.camera.cameraStack
import com.strike.camera.sharkCams
import com.strike.core.Config
import com.strike.core.ScratchPaths
import com.strike.recording.ClipStore
import com.strike.recording.MB
import com.strike.recording.Reapable
import com.strike.recording.Recorder
import com.strike.recording.RecordingMode
import com.strike.recording.RecordingOptions
import com.strike.recording.RecordingSettings
import com.strike.recording.Retention
import com.strike.recording.ensureDir
import com.strike.recording.remount
import com.strike.recording.storageUuid
import com.strike.recording.shouldRecord
import com.strike.recording.driveWanted
import com.strike.recording.sentryMode
import com.strike.surveillance.EVENT_CLIP_CAP_MS
import com.strike.surveillance.EventStore
import com.strike.surveillance.Flag
import com.strike.surveillance.Mark
import com.strike.surveillance.RedScreen
import com.strike.surveillance.Sentry
import com.strike.surveillance.SurveillanceSettings
import com.strike.surveillance.eventInProgress
import com.strike.surveillance.surveillanceReason
import com.strike.server.DashboardControl
import com.strike.vehicle.VehicleSnapshot
import com.strike.vehicle.VehicleTelemetry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

private const val TAG = "Daemon"
private const val SUPERVISE_EVERY_MS = 1_000L
private const val REAP_EVERY_MS = 30_000L
private const val RETRY_AFTER_MS = 10_000L

private const val STALL_MS = 6_000L
private const val LOST_CAPTURE_MS = 4_000L
private const val CAMERA_SETTLE_MS = 1_500L

private enum class SentryMode { OFF, SMART, CONTINUOUS }

private data class Target(
    val dir: File,
    val mode: RecordingMode,
    val options: RecordingOptions
)

// Camera capture runs in app_process under shell UID 2000.
object CameraDaemon {

    private val lock = SingletonLock()
    private val startedAtMs = System.currentTimeMillis()

    @Volatile
    private var alive = true

    @Volatile
    private var wanted = false

    @Volatile
    private var liveWanted = false

    @Volatile
    private var liveView = CameraView.FRONT

    @Volatile
    private var liveBitrateBps = LIVE_BITRATE_BPS

    @Volatile
    private var sentryMode = SentryMode.OFF

    @Volatile
    private var sentryReason = "Starting vehicle monitoring"

    private var vehicle: VehicleTelemetry? = null
    private val acc = AccMonitor(read = {
        // BYD Auto IPC only works from the app UID (Open-DiKey). Shell uid 2000
        // always gets SecurityException — rely on Triggers pushing snapshots.
        if (Process.myUid() == 2000) return@AccMonitor null
        val telemetry = vehicle ?: DaemonContext.get()?.let { context ->
            VehicleTelemetry(context) { DaemonLog.w("ACC", it) }
        }
        vehicle = telemetry
        telemetry?.parkingSnapshot()
    })

    private val relay = PacketRelay()
    private val audio = AudioIngest()
    private val streamer = LiveStreamer(relay)
    private val camera = FastCamBackend()
    private val panel = ParkedPanel()
    private val panelLease = PanelLease(File(PANEL_LOCK_PATH), camera = true)
    private val screen = RedScreen(panel) {
        ParkedRails.isHeld || (onlinePanelHeld && ParkedRails.onlineHeld())
    }
    @Volatile private var panelReady = false
    @Volatile private var onlinePanelHeld = false
    private var sentry: Sentry? = null
    private var bus: FrameBus? = null
    private var server: CommandServer? = null
    private var supervisor: Thread? = null
    private var recorder: Recorder? = null
    private var target: Target? = null
    private var flagged: Flag? = null
    private val marked = ArrayList<Mark>()
    private var cameraOpenedAtMs = 0L
    private var cameraRetryAtMs = 0L
    private var failedAtMs = 0L
    private var remountedAtMs = 0L
    private var reapedAtMs = 0L
    private val reaping = AtomicBoolean(false)
    private var clips = 0

    @JvmStatic
    fun main(args: Array<String>) {
        DaemonLog.watchCrashes()
        DaemonLog.d(TAG, "starting as uid ${Process.myUid()}, pid ${Process.myPid()}")
        ScratchPaths.syncFromEnv()
        ScratchPaths.ensureDir()
        // A duplicate daemon must stop its watchdog without clearing the active daemon's lock.
        if (!lock.take()) exitProcess(EXIT_ALREADY_RUNNING)
        DaemonLog.d(TAG, "camera stack: " + cameraStack())
        Looper.prepareMainLooper()
        DaemonFonts.install()
        val nativeDir = args.firstOrNull()
        camera.setNativeLibDir(nativeDir)
        FastCamNative.tryLoadFrom(nativeDir)
        screen.apkPath = args.getOrNull(1)
        panelReady = panelLease.acquire()
        sentry = Sentry(args.getOrNull(1), screen)
        vehicle = if (Process.myUid() == 2000) {
            DaemonLog.d(TAG, "skipping local BYD telemetry under shell uid; app Triggers supply ACC")
            null
        } else {
            DaemonContext.get()?.let { context ->
                VehicleTelemetry(context) { DaemonLog.w("ACC", it) }
            }
        }
        wanted = shouldRecord(Config.getString(RecordingSettings.MODE, RecordingSettings.fallback(RecordingSettings.MODE)), null)
        // SIGTERM can finalize an open clip; SIGKILL and power loss cannot.
        Runtime.getRuntime().addShutdownHook(Thread({ finish() }, "last-clip"))
        val commands = CommandServer(::answer)
        server = commands
        supervisor = Thread({ supervise() }, "supervisor").also { it.isDaemon = true; it.start() }
        Thread({ commands.serveForever() }, "commands").also { it.isDaemon = true }.start()
        Thread({ relay.serveForever() }, "packets").also { it.isDaemon = true }.start()
        Thread({ audio.serveForever() }, "audio").also { it.isDaemon = true }.start()
        Thread({ watchVehicle() }, "vehicle").also { it.isDaemon = true }.start()
        Looper.loop()
        lock.release()
    }

    private fun answer(command: JSONObject): JSONObject = when (command.optString("cmd")) {
        "status" -> status()
        "vehicle" -> {
            acc.fromApp(VehicleSnapshot(
                null, null, null, null, null,
                if (command.has("gear")) command.optString("gear") else null,
                if (command.has("on")) command.optBoolean("on") else null,
                if (command.has("locked")) command.optBoolean("locked") else null
            ))
            updateVehicle()
            vehicleReply(acc.snapshot())
        }
        "acc" -> {
            acc.edge(command.optBoolean("on"))
            updateVehicle()
            ok()
        }
        "screen.preview" -> {
            if (!panelReady || !alive) failed("The parked display is starting")
            else {
                val message = command.optString("message")
                val seconds = command.optInt("seconds", 5)
                Thread({ screen.show(message, seconds, forced = true) }, "deterrent").start()
                ok()
            }
        }
        "power.parked" -> {
            if (panelReady && ParkedRails.onlineHeld()) onlinePanelHeld = true
            if (panelReady && screen.parkedWake { alive && panelReady && ParkedRails.onlineHeld() }) ok()
            else failed("The parked display is not ready")
        }
        "live.start" -> {
            liveView = CameraView.of(command.optString("view"))
            liveBitrateBps = command.optInt("bitrateBps", LIVE_BITRATE_BPS)
                .takeIf { bitrate -> LiveQuality.entries.any { it.bitrateBps == bitrate } } ?: LIVE_BITRATE_BPS
            liveWanted = true
            ok()
        }
        "live.stop" -> {
            liveWanted = false
            ok()
        }
        "shutdown" -> {
            alive = false
            Thread({ shutdown() }, "shutdown").start()
            ok()
        }
        else -> failed("no command named ${command.optString("cmd")}")
    }

    private fun status(): JSONObject {
        val held = recorder
        val watching = sentry
        val payload = ok()
        payload.put("cameras", cameras())
        payload.put("recording", held != null && held.isRecording)
        payload.put("clip", held?.clip ?: JSONObject.NULL)
        payload.put("clipMs", if (held?.clip == null) 0 else System.currentTimeMillis() - held.clipStartedAtMs)
        payload.put("clips", clips)
        payload.put("writing", target?.mode?.name?.lowercase() ?: JSONObject.NULL)
        payload.put("sentry", sentryMode.name.lowercase())
        payload.put("sentryReason", sentryReason)
        payload.put("accOn", acc.snapshot()?.accOn ?: JSONObject.NULL)
        payload.put("armed", watching != null && watching.isArmed)
        payload.put("confirming", watching != null && watching.isConfirming)
        payload.put("events", watching?.events ?: 0)
        payload.put("deterrent", screen.isShowing)
        payload.put("rails", ParkedRails.isHeld)
        payload.put("live", streamer.isStreaming)
        payload.put("liveView", streamer.view.id)
        payload.put("uptimeMs", System.currentTimeMillis() - startedAtMs)
        payload.put("frames", bus?.frameCount ?: 0)
        val open = bus
        if (open != null) {
            val camera = JSONObject()
            camera.put("width", open.frameWidth)
            camera.put("height", open.frameHeight)
            payload.put("camera", camera)
        }
        return payload
    }

    private fun supervise() {
        while (alive) {
            updateVehicle()
            if (onlinePanelHeld && !ParkedRails.onlineHeld() &&
                (sentryMode != SentryMode.OFF || screen.releasePanel())) onlinePanelHeld = false
            if (!panelReady) {
                if (!panelLease.acquire()) {
                    screen.apkPath?.let { DashboardControl.releasePanel(it) }
                    if (!panelLease.acquire()) {
                        Thread.sleep(SUPERVISE_EVERY_MS)
                        continue
                    }
                }
                panelReady = true
            }
            val now = System.currentTimeMillis()
            if (sentryMode != SentryMode.OFF) {
                if (!ParkedRails.isHeld) {
                    ParkedRails.hold { sentryMode != SentryMode.OFF }
                    if (ParkedRails.isHeld) screen.sleepPanel()
                }
                if (sentryMode != SentryMode.OFF && !ParkedRails.isHeld) {
                    Thread.sleep(SUPERVISE_EVERY_MS)
                    continue
                }
                bringUp(eventsDir() ?: clipsDir())
            } else {
                ParkedRails.release()
            }
            superviseSentry()
            if (!alive) break
            superviseFlag()
            if (sentryMode != SentryMode.SMART) {
                flagged = null
                marked.clear()
            }
            val held = recorder
            val wantedTarget = targetNow()
            when {
                held != null && !held.isRecording -> {
                    stopRecording()
                    failedAtMs = now
                }
                held != null && wantedTarget == null -> stopRecording()
                held != null && target != wantedTarget -> {
                    DaemonLog.d(TAG, "what is being recorded changed, starting a new clip")
                    stopRecording()
                }
                held == null && wantedTarget != null &&
                    (wantedTarget.mode == RecordingMode.EVENT || now - failedAtMs > RETRY_AFTER_MS) ->
                    startRecording(wantedTarget)
            }
            superviseLive()
            superviseFlag()
            if (sentryMode != SentryMode.OFF && ParkedRails.tick()) {
                if (!screen.isShowing) screen.sleepPanel()
            }
            if (bus != null) {
                // FastCam capture stays alive while the bus is open.
            }
            if (nothingWatching() && wantedTarget == null && !(liveWanted && relay.hasReader)) {
                cameraDown()
            } else {
                superviseFrames()
            }
            if (now - reapedAtMs > REAP_EVERY_MS) {
                reapedAtMs = now
                reap()
            }
            Thread.sleep(SUPERVISE_EVERY_MS)
        }
        superviseFlag()
        streamer.stop()
        sentry?.disarm()
        screen.hide()
        ParkedRails.release()
        stopRecording()
        cameraDown()
        panelReady = false
        if (screen.close()) panelLease.close()
    }

    private fun watchVehicle() {
        Looper.prepare()
        val looper = Looper.myLooper()!!
        val handler = Handler(looper)
        handler.post(object : Runnable {
            private var failure: String? = null

            override fun run() {
                if (!alive) {
                    looper.quitSafely()
                    return
                }
                val trouble = try {
                    acc.poll()
                    null
                } catch (e: RuntimeException) {
                    e
                } catch (e: LinkageError) {
                    e
                }
                val reason = trouble?.let { it.message ?: it.javaClass.simpleName }
                if (reason != null && reason != failure) DaemonLog.w("ACC", "power state read failed: $reason")
                failure = reason
                updateVehicle()
                handler.postDelayed(this, SUPERVISE_EVERY_MS)
            }
        })
        Looper.loop()
    }

    private fun updateVehicle() = synchronized(acc) {
        val snapshot = acc.snapshot()
        AccGate.say(snapshot?.accOn?.takeIf { snapshot.gear == null || snapshot.gear == "P" }, acc.observedAtMs())
        if (AccGate.isUnsafe) screen.hide()
        val recordingMode = Config.getString(RecordingSettings.MODE, RecordingSettings.fallback(RecordingSettings.MODE))
        wanted = driveWanted(recordingMode, snapshot, wanted)
        val enabled = Config.getBool(SurveillanceSettings.ENABLED, false)
        val mode = Config.getString(SurveillanceSettings.MODE, SurveillanceSettings.fallback(SurveillanceSettings.MODE))
        val arm = Config.getString(SurveillanceSettings.ARM, SurveillanceSettings.fallback(SurveillanceSettings.ARM))
        val next = sentryMode(enabled, mode, snapshot, arm, acc.parkedForMs())
        if (next != null) {
            sentryMode = when (next) {
                "smart" -> SentryMode.SMART
                "continuous" -> SentryMode.CONTINUOUS
                else -> SentryMode.OFF
            }
        }
        if (sentryMode != SentryMode.OFF) wanted = false
        sentry?.screenOn = Config.getBool(SurveillanceSettings.SCREEN, false)
        if (sentryMode == SentryMode.OFF) sentry?.disarm()
        val reason = surveillanceReason(enabled, snapshot, arm, next, acc.isConfirmingOff())
        if (reason != sentryReason) {
            sentryReason = reason
            DaemonLog.d("Sentry", reason)
        }
    }

    private fun nothingWatching(): Boolean =
        recorder == null && !streamer.isStreaming && sentry?.isArmed != true

    // Smart mode keeps capture open between events while the recording encoder is idle.
    private fun superviseSentry() {
        val watching = sentry ?: return
        if (sentryMode != SentryMode.SMART) {
            watching.disarm()
            return
        }
        val frames = bus ?: cameraUp() ?: return
        synchronized(acc) {
            if (!alive || sentryMode != SentryMode.SMART) return
            if (watching.isArmed) watching.rebind(frames) else watching.arm(frames)
        }
    }

    // Hold the sighting until encoder startup gives the clip a filename.
    private fun superviseFlag() {
        val watching = sentry ?: return
        watching.takeFlag()?.let { flagged = it }
        watching.takeMarks(marked)
        if (flagged == null && marked.isEmpty()) return
        val recording = recorder ?: return
        val clip = recording.clip ?: return
        val writing = target ?: return
        if (writing.mode != RecordingMode.EVENT) return
        val store = EventStore(writing.dir)
        flagged?.let {
            store.flag(clip, it.seen, it.score, it.hero)
            flagged = null
        }
        store.mark(clip, recording.clipStartedAtMs, marked)
        marked.clear()
    }

    private fun superviseLive() {
        val watching = liveWanted && relay.hasReader
        if (!watching) {
            if (streamer.isStreaming) streamer.stop()
            return
        }
        if (streamer.isStreaming && streamer.view != liveView) {
            streamer.stop()
        }
        if (streamer.isStreaming && !streamer.adjustBitrate(liveBitrateBps)) streamer.stop()
        if (!streamer.isStreaming) {
            val bus = cameraUp() ?: return
            if (bus.frameCount == 0L) return
            if (!streamer.start(bus, liveView, LIVE_FRAME_RATE_FPS, liveBitrateBps)) {
                liveWanted = false
            }
        }
    }

    private fun cameraUp(): FrameBus? {
        if (!alive) return null
        bus?.let { return it }
        if (SystemClock.elapsedRealtime() < cameraRetryAtMs) return null
        cameraRetryAtMs = SystemClock.elapsedRealtime() + RETRY_AFTER_MS
        if (!alive) return null
        if (!camera.open(frameRateFps())) {
            return null
        }
        val fresh = FrameBus(SHARK_FRAME.width, SHARK_FRAME.height)
        if (!fresh.start()) {
            camera.close()
            fresh.stop()
            return null
        }
        if (!alive) {
            camera.close()
            fresh.stop()
            return null
        }
        bus = fresh
        cameraOpenedAtMs = SystemClock.elapsedRealtime()
        cameraRetryAtMs = 0L
        DaemonLog.d(TAG, "FastCam open, frame ${SHARK_FRAME.width}x${SHARK_FRAME.height} cams=${sharkCams()}")
        return fresh
    }

    private fun cameraDown() {
        if (bus == null) return
        camera.close()
        bus?.stop()
        bus = null
        DaemonLog.d(TAG, "camera released, nothing is watching")
    }

    // Recover stalled parked capture without disarming surveillance.
    private fun superviseFrames() {
        val watched = bus ?: return
        val frames = watched.frameCount
        val waitingMs = SystemClock.elapsedRealtime() - cameraOpenedAtMs
        val quiet = if (frames == 0L) waitingMs else watched.quietForMs
        // A capture child that exited (AIS busy or preempted) will never send a first frame.
        val lost = !camera.isOpen
        val allowed = when {
            lost -> LOST_CAPTURE_MS
            frames == 0L -> CAMERA_FIRST_FRAME_MS
            else -> STALL_MS
        }
        if (quiet < allowed) return
        if (lost) {
            DaemonLog.w(TAG, "FastCam capture exited; reopening it")
        } else if (frames == 0L) {
            DaemonLog.e(TAG, "FastCam sent no frames in ${quiet / 1000}s; reopening it")
        } else {
            DaemonLog.w(TAG, "the camera went quiet for ${quiet / 1000}s, reopening it")
        }
        recorder?.stop()
        recorder = null
        target = null
        streamer.stop()
        camera.close()
        watched.stop()
        bus = null
        if (sentryMode != SentryMode.OFF) ParkedRails.reassert()
        cameraRetryAtMs = SystemClock.elapsedRealtime() + CAMERA_SETTLE_MS
        sentry?.rebind(cameraUp() ?: return)
    }

    private fun targetNow(): Target? {
        if (wanted) {
            val dir = clipsDir()
            if (dir == null) {
                noVolume("record")
                return null
            }
            return Target(dir, RecordingMode.DRIVE, options(clipLengthMs(), audio()))
        }
        if (sentryMode == SentryMode.OFF) return null
        val dir = eventsDir()
        if (dir == null) {
            noVolume("watch")
            return null
        }
        if (sentryMode == SentryMode.CONTINUOUS) {
            return Target(dir, RecordingMode.WATCH, options(clipLengthMs(), false))
        }
        val until = sentry?.triggeredUntilMs ?: 0L
        val held = recorder?.takeIf { target?.mode == RecordingMode.EVENT }
        val naming = held != null && held.clip == null
        val startedAtMs = if (held?.clip != null) held.clipStartedAtMs else 0L
        if (!eventInProgress(until, startedAtMs, System.currentTimeMillis()) && !naming && flagged == null) return null
        return Target(dir, RecordingMode.EVENT, options(EVENT_CLIP_CAP_MS, false))
    }

    private fun noVolume(what: String) {
        val now = System.currentTimeMillis()
        if (now - failedAtMs <= RETRY_AFTER_MS) return
        failedAtMs = now
        DaemonLog.e(TAG, "the app has not said which volume to $what on")
    }

    // ACC off can unmount storage; wait for the remounted FUSE path before writing.
    private fun bringUp(dir: File?) {
        val target = dir ?: return
        val uuid = storageUuid(target.path) ?: return
        val now = System.currentTimeMillis()
        if (now - remountedAtMs < 15_000L) return
        remountedAtMs = now
        remount(target)
        if (!target.exists()) DaemonLog.w(TAG, "the card at $uuid is not back yet")
    }

    private fun startRecording(wantedTarget: Target) {
        bringUp(wantedTarget.dir)
        if (!alive) return
        val bus = cameraUp() ?: return
        if (bus.frameCount == 0L) return
        val fresh = Recorder(wantedTarget.dir, wantedTarget.mode, audioFor(wantedTarget)) { clipLanded() }
        if (!fresh.start(wantedTarget.options, bus)) {
            failedAtMs = System.currentTimeMillis()
            return
        }
        target = wantedTarget
        recorder = fresh
    }

    private fun audioFor(wantedTarget: Target): AudioIngest? =
        if (wantedTarget.mode == RecordingMode.DRIVE) audio else null

    // The reply has to reach the app before the port closes.
    private fun shutdown() {
        Thread.sleep(200)
        finish()
        server?.stop()
        lock.release()
        exitProcess(0)
    }

    private fun finish() {
        alive = false
        supervisor?.join()
        stopRecording()
    }

    @Synchronized
    private fun stopRecording() {
        val held = recorder ?: return
        recorder = null
        target = null
        held.stop()
        DaemonLog.d(TAG, "recording stopped")
    }

    private fun clipLanded() {
        clips++
        reap()
    }

    // The supervisor and the thread finalising a clip both reap; two at once double-count and over-delete.
    private fun reap() {
        if (!reaping.compareAndSet(false, true)) return
        try {
            val inFlight = recorder?.clip
            clipsDir()?.let {
                free(ClipStore(it), "clips", budgetMb(RecordingSettings.BUDGET_MB, RecordingSettings.BUDGET_FALLBACK_MB), inFlight)
            }
            eventsDir()?.let {
                free(EventStore(it), "events", budgetMb(SurveillanceSettings.BUDGET_MB, SurveillanceSettings.BUDGET_FALLBACK_MB), inFlight)
            }
        } finally {
            reaping.set(false)
        }
    }

    private fun free(store: Reapable, what: String, budgetMb: Int, inFlight: String?) {
        val dropped = Retention(store, budgetMb * MB).enforce(inFlight)
        if (dropped > 0) DaemonLog.d(TAG, "dropped $dropped oldest $what to stay under $budgetMb MB")
    }

    private fun cameras(): JSONArray = JSONArray().put(
        JSONObject()
            .put("tag", "fastcam")
            .put("width", SHARK_FRAME.width)
            .put("height", SHARK_FRAME.height)
    )

    private fun clipsDir(): File? = dir(RecordingSettings.CLIPS_DIR)

    private fun eventsDir(): File? = dir(SurveillanceSettings.EVENTS_DIR)

    private fun dir(key: String): File? {
        val path = Config.getString(key, "")
        if (path.isEmpty()) return null
        return File(path)
    }

    private fun budgetMb(key: String, fallback: Int): Int = Config.getInt(key, fallback)

    private fun options(clipLengthMs: Long, audio: Boolean) = RecordingOptions(
        clipLengthMs = clipLengthMs,
        quality = setting(RecordingSettings.QUALITY),
        codec = setting(RecordingSettings.CODEC),
        frameRateFps = frameRateFps(),
        audio = audio
    )

    private fun clipLengthMs(): Long =
        setting(RecordingSettings.CLIP_LENGTH_MINUTES).toLong() * 60_000L

    private fun frameRateFps(): Int = setting(RecordingSettings.FRAME_RATE_FPS).toInt()

    private fun audio(): Boolean = Config.getBool(RecordingSettings.AUDIO, false)

    private fun setting(key: String): String = Config.getString(key, RecordingSettings.fallback(key))
}
