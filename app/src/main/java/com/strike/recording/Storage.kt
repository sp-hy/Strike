package com.strike.recording

import android.content.Context
import com.strike.core.Config
import com.strike.core.Logs
import com.strike.daemon.Shell
import java.io.File
import java.io.IOException

private const val TAG = "Storage"
private const val CLIPS_DIR = "Strike/clips"

class Storage(context: Context, shell: Shell) {

    private val volumes = Volumes(context, shell)

    fun mounted(): Map<String, Volume> = volumes.mounted()

    /** Configured preference (sd by default on new installs). */
    fun preferredLocation(): String =
        Config.getString(RecordingSettings.LOCATION, RecordingSettings.fallback(RecordingSettings.LOCATION))

    /**
     * Volume actually used for clips. Prefer the configured location when mounted;
     * otherwise fall back to internal so recording still works without an SD card.
     */
    fun location(): String {
        val preferred = preferredLocation()
        val volumes = mounted()
        if (volumes.containsKey(preferred)) return preferred
        if (volumes.containsKey(INTERNAL)) return INTERNAL
        return volumes.keys.firstOrNull() ?: preferred
    }

    fun selected(): Volume? = mounted()[location()]

    fun clipsOn(volume: Volume): ClipStore = ClipStore(clipsDir(volume))

    fun usedMb(volume: Volume): Int = (totalBytes(clipsOn(volume).list()) / MB).toInt()

    fun budgetMb(): Int =
        Config.getInt(RecordingSettings.BUDGET_MB, RecordingSettings.BUDGET_FALLBACK_MB)

    // The app resolves the volume and publishes its path for the daemon.
    fun publish(shell: Shell) {
        forgetMounted()
        val active = location()
        val root = volumes.rootFor(active) ?: run {
            Logs.w(TAG, "no volume for $active (preferred ${preferredLocation()})")
            return
        }
        publishWriteDir(shell, File(publicRoot(root.path), CLIPS_DIR), RecordingSettings.CLIPS_DIR)
    }

    fun reap() {
        val volume = selected() ?: return
        val budgetMb = budgetMb()
        val dropped = Retention(clipsOn(volume), budgetMb * MB).enforce(null)
        if (dropped > 0) Logs.d(TAG, "dropped $dropped oldest clips to stay under $budgetMb MB")
    }
}

// Shell UID 2000 cannot write the app's Android/data directory.
internal fun clipsDir(volume: Volume): File = File(publicRoot(volume.dir.path), CLIPS_DIR)

internal fun publicRoot(path: String): String {
    val marker = path.indexOf("/Android/")
    return if (marker > 0) path.substring(0, marker) else path
}

// Probe from the app UID first; on Shark FUSE the app may not write an older
// Strike/clips tree, so fall back to a shell probe (daemon records as uid 2000).
internal fun publishWriteDir(shell: Shell, wanted: File, key: String) {
    val ready = prepareDir(wanted, shell)
    val path = keepWritePath(ready, wanted.absolutePath) ?: run {
        Logs.w(TAG, "cannot write ${wanted.path}")
        return
    }
    if (!ready) Logs.w(TAG, "${wanted.path} will not take files yet")
    if (Config.getString(key, "") == path) return
    if (!Config.put(shell, key, path)) {
        Logs.w(TAG, "could not publish $key=$path")
    } else {
        Logs.d(TAG, "published $key=$path")
    }
}

internal fun keepWritePath(ready: Boolean, path: String): String? {
    if (ready) return path
    // Removable card: keep the path while FUSE remounts.
    if (storageUuid(path) != null) return path
    return null
}

internal fun prepareDir(dir: File, shell: Shell?): Boolean {
    if (storageUuid(dir.path) != null) remount(dir)
    if (!dir.exists() && !dir.mkdirs()) {
        shell?.check("timeout -s KILL 3 mkdir -p \"${dir.absolutePath}\"")
    }
    if (!dir.exists() && shell != null) {
        shell.check("timeout -s KILL 3 mkdir -p \"${dir.absolutePath}\"")
    }
    if (!dir.exists()) return false
    openForDaemon(dir)
    val probe = File(dir, ".probe")
    try {
        probe.writeText("ok")
        probe.delete()
        return true
    } catch (_: IOException) {
        // App UID cannot write (stale ownership / FUSE). Shell records here instead.
        val quoted = dir.absolutePath.replace("'", "'\"'\"'")
        return shell?.check(
            "timeout -s KILL 5 sh -c 'mkdir -p \"$quoted\" && " +
                "echo ok > \"$quoted/.probe\" && rm -f \"$quoted/.probe\"'"
        ) == true
    }
}

private fun openForDaemon(dir: File) {
    var walk: File? = dir
    repeat(3) {
        val at = walk ?: return
        at.setReadable(true, false)
        at.setWritable(true, false)
        at.setExecutable(true, false)
        walk = at.parentFile
    }
}
