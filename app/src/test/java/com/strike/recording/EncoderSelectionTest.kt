package com.strike.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class EncoderSelectionTest {

    @Test
    fun aWorkingDefaultDoesNotEnumerateOrOpenAlternatives() {
        val running = Any()
        val selected = firstEncoder(
            preferred = { running },
            alternatives = { error("A working encoder must not trigger discovery") },
            start = { error("A working encoder must not be replaced") }
        )
        assertSame(running, selected)
    }

    @Test
    fun aFailedDefaultUsesTheFirstAlternativeThatStarts() {
        val opened = ArrayList<String>()
        val selected = firstEncoder(
            preferred = { null },
            alternatives = { sequenceOf("busy", "available", "unused") },
            start = {
                opened.add(it)
                if (it == "available") it else null
            }
        )
        assertEquals("available", selected)
        assertEquals(listOf("busy", "available"), opened)
    }

    @Test
    fun noSuitableAlternativeLeavesRecordingStopped() {
        val selected = firstEncoder<String>(
            preferred = { null },
            alternatives = { emptySequence() },
            start = { error("An unsupported encoder must not be opened") }
        )
        assertNull(selected)
    }

    @Test
    fun failedAlternativesAreExhaustedWithoutRetrying() {
        val opened = ArrayList<String>()
        val selected = firstEncoder<String>(
            preferred = { null },
            alternatives = { sequenceOf("first", "second") },
            start = { opened.add(it); null }
        )
        assertNull(selected)
        assertEquals(listOf("first", "second"), opened)
    }
}
