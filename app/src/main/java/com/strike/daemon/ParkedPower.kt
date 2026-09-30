package com.strike.daemon

import com.strike.core.Config
import com.strike.recording.Choice

object ParkedPower {

    const val KEEP_AWAKE = "power.keepAwake"
    const val CAMERA_HEARTBEAT = "power.cameraHeartbeat"
    const val SHUTDOWN_HOLD = "power.shutdownHold"
    const val NETWORK = "power.network"
    const val CLOUD_HEARTBEAT = "power.cloudHeartbeat"
    const val CUTOFF_VOLTS = "power.cutoffVolts"

    val switches = listOf(KEEP_AWAKE, CAMERA_HEARTBEAT, SHUTDOWN_HOLD, NETWORK, CLOUD_HEARTBEAT)

    val cutoff = Choice(listOf("off", "11.6", "11.8", "12.0", "12.2"), "11.8")

    fun on(key: String): Boolean = Config.getBool(key, false)

    fun cutoffSetting(): String =
        Config.getString(CUTOFF_VOLTS, cutoff.fallback).takeIf { it in cutoff.options } ?: cutoff.fallback

    /** Null when the cutoff is off. */
    fun cutoffVolts(): Double? = cutoffSetting().toDoubleOrNull()

    fun accepts(key: String, value: String): Boolean = when (key) {
        in switches -> value == "true" || value == "false"
        CUTOFF_VOLTS -> value in cutoff.options
        else -> false
    }
}
