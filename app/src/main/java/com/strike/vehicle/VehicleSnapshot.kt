package com.strike.vehicle

class VehicleSnapshot(
    val soc: Int?,
    val rangeKm: Int?,
    val batteryKwh: Double?,
    val fuelPercent: Int?,
    val fuelRangeKm: Int?,
    val gear: String?,
    val accOn: Boolean?,
    val locked: Boolean?,
    val batteryVolts: Double? = null,
    /** The parked 12 V cutoff has tripped; decided app-side by [com.strike.daemon.BatteryGuard]. */
    val lowBattery: Boolean = false
)
