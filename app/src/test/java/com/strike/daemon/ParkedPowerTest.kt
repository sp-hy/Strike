package com.strike.daemon

import org.junit.Assert.*
import org.junit.Test

class ParkedPowerTest {

    @Test fun onlyKnownSettingsAndValuesAreAccepted() {
        for (key in ParkedPower.switches) {
            assertTrue(ParkedPower.accepts(key, "true"))
            assertTrue(ParkedPower.accepts(key, "false"))
            assertFalse(ParkedPower.accepts(key, "1"))
        }
        assertTrue(ParkedPower.accepts(ParkedPower.CUTOFF_VOLTS, "off"))
        assertTrue(ParkedPower.accepts(ParkedPower.CUTOFF_VOLTS, "12.2"))
        assertFalse(ParkedPower.accepts(ParkedPower.CUTOFF_VOLTS, "10.0"))
        assertFalse(ParkedPower.accepts("power.unknown", "true"))
    }

    @Test fun theFallbackCutoffIsOffered() {
        assertTrue(ParkedPower.cutoff.fallback in ParkedPower.cutoff.options)
        assertNotNull(ParkedPower.cutoff.fallback.toDoubleOrNull())
    }
}
