package com.strike.core

import android.util.Base64
import com.strike.daemon.CONFIG_PATH
import com.strike.daemon.STRIKE_DIR
import com.strike.daemon.Shell
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

private const val REVALIDATE_MS = 1_000L
private const val TAG = "Config"

// Shared settings live in a shell-owned, app-readable file; writes require ADB.
object Config {

    private val lock = Any()
    private var held = JSONObject()
    private var readAtMs = 0L
    private var modifiedAtMs = -1L

    fun getString(key: String, fallback: String): String = values().optString(key, fallback)

    fun getInt(key: String, fallback: Int): Int = values().optInt(key, fallback)

    fun getBool(key: String, fallback: Boolean): Boolean = values().optBoolean(key, fallback)

    /** Prefer a shell write; if ADB is down, write a world-readable file the daemon can still open. */
    fun put(shell: Shell, key: String, value: Any): Boolean = synchronized(lock) {
        val merged = JSONObject(values().toString())
        merged.put(key, value)
        val encoded = Base64.encodeToString(merged.toString().toByteArray(), Base64.NO_WRAP)
        if (shell.run(writeLine(encoded)) == 0) {
            readAtMs = 0L
            modifiedAtMs = -1L
            return true
        }
        return writeLocal(merged)
    }

    private fun values(): JSONObject = synchronized(lock) {
        val now = System.currentTimeMillis()
        if (now - readAtMs < REVALIDATE_MS) return held
        readAtMs = now
        val file = File(CONFIG_PATH)
        val modified = file.lastModified()
        if (modified == modifiedAtMs) return held
        modifiedAtMs = modified
        held = read(file)
        held
    }

    private fun read(file: File): JSONObject = try {
        if (file.isFile) JSONObject(file.readText()) else JSONObject()
    } catch (e: JSONException) {
        Logs.w(TAG, "the settings file is not readable json; rewriting on next save")
        JSONObject()
    } catch (e: IOException) {
        Logs.w(TAG, "cannot read the settings file")
        JSONObject()
    }

    private fun writeLocal(merged: JSONObject): Boolean {
        val file = File(CONFIG_PATH)
        return try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "config.json.tmp")
            tmp.writeText(merged.toString())
            tmp.setReadable(true, false)
            tmp.setWritable(true, false)
            if (!tmp.renameTo(file)) {
                file.writeText(merged.toString())
                tmp.delete()
            }
            file.setReadable(true, false)
            file.setWritable(true, false)
            readAtMs = 0L
            modifiedAtMs = -1L
            Logs.w(TAG, "wrote settings without shell")
            true
        } catch (e: IOException) {
            Logs.w(TAG, "cannot write the settings file")
            false
        }
    }
}

// Base64 protects shell arguments; atomic rename prevents partial reads.
internal fun writeLine(base64: String): String {
    val dir = STRIKE_DIR
    val path = CONFIG_PATH
    // Quote the payload — unquoted +/= in base64 breaks toybox echo.
    return ScratchPaths.prepareShellCommand(
        "mkdir -p '$dir' && chmod 777 '$dir' && " +
            "echo '$base64' | base64 -d > '$path.tmp' && chmod 644 '$path.tmp' && " +
            "mv -f '$path.tmp' '$path'"
    )
}
