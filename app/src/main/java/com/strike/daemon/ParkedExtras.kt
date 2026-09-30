package com.strike.daemon

import dadb.AdbShellResponse
import java.io.File
import java.io.IOException

private const val TAG = "Rails"

private const val HEARTBEAT_MS = 2_000L
private const val SHUTDOWN_CHECK_MS = 30_000L
private const val SHUTDOWN_DENIED_MS = 5 * 60_000L
private const val NETWORK_MS = 30_000L
private const val ADB_CHECK_MS = 60_000L
private const val ADB_FIX_MS = 15 * 60_000L

private const val PANORAMA = 1031
private const val PANORAMA_WORK_MODE_SET = 1306529812

private const val CAMERA = "camera"
private const val SHUTDOWN = "shutdown"

private const val TOKEN = "strike"
private const val LIST = "vendor.peripheral.shutdown_critical_list"
private const val STATE = "vendor.peripheral.$TOKEN.state"

internal const val SHUTDOWN_APPLY =
    "CUR=$(getprop $LIST); case \" \$CUR \" in *\" $TOKEN \"*) ;; *) " +
    "setprop $LIST \"\${CUR:+\$CUR }$TOKEN\" || exit 2;; esac; " +
    "setprop $STATE ONLINE || exit 3; " +
    "[ \"$(getprop $STATE)\" = ONLINE ] || exit 4; " +
    "case \" $(getprop $LIST) \" in *\" $TOKEN \"*) exit 0;; *) exit 5;; esac"

internal const val SHUTDOWN_CHECK =
    "[ \"$(getprop $STATE)\" = ONLINE ] || exit 4; " +
    "case \" $(getprop $LIST) \" in *\" $TOKEN \"*) exit 0;; *) exit 5;; esac"

// Strips only Strike's token; Overdrive and the OEM share this list.
internal const val SHUTDOWN_RELEASE =
    "setprop $STATE OFFLINE; CUR=$(getprop $LIST); NEW=\"\"; " +
    "for t in \$CUR; do [ \"\$t\" = \"$TOKEN\" ] || NEW=\"\${NEW:+\$NEW }\$t\"; done; " +
    "[ \"\$NEW\" = \"\$CUR\" ] || setprop $LIST \"\$NEW\"; " +
    "case \" $(getprop $LIST) \" in *\" $TOKEN \"*) exit 5;; *) exit 0;; esac"

private const val ADB_ON =
    "settings put global adb_enabled 1; settings put global adb_wifi_enabled 1; " +
    "settings put global adb_allowed_connection_time 0"

/**
 * The optional DiLink 5 levers from Overdrive's parked lease, run by the camera daemon
 * while it holds parked power. Levers that change shared state are recorded in [marker]
 * before they are pulled, so a restarted daemon releases exactly what an earlier one left.
 */
internal class ParkedExtras(
    private val shell: (String) -> AdbShellResponse? = ::localCommand,
    private val panorama: (Int) -> Boolean = PanoramaWorkMode::write,
    private val adbListening: () -> Boolean = ::portOpen,
    private val marker: File = File("$STRIKE_DIR/parked-extras.owner"),
    private val nowMs: () -> Long = System::currentTimeMillis
) {

    private var recorded: MutableSet<String>? = null
    private var cameraOn = false
    private var heartbeatAtMs = 0L
    private var heartbeatFailed = false
    private var shutdownOn = false
    private var shutdownAtMs = 0L
    private var shutdownDeniedAtMs = 0L
    private var wifiWanted: Boolean? = null
    private var dataWanted: Boolean? = null
    private var networkAtMs = 0L
    private var adbAtMs = 0L
    private var adbFixedAtMs = 0L

    @Synchronized
    fun tick(camera: Boolean, shutdown: Boolean, network: Boolean) {
        val now = nowMs()
        if (camera) heartbeat(now) else stopCamera()
        if (shutdown) holdShutdown(now) else releaseShutdown()
        if (network) keepNetwork(now) else forgetNetwork()
        keepAdb(now)
    }

    /** Also undoes levers recorded by an earlier daemon. */
    @Synchronized
    fun release() {
        stopCamera()
        releaseShutdown()
        forgetNetwork()
    }

    private fun heartbeat(now: Long) {
        if (!cameraOn) {
            if (!record(CAMERA)) return
            cameraOn = true
            heartbeatAtMs = 0L
            DaemonLog.d(TAG, "camera heartbeat started")
        }
        if (now - heartbeatAtMs < HEARTBEAT_MS) return
        heartbeatAtMs = now
        val landed = panorama(1)
        if (!landed && !heartbeatFailed) DaemonLog.w(TAG, "camera heartbeat write did not land")
        heartbeatFailed = !landed
    }

    private fun stopCamera() {
        if (!cameraOn && CAMERA !in recorded()) return
        panorama(0)
        cameraOn = false
        heartbeatFailed = false
        forget(CAMERA)
        DaemonLog.d(TAG, "camera heartbeat stopped")
    }

    private fun holdShutdown(now: Long) {
        if (shutdownDeniedAtMs != 0L && now - shutdownDeniedAtMs < SHUTDOWN_DENIED_MS) return
        if (shutdownOn && now - shutdownAtMs < SHUTDOWN_CHECK_MS) return
        shutdownAtMs = now
        if (shutdownOn && shell(SHUTDOWN_CHECK)?.exitCode == 0) return
        if (!record(SHUTDOWN)) return
        val result = shell(SHUTDOWN_APPLY)
        when (result?.exitCode) {
            0 -> {
                if (!shutdownOn) DaemonLog.d(TAG, "shutdown hold applied to $LIST")
                shutdownOn = true
                shutdownDeniedAtMs = 0L
            }
            2, 3 -> {
                if (shutdownDeniedAtMs == 0L) DaemonLog.w(TAG, "shutdown hold was denied; retrying every 5 min")
                shutdownDeniedAtMs = now
            }
            else -> DaemonLog.w(TAG, "shutdown hold did not apply (exit ${result?.exitCode})")
        }
    }

    private fun releaseShutdown() {
        if (!shutdownOn && SHUTDOWN !in recorded()) return
        val exit = shell(SHUTDOWN_RELEASE)?.exitCode
        if (exit != 0) DaemonLog.w(TAG, "shutdown hold release exited $exit")
        shutdownOn = false
        shutdownDeniedAtMs = 0L
        forget(SHUTDOWN)
        DaemonLog.d(TAG, "shutdown hold released")
    }

    // Only restores radios that were on when parked power was taken.
    private fun keepNetwork(now: Long) {
        if (wifiWanted == null) {
            wifiWanted = setting("wifi_on").let { it == "1" || it == "2" }
            dataWanted = setting("mobile_data") == "1"
            networkAtMs = now
            return
        }
        if (now - networkAtMs < NETWORK_MS) return
        networkAtMs = now
        if (wifiWanted == true && setting("wifi_on") == "0") {
            DaemonLog.d(TAG, "Wi-Fi went off while parked; turning it back on")
            shell("svc wifi enable")
        }
        if (dataWanted == true && setting("mobile_data") == "0") {
            DaemonLog.d(TAG, "mobile data went off while parked; turning it back on")
            shell("svc data enable")
        }
    }

    private fun forgetNetwork() {
        wifiWanted = null
        dataWanted = null
    }

    private fun keepAdb(now: Long) {
        if (now - adbAtMs < ADB_CHECK_MS) return
        adbAtMs = now
        if (adbListening()) return
        if (adbFixedAtMs != 0L && now - adbFixedAtMs < ADB_FIX_MS) return
        adbFixedAtMs = now
        DaemonLog.w(TAG, "wireless debugging stopped listening while parked; turning it back on")
        shell(ADB_ON)
    }

    private fun setting(name: String): String? =
        shell("settings get global $name")?.takeIf { it.exitCode == 0 }?.output?.trim()

    private fun recorded(): MutableSet<String> = recorded ?: try {
        marker.takeIf { it.exists() }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toMutableSet()
    } catch (e: IOException) {
        null
    }.let { it ?: mutableSetOf() }.also { recorded = it }

    private fun record(lever: String): Boolean {
        val levers = recorded()
        if (lever in levers) return true
        return try {
            marker.parentFile?.mkdirs()
            marker.writeText((levers + lever).joinToString("\n"))
            levers.add(lever)
            true
        } catch (e: IOException) {
            DaemonLog.w(TAG, "Could not record the $lever lever; leaving it alone")
            false
        }
    }

    private fun forget(lever: String) {
        val levers = recorded()
        if (!levers.remove(lever)) return
        try {
            if (levers.isEmpty()) marker.delete() else marker.writeText(levers.joinToString("\n"))
        } catch (e: IOException) {
            DaemonLog.w(TAG, "Could not update the parked power record")
        }
    }
}

private object PanoramaWorkMode {

    private var manager: Any? = null

    @Synchronized
    fun write(value: Int): Boolean {
        val auto = manager ?: DaemonContext.get()?.getSystemService("auto")?.also { manager = it } ?: return false
        return try {
            val rc = auto.javaClass.getMethod("setInt", Int::class.java, Int::class.java, Int::class.java)
                .invoke(auto, PANORAMA, PANORAMA_WORK_MODE_SET, value)
            rc == null || rc == true || (rc is Number && rc.toInt() == 0)
        } catch (e: ReflectiveOperationException) {
            manager = null
            false
        } catch (e: RuntimeException) {
            manager = null
            false
        }
    }
}
