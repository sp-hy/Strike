package com.strike.vehicle

import android.util.Base64
import com.strike.core.Logs
import com.strike.core.ScratchPaths
import com.strike.daemon.STRIKE_DIR
import com.strike.daemon.Shell
import org.json.JSONObject
import java.io.File
import java.io.IOException

private const val TAG = "VehicleCache"

/**
 * App-UID [VehicleTelemetry] publishes here via shell so the shell dashboard
 * (uid 2000) can read it. App-owned 660 files under Android/data are not
 * readable by shell on Shark FUSE/SELinux.
 */
object VehicleCache {

    private val path: String get() = "$STRIKE_DIR/vehicle.json"

    fun write(shell: Shell, snapshot: VehicleSnapshot?) {
        if (snapshot == null) return
        if (snapshot.soc == null && snapshot.rangeKm == null && snapshot.batteryKwh == null &&
            snapshot.fuelPercent == null && snapshot.fuelRangeKm == null &&
            snapshot.gear == null && snapshot.accOn == null && snapshot.locked == null) return
        val json = JSONObject()
        snapshot.soc?.let { json.put("soc", it) }
        snapshot.rangeKm?.let { json.put("rangeKm", it) }
        snapshot.batteryKwh?.let { json.put("batteryKwh", it) }
        snapshot.fuelPercent?.let { json.put("fuelPercent", it) }
        snapshot.fuelRangeKm?.let { json.put("fuelRangeKm", it) }
        snapshot.gear?.let { json.put("gear", it) }
        snapshot.accOn?.let { json.put("accOn", it) }
        snapshot.locked?.let { json.put("locked", it) }
        json.put("atMs", System.currentTimeMillis())
        val encoded = Base64.encodeToString(json.toString().toByteArray(), Base64.NO_WRAP)
        val ok = shell.check(
            ScratchPaths.prepareShellCommand(
                "echo '$encoded' | base64 -d > '$path.tmp' && chmod 644 '$path.tmp' && " +
                    "mv -f '$path.tmp' '$path'"
            )
        )
        if (!ok) {
            Logs.w(TAG, "shell could not publish vehicle snapshot; trying app write")
            writeLocal(json)
        }
    }

    private fun writeLocal(json: JSONObject) {
        val file = File(path)
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "vehicle.json.tmp")
            tmp.writeText(json.toString())
            tmp.setReadable(true, false)
            tmp.setWritable(true, false)
            if (!tmp.renameTo(file)) {
                file.writeText(json.toString())
                tmp.delete()
            }
            file.setReadable(true, false)
            file.setWritable(true, false)
        } catch (e: IOException) {
            Logs.w(TAG, "could not publish vehicle snapshot")
        }
    }

    fun read(maxAgeMs: Long = 60_000L): VehicleSnapshot? {
        val file = File(path)
        if (!file.isFile) return null
        return try {
            val json = JSONObject(file.readText())
            val at = json.optLong("atMs", 0L)
            if (at > 0L && System.currentTimeMillis() - at > maxAgeMs) return null
            VehicleSnapshot(
                soc = json.optIntOrNull("soc"),
                rangeKm = json.optIntOrNull("rangeKm"),
                batteryKwh = json.optDoubleOrNull("batteryKwh"),
                fuelPercent = json.optIntOrNull("fuelPercent"),
                fuelRangeKm = json.optIntOrNull("fuelRangeKm"),
                gear = json.optStringOrNull("gear"),
                accOn = json.optBooleanOrNull("accOn"),
                locked = json.optBooleanOrNull("locked")
            )
        } catch (e: Exception) {
            null
        }
    }
}

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { it.isFinite() } else null

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
    if (has(key) && !isNull(key)) optBoolean(key) else null
