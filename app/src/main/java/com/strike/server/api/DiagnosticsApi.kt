package com.strike.server.api

import com.strike.core.Config
import com.strike.core.Diagnostics
import com.strike.daemon.Shell
import com.strike.server.JSON
import com.strike.server.Response
import com.strike.server.TEXT
import com.strike.server.formValue
import org.json.JSONObject

class DiagnosticsApi(private val shell: Shell) {

    fun settings(): Response {
        val payload = JSONObject().put("perfLogs", Diagnostics.perfLogs())
        return Response(200, JSON, payload.toString().toByteArray())
    }

    fun save(body: String): Response {
        val on = when (formValue(body, "perfLogs")) {
            "true" -> true
            "false" -> false
            else -> return Response(400, TEXT, "Choose on or off".toByteArray())
        }
        if (!Config.put(shell, Diagnostics.PERF_LOGS, on)) {
            return Response(503, TEXT, "Strike has no shell access, so it cannot save that".toByteArray())
        }
        return settings()
    }
}
