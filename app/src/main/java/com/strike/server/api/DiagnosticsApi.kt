package com.strike.server.api

import com.strike.boot.StartupSettings
import com.strike.core.Config
import com.strike.core.Diagnostics
import com.strike.daemon.Shell
import com.strike.server.JSON
import com.strike.server.Response
import com.strike.server.TEXT
import com.strike.server.formValue
import org.json.JSONObject

private val TOGGLES = mapOf(
    "perfLogs" to Diagnostics.PERF_LOGS,
    "openApp" to StartupSettings.OPEN_APP
)

class DiagnosticsApi(private val shell: Shell) {

    fun settings(): Response {
        val payload = JSONObject()
            .put("perfLogs", Diagnostics.perfLogs())
            .put("openApp", StartupSettings.openApp())
        return Response(200, JSON, payload.toString().toByteArray())
    }

    fun save(body: String): Response {
        val (field, key) = TOGGLES.entries.firstOrNull { formValue(body, it.key) != null }?.toPair()
            ?: return Response(400, TEXT, "No setting given".toByteArray())
        val on = when (formValue(body, field)) {
            "true" -> true
            "false" -> false
            else -> return Response(400, TEXT, "Choose on or off".toByteArray())
        }
        if (!Config.put(shell, key, on)) {
            return Response(503, TEXT, "Strike has no shell access, so it cannot save that".toByteArray())
        }
        return settings()
    }
}
