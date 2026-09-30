package com.strike.daemon

import dadb.AdbShellResponse
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ParkedExtrasTest {
    @get:Rule val temporary = TemporaryFolder()

    private val start = 1_000_000L
    private var now = start
    private val commands = ArrayList<String>()
    private val panorama = ArrayList<Int>()
    private val settings = HashMap<String, String>()
    private var applyExit = 0
    private var listening = true

    private fun extras(marker: File = File(temporary.root, "parked-extras.owner"), onPanorama: (Int) -> Unit = {}) = ParkedExtras(
        shell = { command ->
            commands.add(command)
            val name = command.removePrefix("settings get global ")
            when {
                command == SHUTDOWN_APPLY -> AdbShellResponse("", "", applyExit)
                name != command -> AdbShellResponse(settings[name] ?: "null", "", 0)
                else -> AdbShellResponse("", "", 0)
            }
        },
        panorama = { value -> onPanorama(value); panorama.add(value); true },
        adbListening = { listening },
        marker = marker,
        nowMs = { now }
    )

    @Test fun leversAreRecordedBeforeTheyArePulledAndForgottenOnRelease() {
        val marker = File(temporary.root, "parked-extras.owner")
        val extras = ParkedExtras(
            shell = { command ->
                if (command == SHUTDOWN_APPLY) assertTrue("shutdown" in marker.readLines())
                commands.add(command)
                AdbShellResponse("", "", 0)
            },
            panorama = { value ->
                if (value == 1) assertTrue("camera" in marker.readLines())
                panorama.add(value)
                true
            },
            adbListening = { true },
            marker = marker,
            nowMs = { now }
        )
        extras.tick(camera = true, shutdown = true, network = false)
        assertEquals(listOf(1), panorama)
        assertTrue(SHUTDOWN_APPLY in commands)
        extras.release()
        assertEquals(listOf(1, 0), panorama)
        assertTrue(SHUTDOWN_RELEASE in commands)
        assertFalse(marker.exists())
    }

    @Test fun anEarlierDaemonsLeversAreReleasedExactly() {
        val marker = File(temporary.root, "parked-extras.owner")
        marker.writeText("shutdown")
        extras(marker).release()
        assertEquals(listOf(SHUTDOWN_RELEASE), commands)
        assertTrue(panorama.isEmpty())
        assertFalse(marker.exists())
        extras(marker).release()
        assertEquals(listOf(SHUTDOWN_RELEASE), commands)
    }

    @Test fun aLeverThatCannotBeRecordedIsNotPulled() {
        val blocked = File(temporary.newFile(), "parked-extras.owner")
        val extras = extras(blocked)
        extras.tick(camera = true, shutdown = true, network = false)
        assertTrue(panorama.isEmpty())
        assertFalse(SHUTDOWN_APPLY in commands)
        extras.release()
        assertTrue(panorama.isEmpty())
    }

    @Test fun theCameraHeartbeatRepeatsEveryTwoSeconds() {
        val extras = extras()
        for (second in 0..4) {
            now = start + second * 1_000L
            extras.tick(camera = true, shutdown = false, network = false)
        }
        assertEquals(listOf(1, 1, 1), panorama)
    }

    @Test fun aDeniedShutdownHoldIsOnlyRetriedEveryFiveMinutes() {
        applyExit = 2
        val extras = extras()
        extras.tick(camera = false, shutdown = true, network = false)
        now = start + 4 * 60_000L
        extras.tick(camera = false, shutdown = true, network = false)
        assertEquals(1, commands.count { it == SHUTDOWN_APPLY })
        now = start + 5 * 60_000L
        extras.tick(camera = false, shutdown = true, network = false)
        assertEquals(2, commands.count { it == SHUTDOWN_APPLY })
    }

    @Test fun onlyRadiosThatWereOnWhenParkedAreTurnedBackOn() {
        settings["wifi_on"] = "1"
        settings["mobile_data"] = "0"
        val extras = extras()
        extras.tick(camera = false, shutdown = false, network = true)
        settings["wifi_on"] = "0"
        now = start + 30_000L
        extras.tick(camera = false, shutdown = false, network = true)
        assertTrue("svc wifi enable" in commands)
        assertFalse("svc data enable" in commands)
    }

    @Test fun wirelessDebuggingIsRestoredAtMostEveryFifteenMinutes() {
        listening = false
        val extras = extras()
        for (minute in 0..15) {
            now = start + minute * 60_000L
            extras.tick(camera = false, shutdown = false, network = false)
        }
        assertEquals(2, commands.count { it.contains("adb_enabled 1") })
    }
}
