package com.strike.vehicle

import android.content.Context
import android.os.Looper
import android.os.Process
import com.strike.core.Logs

private const val TAG = "Vehicle"
private const val STATISTIC = "android.hardware.bydauto.statistic.BYDAutoStatisticDevice"
private const val POWER = "android.hardware.bydauto.power.BYDAutoPowerDevice"
private const val BODYWORK = "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice"
private const val GEARBOX = "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice"
private const val OTA = "android.hardware.bydauto.ota.BYDAutoOtaDevice"
private const val CAR_ADAPTER = "com.ts.lib.caradapter.CarAdapterManager"

private const val RETRY_MS = 30_000L

private val GEARS = arrayOf("P", "R", "N", "D", "M", "S")

// Read-only BYD vehicle signals. Call from the app UID only — shell (uid 2000)
// cannot hold BYDAUTO grants (same constraint as Open-DiKey).
class VehicleTelemetry(
    private val context: Context,
    private val warn: (String) -> Unit = { Logs.w(TAG, it) }
) {

    private val lock = Any()
    private val devices = HashMap<String, Any>()
    private val triedAtMs = HashMap<String, Long>()
    private val appUid = Process.myUid() != 2000
    private val gears = GearReader(adapter = { device(CAR_ADAPTER) })
    private val adapterType: Class<*>? by lazy {
        if (!appUid) return@lazy null
        if (!BydSdk.ensure(context) && !BydSdk.isLoadable(context)) return@lazy null
        try {
            Class.forName(CAR_ADAPTER)
        } catch (e: ClassNotFoundException) {
            null
        } catch (e: LinkageError) {
            warn("The alternate gear reader could not load")
            null
        }
    }

    fun snapshot(): VehicleSnapshot? {
        val statistic = device(STATISTIC)
        val bodywork = device(BODYWORK)
        val gearbox = device(GEARBOX)
        val power = device(POWER)
        val ota = device(OTA)
        val gear = gear(gearbox)
        if (statistic == null && bodywork == null && gearbox == null && power == null && ota == null && gear == null) {
            return null
        }
        // Open-DiKey BydVehicleInfoController: Statistic getters for SOC / range / fuel / kWh.
        val soc = socOf(read(statistic, "getElecPercentageValue")?.toDouble())
        val fuel = fuelOf(read(statistic, "getFuelPercentageValue")?.toInt())
        val fuelRange = fuelRangeOf(read(statistic, "getFuelDrivingRangeValue")?.toInt())
        val remainingKwh = batteryKwhOf(
            read(statistic, "getEVRemainingBatteryPower")?.toDouble()
                ?: read(power, "getBatteryRemainPowerEV")?.toDouble(),
            soc
        ) {
            read(statistic, "getRemainingBatteryPower")?.toInt()
        }
        return VehicleSnapshot(
            soc = soc,
            rangeKm = rangeOf(read(statistic, "getElecDrivingRangeValue")?.toInt())
                ?: rangeOf(read(statistic, "getDrivingRangeAll")?.toInt()),
            batteryKwh = remainingKwh,
            fuelPercent = fuel,
            fuelRangeKm = fuelRange,
            gear = gear,
            accOn = accOnOf(read(bodywork, "getPowerLevel")?.toInt()),
            locked = lockOf(read(ota, "getLFDoorLockState")?.toInt())
        )
    }

    fun accOn(): Boolean? = accOnOf(read(device(BODYWORK), "getPowerLevel")?.toInt())

    fun parkingSnapshot(): VehicleSnapshot = VehicleSnapshot(
        soc = null,
        rangeKm = null,
        batteryKwh = null,
        fuelPercent = null,
        fuelRangeKm = null,
        gear = gear(device(GEARBOX)),
        accOn = polledAccOnOf(read(device(BODYWORK), "getPowerLevel")?.toInt()),
        locked = lockOf(read(device(OTA), "getLFDoorLockState")?.toInt())
    )

    private fun gear(gearbox: Any?): String? =
        gears.read(read(gearbox, "getGearboxAutoModeType")?.toInt())

    private fun read(device: Any?, getter: String): Number? {
        if (device == null) return null
        return try {
            device.javaClass.getMethod(getter).invoke(device) as? Number
        } catch (e: ReflectiveOperationException) {
            null
        }
    }

    private fun device(className: String): Any? = synchronized(lock) {
        devices[className]?.let { return it }
        val now = System.currentTimeMillis()
        val tried = triedAtMs[className]
        if (tried != null && now - tried < RETRY_MS) return null
        triedAtMs[className] = now
        val resolved = resolve(className)
        if (resolved != null) devices[className] = resolved
        resolved
    }

    private fun resolve(className: String): Any? {
        if (!appUid) return null
        // getInstance builds handlers, so the calling thread needs a looper.
        if (Looper.myLooper() == null) Looper.prepare()
        val device = (if (className == CAR_ADAPTER) adapterType else BydSdk.deviceClass(className, context))
            ?: return null
        val getInstance = try {
            device.getMethod("getInstance", Context::class.java)
        } catch (e: NoSuchMethodException) {
            warn("$className has no getInstance(Context)")
            return null
        }
        // Open-DiKey vehicle info: plain app context first, then permission wrapper.
        invokeGetInstance(getInstance, context.applicationContext ?: context)?.let { return it }
        return try {
            getInstance.invoke(null, BydPermissions(context))
        } catch (e: ReflectiveOperationException) {
            val cause = e.cause ?: e
            warn("$className would not start: ${cause.javaClass.simpleName}")
            null
        }
    }

    private fun invokeGetInstance(method: java.lang.reflect.Method, ctx: Context): Any? = try {
        method.invoke(null, ctx)
    } catch (_: ReflectiveOperationException) {
        null
    }
}

// The head unit answers 0 for a signal it has not received yet.
// Open-DiKey keeps 0..100 SOC; reject only non-finite / out of range.
internal fun socOf(percent: Double?): Int? {
    if (percent == null || !percent.isFinite() || percent < 0.0 || percent > 100.0) return null
    return Math.round(percent).toInt()
}

internal fun rangeOf(km: Int?): Int? = if (km != null && km in 1..999) km else null

// Sensor rails sit above both domains: 254 and up for the gauge, 2046 and up for the range.
internal fun fuelOf(percent: Int?): Int? = if (percent != null && percent in 1..100) percent else null

internal fun fuelRangeOf(km: Int?): Int? = if (km != null && km in 1..1200) km else null

// Open-DiKey usable kWh band for Statistic.getEVRemainingBatteryPower.
internal inline fun batteryKwhOf(directKwh: Double?, soc: Int?, fallbackTenthsKwh: () -> Int?): Double? {
    acceptKwh(directKwh, soc)?.let { return it }
    return acceptKwh(fallbackTenthsKwh()?.div(10.0), soc)
}

internal fun acceptKwh(kwh: Double?, soc: Int?): Double? {
    if (kwh == null || !kwh.isFinite() || kwh !in 0.5..100.0) return null
    // Wrong getters sometimes echo SOC% as "kWh".
    if (soc != null && soc in 5..100 && kotlin.math.abs(kwh - soc) < 1.0) return null
    return kwh
}

internal fun gearOf(mode: Int?): String? =
    if (mode != null && mode in 1..GEARS.size) GEARS[mode - 1] else null

// Power levels 4 and 255 are not usable ACC readings.
internal fun accOnOf(powerLevel: Int?): Boolean? =
    if (powerLevel != null && powerLevel in 0..3) powerLevel >= 2 else null

// Overdrive's heartbeat skips accessory level 1 while power is transitioning.
internal fun polledAccOnOf(powerLevel: Int?): Boolean? =
    if (powerLevel == 1) null else accOnOf(powerLevel)

// Lock state: 1 unlocked, 2 locked, 0 unavailable.
internal fun lockOf(state: Int?): Boolean? = when (state) {
    1 -> false
    2 -> true
    else -> null
}
