package com.strike.daemon

import org.junit.Assert.*
import org.junit.Test

class BatteryGuardTest {

    @Test fun tripsOnlyAfterThreeConsecutiveLowReadings() {
        val guard = BatteryGuard()
        assertFalse(guard.observe(11.7, 11.8, false))
        assertFalse(guard.observe(11.7, 11.8, false))
        assertFalse(guard.observe(12.1, 11.8, false))
        assertFalse(guard.observe(11.8, 11.8, false))
        assertFalse(guard.observe(11.6, 11.8, false))
        assertTrue(guard.observe(11.5, 11.8, false))
        assertEquals(11.5, guard.volts!!, 0.0)
    }

    @Test fun surfaceChargeReboundDoesNotReleaseTheCutoff() {
        val guard = BatteryGuard()
        repeat(3) { guard.observe(11.5, 11.8, false) }
        repeat(10) { assertTrue(guard.observe(12.6, 11.8, false)) }
    }

    @Test fun startingTheCarOrChargingReleasesTheCutoff() {
        val started = BatteryGuard()
        repeat(3) { started.observe(11.5, 11.8, false) }
        assertFalse(started.observe(11.5, 11.8, true))
        assertFalse(started.observe(11.5, 11.8, false))

        val charging = BatteryGuard()
        repeat(3) { charging.observe(11.5, 11.8, false) }
        assertTrue(charging.observe(13.6, 11.8, false))
        assertTrue(charging.observe(13.6, 11.8, false))
        assertFalse(charging.observe(13.6, 11.8, false))
    }

    @Test fun missingOrImplausibleReadingsNeitherTripNorRelease() {
        val guard = BatteryGuard()
        repeat(5) { assertFalse(guard.observe(null, 11.8, false)) }
        repeat(5) { assertFalse(guard.observe(0.0, 11.8, false)) }
        assertNull(guard.volts)
        repeat(3) { guard.observe(11.5, 11.8, false) }
        assertTrue(guard.observe(null, 11.8, null))
        assertTrue(guard.observe(255.0, 11.8, false))
    }

    @Test fun turningTheCutoffOffReleasesIt() {
        val guard = BatteryGuard()
        repeat(3) { guard.observe(11.5, 11.8, false) }
        assertFalse(guard.observe(11.5, null, false))
        assertFalse(guard.observe(11.5, 11.8, false))
    }
}
