package com.strike.daemon

import com.strike.core.ScratchPaths

// Shared paths are shell-owned and app-readable under the resolved scratch dir.
// Use string joins so Windows JVMs do not rewrite Unix head-unit paths.
val STRIKE_DIR: String
    get() = ScratchPaths.getDir().trimEnd('/') + "/strike"

val CONFIG_PATH: String
    get() = "$STRIKE_DIR/config.json"

val CAM_LOG_PATH: String
    get() = "$STRIKE_DIR/cam.log"

val CAM_LOCK_PATH: String
    get() = "$STRIKE_DIR/cam.lock"

val CAM_SENTINEL_PATH: String
    get() = "$STRIKE_DIR/cam.disabled"

val CAM_SCRIPT_PATH: String
    get() = "$STRIKE_DIR/start_cam.sh"

val CAM_WATCHDOG_PID_PATH: String
    get() = "$STRIKE_DIR/cam_watchdog.pid"

internal val PANEL_LOCK_PATH: String
    get() = "$STRIKE_DIR/parked-panel.lock"

/** Overdrive holds 19876 on this same head unit and both may be installed. */
const val COMMAND_PORT = 19886

const val PACKET_PORT = 19887

// The app captures cabin audio; the daemon muxes it, so AAC crosses a separate socket.
const val AUDIO_PORT = 19888

const val CAM_PROCESS = "strike_cam"

/** The daemon and the watchdog script must read this the same way. */
const val EXIT_ALREADY_RUNNING = 3
