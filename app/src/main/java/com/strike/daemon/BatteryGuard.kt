package com.strike.daemon

private const val SAMPLES = 3
private const val CHARGING_VOLTS = 13.2
private val PLAUSIBLE_VOLTS = 6.0..18.0

/**
 * Parked 12 V cutoff. Once tripped it stays low until the car starts or the DC-DC
 * converter is visibly charging: surface charge alone rebounds past the cutoff.
 */
class BatteryGuard {

    var isLow = false
        private set

    var volts: Double? = null
        private set

    private var lowSamples = 0
    private var chargingSamples = 0

    @Synchronized
    fun observe(reading: Double?, cutoffVolts: Double?, accOn: Boolean?): Boolean {
        val plausible = reading?.takeIf { it in PLAUSIBLE_VOLTS }
        if (plausible != null) volts = plausible
        if (accOn == true || cutoffVolts == null) {
            reset()
            return false
        }
        if (plausible == null) return isLow
        if (isLow) {
            chargingSamples = if (plausible >= CHARGING_VOLTS) chargingSamples + 1 else 0
            if (chargingSamples >= SAMPLES) reset()
            return isLow
        }
        lowSamples = if (plausible <= cutoffVolts) lowSamples + 1 else 0
        if (lowSamples >= SAMPLES) isLow = true
        return isLow
    }

    private fun reset() {
        isLow = false
        lowSamples = 0
        chargingSamples = 0
    }
}
