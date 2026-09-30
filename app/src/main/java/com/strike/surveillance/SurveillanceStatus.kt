package com.strike.surveillance

import com.strike.vehicle.VehicleSnapshot

internal fun surveillanceReason(
    enabled: Boolean,
    snapshot: VehicleSnapshot?,
    arm: String,
    mode: String?,
    confirmingOff: Boolean,
    lowBattery: Boolean = false
): String = when {
    !enabled -> "Off"
    confirmingOff -> "Confirming that the car switched off"
    snapshot?.accOn == null -> "Waiting for the car's power state"
    snapshot.accOn || (snapshot.gear != null && snapshot.gear != "P") ->
        "Standing by until the car switches off"
    lowBattery -> "The 12 V battery is low; paused until the car starts"
    arm == "lock" && mode == "off" && snapshot.locked == null ->
        "Lock state unavailable; arming one minute after switch-off"
    arm == "lock" && mode == "off" -> "Standing by until the doors lock"
    else -> "Starting surveillance"
}
