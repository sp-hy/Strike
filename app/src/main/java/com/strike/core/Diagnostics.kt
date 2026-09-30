package com.strike.core

object Diagnostics {
    const val PERF_LOGS = "diagnostics.perfLogs"

    fun perfLogs(): Boolean = Config.getBool(PERF_LOGS, false)
}
